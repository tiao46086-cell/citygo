package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
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

    @Value("${citygo.shop-cache.maximum-size:1000}")
    private long localCacheMaxSize;

    @Value("${citygo.shop-cache.ttl:60s}")
    private Duration localCacheTtl;

    /** 店铺详情。expireAfterWrite，空结果不放进来。recordStats 打开，方便以后读命中率 */
    private Cache<Long, Shop> localCache;

    @PostConstruct
    void initLocalCache() {
        localCache = Caffeine.newBuilder()
                .maximumSize(localCacheMaxSize)
                .expireAfterWrite(localCacheTtl)
                .recordStats()
                .build();
    }

    @Override
    public Shop queryById(Long id) {
        Shop cached = localCache.getIfPresent(id);
        if (cached != null) {
            return cached;
        }
        Shop shop = loadThroughRedis(id);
        if (shop != null) {
            localCache.put(id, shop);
        }
        return shop;
    }

    private Shop loadThroughRedis(Long id) {
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
        // 提交成功后再删。本地缓存和 Redis 分开删，一边失败不影响另一边
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteCacheTwice(id);
                }
            });
        } else {
            deleteCacheTwice(id);
        }
        return Result.ok();
    }

    private void deleteCacheTwice(Long id) {
        deleteLocalAndRedis(id);
        CACHE_DELAY_EXECUTOR.schedule(() -> deleteLocalAndRedis(id), CACHE_DELAY_DELETE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void deleteLocalAndRedis(Long id) {
        try {
            localCache.invalidate(id);
        } catch (RuntimeException e) {
            log.error("删除商户本地缓存失败, id={}", id, e);
        }
        try {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        } catch (RuntimeException e) {
            log.error("删除商户缓存失败, key={}", CACHE_SHOP_KEY + id, e);
        }
    }
}