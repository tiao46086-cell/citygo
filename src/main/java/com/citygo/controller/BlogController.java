package com.citygo.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citygo.dto.Result;
import com.citygo.dto.UserDTO;
import com.citygo.entity.Blog;
import com.citygo.entity.User;
import com.citygo.service.IBlogService;
import com.citygo.service.IShopService;
import com.citygo.service.IUserService;
import com.citygo.utils.SystemConstants;
import com.citygo.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    private static final int TITLE_LIMIT = 255;
    private static final int CONTENT_LIMIT = 2048;
    private static final int IMAGES_LIMIT = 2048;

    @Resource
    private IBlogService blogService;
    @Resource
    private IShopService shopService;
    @Resource
    private IUserService userService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        Result invalid = prepareBlog(blog);
        if (invalid != null) {
            return invalid;
        }
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        blog.setId(null);
        blog.setLiked(0);
        blog.setComments(0);
        blog.setCreateTime(null);
        blog.setUpdateTime(null);
        blogService.save(blog);
        return Result.ok(blog.getId());
    }

    /**
     * /blog/me/* 有两段，不会被「笔记详情免登录」放行。
     */
    @PutMapping("/me/{id}")
    public Result updateMyBlog(@PathVariable("id") Long id, @RequestBody Blog blog) {
        Result invalid = prepareBlog(blog);
        if (invalid != null) {
            return invalid;
        }
        return blogService.updateOwn(id, blog);
    }

    @DeleteMapping("/me/{id}")
    public Result deleteMyBlog(@PathVariable("id") Long id) {
        return blogService.deleteOwn(id);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 收集这一页涉及的所有作者 id 并去重（同一作者发多条只查一次）
        List<Long> userIds = records.stream()
                .map(Blog::getUserId)
                .distinct()
                .collect(Collectors.toList());
        // 空列表直接返回，避免 in () 语法错误
        if (userIds.isEmpty()) {
            blogService.fillLikeStatus(records);
            return Result.ok(records);
        }
        // 一次 IN 查询拿回所有作者，组装成 id -> User 的 Map
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        // 遍历笔记，从 Map 取作者信息，不再单独查库（消除 N+1）
        for (Blog blog : records) {
            User user = userMap.get(blog.getUserId());
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
        }
        blogService.fillLikeStatus(records);
        return Result.ok(records);
    }

    /**
     * 通过时把标题、正文、图片整理好并返回 null。不通过时返回失败结果。
     */
    private Result prepareBlog(Blog blog) {
        if (blog == null) {
            return Result.fail("笔记不能为空");
        }
        String title = blog.getTitle() == null ? "" : blog.getTitle().trim();
        String content = blog.getContent() == null ? "" : blog.getContent().trim();
        if (title.isEmpty()) {
            return Result.fail("请填写标题");
        }
        if (title.length() > TITLE_LIMIT) {
            return Result.fail("标题太长");
        }
        if (content.isEmpty()) {
            return Result.fail("请填写正文");
        }
        if (content.length() > CONTENT_LIMIT) {
            return Result.fail("正文太长");
        }
        if (blog.getShopId() == null) {
            return Result.fail("请选择商户");
        }
        if (shopService.getById(blog.getShopId()) == null) {
            return Result.fail("商户不存在");
        }
        String images = blog.getImages() == null ? "" : blog.getImages().trim();
        if (images.length() > IMAGES_LIMIT) {
            return Result.fail("图片太多");
        }
        blog.setTitle(title);
        blog.setContent(content);
        blog.setImages(images);
        return null;
    }
}
