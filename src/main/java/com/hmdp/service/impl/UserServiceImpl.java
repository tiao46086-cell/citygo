package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.BlogFavorite;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.mapper.BlogFavoriteMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.DEMO_LOGIN_LIMIT_KEY;
import static com.hmdp.utils.RedisConstants.DEMO_USER_LOCK_KEY;
import static com.hmdp.utils.RedisConstants.DEMO_USER_SEQ_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.hmdp.utils.RedisConstants.UPLOAD_BLOG_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j 
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /** 190 开头不满足验证码登录的手机号规则，不能靠日志里的验证码登录 */
    private static final String DEMO_PHONE_PREFIX = "190";
    private static final String DEMO_ICON = "/imgs/icons/default-icon.png?v=8";
    private static final int DEMO_USER_LIMIT = 100;
    private static final int DEMO_LOGIN_LIMIT = 10;
    private static final long DEMO_LOGIN_WINDOW_MINUTES = 10L;
    /** 体验 token 两小时过期。空出来的名额才能交给下一位，而不是一直占到 100 个为止 */
    private static final long DEMO_SESSION_MINUTES = 120L;
    private static final long DEMO_LOCK_WAIT_MILLIS = 1000L;
    private static final String DEMO_BUSY = "体验登录繁忙，请稍后再试";
    private static final String DEMO_FULL = "体验名额已满，请等其他体验结束后再试";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private BlogCommentsMapper blogCommentsMapper;

    @Resource
    private BlogFavoriteMapper blogFavoriteMapper;

    @Override
    public Result sendCode(String phone) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存到 Redis，key 里带手机号，2 分钟后自动删除
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 4. 模拟发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2. 从 Redis 取出这个手机号的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        // 3. 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 4. 用户不存在则创建
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        // 5. 验证码只能用一次
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        return Result.ok(issueToken(user));
    }

    @Override
    public Result loginDemo(String clientIp) {
        if (!allowDemoLogin(clientIp)) {
            return Result.fail("体验登录太频繁，请稍后再试");
        }
        DemoAssignment assignment = assignDemoUser();
        if (assignment.user == null) {
            return Result.fail(assignment.failure);
        }
        return Result.ok(issueToken(assignment.user, DEMO_SESSION_MINUTES));
    }

    private boolean allowDemoLogin(String clientIp) {
        String ip = clientIp == null ? "" : clientIp.trim();
        if (ip.isEmpty() || ip.length() > 64 || ip.indexOf('\n') >= 0 || ip.indexOf('\r') >= 0) {
            ip = "unknown";
        }
        String key = DEMO_LOGIN_LIMIT_KEY + ip;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            stringRedisTemplate.expire(key, DEMO_LOGIN_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
        return count != null && count <= DEMO_LOGIN_LIMIT;
    }

    /**
     * 恢复序号和分配新号在同一把锁里做完。手机号已经存在时继续取下一个，不把旧账号直接交出去。
     * 100 个都建过之后，只回收没有登录态的账号，并先清掉它的订单。
     */
    private DemoAssignment assignDemoUser() {
        RLock lock = redissonClient.getLock(DEMO_USER_LOCK_KEY);
        boolean locked = false;
        long deadline = System.currentTimeMillis() + DEMO_LOCK_WAIT_MILLIS;
        try {
            while (!locked) {
                locked = lock.tryLock();
                if (locked) {
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    return DemoAssignment.fail(DEMO_BUSY);
                }
                Thread.sleep(40L);
            }
            return assignDemoUserLocked();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DemoAssignment.fail(DEMO_BUSY);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private DemoAssignment assignDemoUserLocked() {
        User created = createFreshDemoUser();
        if (created != null) {
            return DemoAssignment.ok(created);
        }
        User reclaimed = reclaimIdleDemoUser();
        if (reclaimed == null) {
            return DemoAssignment.fail(DEMO_FULL);
        }
        log.info("体验账号交给新访客, userId={}", reclaimed.getId());
        return DemoAssignment.ok(reclaimed);
    }

    private User createFreshDemoUser() {
        long existing = countDemoUsers();
        if (existing >= DEMO_USER_LIMIT) {
            return null;
        }
        alignDemoSeq(existing);
        for (int i = 0; i < DEMO_USER_LIMIT; i++) {
            Long seq = stringRedisTemplate.opsForValue().increment(DEMO_USER_SEQ_KEY);
            if (seq == null) {
                return null;
            }
            if (seq > DEMO_USER_LIMIT) {
                stringRedisTemplate.opsForValue().set(DEMO_USER_SEQ_KEY, String.valueOf(existing));
                seq = stringRedisTemplate.opsForValue().increment(DEMO_USER_SEQ_KEY);
            }
            if (seq == null || seq > DEMO_USER_LIMIT) {
                return null;
            }
            String phone = String.format("%s%08d", DEMO_PHONE_PREFIX, seq);
            if (query().eq("phone", phone).one() != null) {
                continue;
            }
            try {
                return createDemoUser(phone);
            } catch (DuplicateKeyException e) {
                log.info("体验手机号已被占用，继续下一个, phone={}", phone);
            }
        }
        return null;
    }

    /** Redis 序号若比库里的 190 手机号少，先对齐再自增。调用方必须已经拿着分配锁 */
    private void alignDemoSeq(long existing) {
        String raw = stringRedisTemplate.opsForValue().get(DEMO_USER_SEQ_KEY);
        long seq = 0L;
        if (raw != null) {
            try {
                seq = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                seq = 0L;
            }
        }
        if (seq < existing) {
            stringRedisTemplate.opsForValue().set(DEMO_USER_SEQ_KEY, String.valueOf(existing));
        }
    }

    private long countDemoUsers() {
        return query().likeRight("phone", DEMO_PHONE_PREFIX).count();
    }

    private User reclaimIdleDemoUser() {
        Set<Long> active = activeLoginUserIds();
        List<User> demos = query()
                .likeRight("phone", DEMO_PHONE_PREFIX)
                .orderByAsc("id")
                .last("LIMIT " + DEMO_USER_LIMIT)
                .list();
        for (User user : demos) {
            if (user.getId() == null || active.contains(user.getId())) {
                continue;
            }
            if (!voucherOrderService.releaseDemoOrders(user.getId())) {
                continue;
            }
            clearDemoFootprint(user.getId());
            if (!DEMO_ICON.equals(user.getIcon())) {
                update().set("icon", DEMO_ICON).eq("id", user.getId()).update();
                user.setIcon(DEMO_ICON);
            }
            return user;
        }
        return null;
    }

    private Set<Long> activeLoginUserIds() {
        Set<Long> ids = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(LOGIN_USER_KEY + "*").count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                Object id = stringRedisTemplate.opsForHash().get(cursor.next(), "id");
                if (id == null) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(id.toString()));
                } catch (NumberFormatException ignored) {
                    // 坏掉的登录哈希跳过，不当成某个体验用户还在线
                }
            }
        }
        return ids;
    }

    /** 新访客不该在个人页或笔记里看到上一位留下的内容 */
    private void clearDemoFootprint(Long userId) {
        List<BlogComments> comments = blogCommentsMapper.selectList(
                new QueryWrapper<BlogComments>().eq("user_id", userId));
        for (BlogComments comment : comments) {
            if (comment.getBlogId() == null) {
                continue;
            }
            blogMapper.update(null, new UpdateWrapper<Blog>()
                    .setSql("comments = IF(comments > 0, comments - 1, 0)")
                    .eq("id", comment.getBlogId()));
        }
        blogCommentsMapper.delete(new QueryWrapper<BlogComments>().eq("user_id", userId));
        blogFavoriteMapper.delete(new QueryWrapper<BlogFavorite>().eq("user_id", userId));
        List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>().eq("user_id", userId));
        for (Blog blog : blogs) {
            blogCommentsMapper.delete(new QueryWrapper<BlogComments>().eq("blog_id", blog.getId()));
            blogFavoriteMapper.delete(new QueryWrapper<BlogFavorite>().eq("blog_id", blog.getId()));
            blogMapper.deleteById(blog.getId());
            stringRedisTemplate.delete(BLOG_LIKED_KEY + blog.getId());
        }
        stringRedisTemplate.delete(UPLOAD_BLOG_KEY + userId);
        ScanOptions options = ScanOptions.scanOptions().match(BLOG_LIKED_KEY + "*").count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                stringRedisTemplate.opsForZSet().remove(cursor.next(), userId.toString());
            }
        }
    }

    private User createDemoUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("体验用户" + phone.substring(phone.length() - 4));
        user.setIcon(DEMO_ICON);
        save(user);
        return user;
    }

    private String issueToken(User user) {
        return issueToken(user, LOGIN_USER_TTL);
    }

    private String issueToken(User user, long minutes) {
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String token = UUID.randomUUID().toString(true);
        String tokenKey = LOGIN_USER_KEY + token;
        java.util.Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new java.util.HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((field, value) -> value.toString()));
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, minutes, TimeUnit.MINUTES);
        return token;
    }

    private static final class DemoAssignment {
        private final User user;
        private final String failure;

        private DemoAssignment(User user, String failure) {
            this.user = user;
            this.failure = failure;
        }

        private static DemoAssignment ok(User user) {
            return new DemoAssignment(user, null);
        }

        private static DemoAssignment fail(String failure) {
            return new DemoAssignment(null, failure);
        }
    }

    @Override
    public Result logout(String token) {
        if (token == null || token.isEmpty()) {
            return Result.fail("未登录");
        }
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
