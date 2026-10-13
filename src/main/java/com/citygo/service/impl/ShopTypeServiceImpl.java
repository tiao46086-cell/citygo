package com.citygo.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citygo.entity.ShopType;
import com.citygo.mapper.ShopTypeMapper;
import com.citygo.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.citygo.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.citygo.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryList() {
        // 1. 先查缓存
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        // 2. 命中则反序列化返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toList(json, ShopType.class);
        }
        // 3. 未命中，查数据库，按 sort 升序
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4. 回填缓存，30 分钟后过期
        stringRedisTemplate.opsForValue()
                .set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return typeList;
    }
}