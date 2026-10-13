package com.citygo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citygo.dto.Result;
import com.citygo.dto.UserDTO;
import com.citygo.entity.Blog;
import com.citygo.entity.BlogComments;
import com.citygo.entity.BlogFavorite;
import com.citygo.entity.User;
import com.citygo.mapper.BlogCommentsMapper;
import com.citygo.mapper.BlogFavoriteMapper;
import com.citygo.mapper.BlogMapper;
import com.citygo.service.IBlogService;
import com.citygo.service.IUserService;
import com.citygo.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.citygo.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int LIKE_USER_LIMIT = 5;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private BlogCommentsMapper blogCommentsMapper;

    @Resource
    private BlogFavoriteMapper blogFavoriteMapper;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result likeBlog(Long id) {
        if (id == null || getById(id) == null) {
            return Result.fail("笔记不存在");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        String key = BLOG_LIKED_KEY + id;
        String member = user.getId().toString();
        RLock lock = redissonClient.getLock("lock:blog:like:" + id + ":" + member);
        lock.lock();
        try {
            return toggleLike(id, key, member);
        } finally {
            lock.unlock();
        }
    }

    private Result toggleLike(Long id, String key, String member) {
        Double score = stringRedisTemplate.opsForZSet().score(key, member);
        if (score == null) {
            Boolean added = stringRedisTemplate.opsForZSet().add(key, member, System.currentTimeMillis());
            if (!Boolean.TRUE.equals(added)) {
                return Result.ok();
            }
            boolean updated;
            try {
                updated = update()
                    .setSql("liked = IFNULL(liked, 0) + 1")
                    .eq("id", id)
                    .update();
            } catch (RuntimeException e) {
                try {
                    stringRedisTemplate.opsForZSet().remove(key, member);
                } catch (RuntimeException compensation) {
                    e.addSuppressed(compensation);
                }
                throw e;
            }
            if (!updated) {
                stringRedisTemplate.opsForZSet().remove(key, member);
                return Result.fail("笔记不存在");
            }
            return Result.ok();
        }
        Long removed = stringRedisTemplate.opsForZSet().remove(key, member);
        if (removed != null && removed > 0) {
            try {
                if (!update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update()) {
                    stringRedisTemplate.opsForZSet().add(key, member, score);
                    return Result.fail("点赞更新失败，请重试");
                }
            } catch (RuntimeException e) {
                try {
                    stringRedisTemplate.opsForZSet().add(key, member, score);
                } catch (RuntimeException compensation) {
                    e.addSuppressed(compensation);
                }
                throw e;
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        User author = userService.getById(blog.getUserId());
        if (author != null) {
            blog.setName(author.getNickName());
            blog.setIcon(author.getIcon());
        }
        fillLikeStatus(Collections.singletonList(blog));
        return Result.ok(blog);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> members = stringRedisTemplate.opsForZSet()
                .reverseRange(BLOG_LIKED_KEY + id, 0, LIKE_USER_LIMIT - 1L);
        if (members == null || members.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = new ArrayList<>();
        for (String member : members) {
            userIds.add(Long.valueOf(member));
        }
        Map<Long, User> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        List<UserDTO> ordered = new ArrayList<>();
        for (Long userId : userIds) {
            User user = users.get(userId);
            if (user == null) {
                continue;
            }
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
            ordered.add(dto);
        }
        return Result.ok(ordered);
    }

    @Override
    public void fillLikeStatus(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        UserDTO user = UserHolder.getUser();
        for (Blog blog : blogs) {
            if (user == null) {
                blog.setIsLike(false);
                continue;
            }
            Double score = stringRedisTemplate.opsForZSet()
                    .score(BLOG_LIKED_KEY + blog.getId(), user.getId().toString());
            blog.setIsLike(score != null);
        }
    }

    @Override
    public Result updateOwn(Long id, Blog incoming) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        Blog blog = id == null ? null : getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        if (!user.getId().equals(blog.getUserId())) {
            return Result.fail("无权限");
        }
        boolean updated = update()
                .set("title", incoming.getTitle())
                .set("content", incoming.getContent())
                .set("shop_id", incoming.getShopId())
                .set("images", incoming.getImages() == null ? "" : incoming.getImages())
                .eq("id", id)
                .eq("user_id", user.getId())
                .update();
        if (!updated) {
            return Result.fail("笔记不存在");
        }
        return Result.ok();
    }

    @Override
    @Transactional
    public Result deleteOwn(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        Blog blog = id == null ? null : getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        if (!user.getId().equals(blog.getUserId())) {
            return Result.fail("无权限");
        }
        blogCommentsMapper.delete(new QueryWrapper<BlogComments>().eq("blog_id", id));
        blogFavoriteMapper.delete(new QueryWrapper<BlogFavorite>().eq("blog_id", id));
        removeById(id);
        stringRedisTemplate.delete(BLOG_LIKED_KEY + id);
        return Result.ok();
    }
}
