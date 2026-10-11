package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.DEMO_LOGIN_LIMIT_KEY;
import static com.hmdp.utils.RedisConstants.DEMO_USER_SEQ_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
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
    private static final int DEMO_USER_LIMIT = 100;
    private static final int DEMO_LOGIN_LIMIT = 10;
    private static final long DEMO_LOGIN_WINDOW_MINUTES = 10L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
        User user = assignDemoUser();
        if (user == null) {
            return Result.fail("体验名额已满，请稍后再试");
        }
        return Result.ok(issueToken(user));
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
     * 按序号新建体验用户。Redis 序号丢了时，从已有的 190 手机号接着编号，避免撞上唯一索引。
     * 满员后不再复用旧账号，否则后进来的人会看到别人的订单，也不能再抢券。
     */
    private User assignDemoUser() {
        Long seq = stringRedisTemplate.opsForValue().increment(DEMO_USER_SEQ_KEY);
        if (seq == null) {
            return null;
        }
        if (seq == 1) {
            long existing = query().likeRight("phone", DEMO_PHONE_PREFIX).count();
            if (existing > 0) {
                stringRedisTemplate.opsForValue().set(DEMO_USER_SEQ_KEY, String.valueOf(existing));
                seq = stringRedisTemplate.opsForValue().increment(DEMO_USER_SEQ_KEY);
            }
        }
        if (seq == null || seq > DEMO_USER_LIMIT) {
            return null;
        }
        String phone = String.format("%s%08d", DEMO_PHONE_PREFIX, seq);
        User existing = query().eq("phone", phone).one();
        if (existing != null) {
            return existing;
        }
        try {
            return createDemoUser(phone);
        } catch (DuplicateKeyException e) {
            return query().eq("phone", phone).one();
        }
    }

    private User createDemoUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("体验用户" + phone.substring(phone.length() - 4));
        user.setIcon("/imgs/icons/default-icon.png?v=8");
        save(user);
        return user;
    }

    private String issueToken(User user) {
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String token = UUID.randomUUID().toString(true);
        String tokenKey = LOGIN_USER_KEY + token;
        java.util.Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new java.util.HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((field, value) -> value.toString()));
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return token;
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
