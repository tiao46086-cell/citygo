package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogFavorite;

public interface IBlogFavoriteService extends IService<BlogFavorite> {

    /**
     * 收藏一篇笔记。同一用户重复收藏仍然成功，库里只留一行。
     */
    Result favorite(Long blogId);

    /**
     * 取消自己的收藏。本来就没收藏也算成功。
     */
    Result cancel(Long blogId);

    /**
     * 当前用户有没有收藏这篇笔记。
     */
    Result isFavorite(Long blogId);

    /**
     * 我的收藏，按收藏时间倒序，带作者昵称和头像。
     */
    Result pageMine(Integer current);
}
