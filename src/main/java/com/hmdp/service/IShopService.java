package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据 id 查询商铺，优先查缓存，缓存未命中再查数据库并回填
     * @param id 商铺id
     * @return 商铺，不存在返回 null
     */
    Shop queryById(Long id);
}
