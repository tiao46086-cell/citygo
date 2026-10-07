package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Shop queryById(Long id) {
        // 1. 先查缓存
        String key = CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 缓存命中，且存的是有效数据，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, Shop.class);
        }
        // 3. key 存在但存的是空串，命中空值，直接返回 null
        if (json != null) {
            return null;
        }
        // 4. 真正的 miss，先抢锁，防击穿（同一 id 只允许一个线程重建缓存）
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 5. 没抢到锁：说明别的线程正在重建，休眠后重查缓存
        if (!isLock) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return queryById(id);
        }
        // 6. 抢到锁，查数据库并回填
        try {
            Shop shop = getById(id);
            if (shop == null) {
                // 查不到也缓存空值，防穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL + RandomUtil.randomInt(0, 5), TimeUnit.MINUTES);
            return shop;
        } finally {
            // 7. 释放锁，用 finally 保证异常也能删锁，避免死锁
            stringRedisTemplate.delete(lockKey);
        }
    }
}