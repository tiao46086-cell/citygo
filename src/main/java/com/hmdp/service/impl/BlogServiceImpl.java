package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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
        Double score = stringRedisTemplate.opsForZSet().score(key, member);
        if (score == null) {
            Boolean added = stringRedisTemplate.opsForZSet().add(key, member, System.currentTimeMillis());
            if (!Boolean.TRUE.equals(added)) {
                return Result.ok();
            }
            boolean updated = update()
                    .setSql("liked = IFNULL(liked, 0) + 1")
                    .eq("id", id)
                    .update();
            if (!updated) {
                stringRedisTemplate.opsForZSet().remove(key, member);
                return Result.fail("笔记不存在");
            }
            return Result.ok();
        }
        Long removed = stringRedisTemplate.opsForZSet().remove(key, member);
        if (removed != null && removed > 0) {
            update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
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
}
