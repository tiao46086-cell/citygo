package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    /**
     * 发一条一级评论。parent_id 和 answer_id 都写 0。
     */
    Result addComment(Long blogId, String content);

    /**
     * 按笔记分页查一级评论，带作者昵称和头像。
     */
    Result pageByBlog(Long blogId, Integer current);

    /**
     * 只能删自己的一级评论。
     */
    Result deleteOwn(Long id);
}
