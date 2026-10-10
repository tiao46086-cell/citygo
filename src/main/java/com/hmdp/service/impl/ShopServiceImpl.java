package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /** 等锁的上限。超过就返回，不再递归 */
    private static final long CACHE_LOCK_WAIT_MILLIS = 500L;
    /** 事务提交后再删第二次，盖住提交前读到旧数据、提交后写回缓存的窗口 */
    private static final long CACHE_DELAY_DELETE_MILLIS = 1000L;
    private static final ScheduledExecutorService CACHE_DELAY_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "shop-cache-delay-delete");
        thread.setDaemon(true);
        return thread;
    });

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Shop queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        long deadline = System.currentTimeMillis() + CACHE_LOCK_WAIT_MILLIS;
        while (true) {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, Shop.class);
            }
            if (json != null) {
                return null;
            }
            // tryLock 不传租期，Redisson 才启动看门狗
            RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
            if (!lock.tryLock()) {
                if (System.currentTimeMillis() >= deadline) {
                    throw new IllegalStateException("店铺正在重建，请稍后重试");
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("店铺正在重建，请稍后重试");
                }
                continue;
            }
            try {
                json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json)) {
                    return JSONUtil.toBean(json, Shop.class);
                }
                if (json != null) {
                    return null;
                }
                Shop shop = getById(id);
                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                        CACHE_SHOP_TTL + RandomUtil.randomInt(0, 5), TimeUnit.MINUTES);
                return shop;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 0 行表示这个 id 不存在，不能告诉调用方修改成功
        if (!updateById(shop)) {
            return Result.fail("店铺不存在");
        }
        String cacheKey = CACHE_SHOP_KEY + id;
        // 提交成功后再删。删失败只打日志，过期时间仍会清掉旧数据
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteCacheTwice(cacheKey);
                }
            });
        } else {
            deleteCacheTwice(cacheKey);
        }
        return Result.ok();
    }

    private void deleteCacheTwice(String cacheKey) {
        try {
            stringRedisTemplate.delete(cacheKey);
        } catch (RuntimeException e) {
            log.error("删除商户缓存失败, key={}", cacheKey, e);
        }
        CACHE_DELAY_EXECUTOR.schedule(() -> {
            try {
                stringRedisTemplate.delete(cacheKey);
            } catch (RuntimeException e) {
                log.error("延迟删除商户缓存失败, key={}", cacheKey, e);
            }
        }, CACHE_DELAY_DELETE_MILLIS, TimeUnit.MILLISECONDS);
    }
}