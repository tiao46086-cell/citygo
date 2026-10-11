package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogFavorite;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogFavoriteMapper;
import com.hmdp.service.IBlogFavoriteService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BlogFavoriteServiceImpl extends ServiceImpl<BlogFavoriteMapper, BlogFavorite> implements IBlogFavoriteService {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Override
    public Result favorite(Long blogId) {
        if (blogId == null || blogService.getById(blogId) == null) {
            return Result.fail("笔记不存在");
        }
        Long userId = UserHolder.getUser().getId();
        if (alreadyFavorite(userId, blogId)) {
            return Result.ok();
        }
        BlogFavorite favorite = new BlogFavorite();
        favorite.setUserId(userId);
        favorite.setBlogId(blogId);
        try {
            save(favorite);
        } catch (DuplicateKeyException ignored) {
            return Result.ok();
        }
        return Result.ok();
    }

    @Override
    public Result cancel(Long blogId) {
        Long userId = UserHolder.getUser().getId();
        update().eq("user_id", userId).eq("blog_id", blogId).remove();
        return Result.ok();
    }

    @Override
    public Result isFavorite(Long blogId) {
        Long userId = UserHolder.getUser().getId();
        return Result.ok(alreadyFavorite(userId, blogId));
    }

    @Override
    public Result pageMine(Integer current) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Long userId = UserHolder.getUser().getId();
        Page<BlogFavorite> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(pageNo, SystemConstants.DEFAULT_PAGE_SIZE));
        List<Long> blogIds = page.getRecords().stream()
                .map(BlogFavorite::getBlogId)
                .collect(Collectors.toList());
        if (blogIds.isEmpty()) {
            return Result.ok(new ArrayList<Blog>(), page.getTotal());
        }
        Map<Long, Blog> blogs = blogService.listByIds(blogIds).stream()
                .collect(Collectors.toMap(Blog::getId, blog -> blog));
        List<Blog> ordered = new ArrayList<>();
        for (Long blogId : blogIds) {
            Blog blog = blogs.get(blogId);
            if (blog != null) {
                ordered.add(blog);
            }
        }
        fillAuthors(ordered);
        return Result.ok(ordered, page.getTotal());
    }

    private boolean alreadyFavorite(Long userId, Long blogId) {
        return query().eq("user_id", userId).eq("blog_id", blogId).count() > 0;
    }

    private void fillAuthors(List<Blog> blogs) {
        List<Long> userIds = blogs.stream()
                .map(Blog::getUserId)
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, User> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        for (Blog blog : blogs) {
            User user = users.get(blog.getUserId());
            if (user == null) {
                continue;
            }
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }
}
