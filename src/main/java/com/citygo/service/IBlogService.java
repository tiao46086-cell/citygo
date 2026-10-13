package com.citygo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citygo.dto.Result;
import com.citygo.entity.Blog;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 点一次是点赞，再点一次是取消。同一个人在 Redis 里只留一条。
     */
    Result likeBlog(Long id);

    /**
     * 笔记详情，带作者，以及当前用户是否点过赞。
     */
    Result queryBlogById(Long id);

    /**
     * 最近点赞的用户，最多 5 个。只返回 id、昵称、头像。
     */
    Result queryBlogLikes(Long id);

    /**
     * 给这一页笔记填上当前用户是否点过赞。
     */
    void fillLikeStatus(List<Blog> blogs);

    /**
     * 只改自己的笔记。点赞数和评论数不动。
     */
    Result updateOwn(Long id, Blog blog);

    /**
     * 只删自己的笔记，同时删掉它的评论、收藏和点赞记录。
     */
    Result deleteOwn(Long id);
}
