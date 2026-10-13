package com.citygo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citygo.entity.ShopType;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 查询商户类型列表，按 sort 升序，优先命中缓存
     * @return 类型列表
     */
    List<ShopType> queryList();
}
