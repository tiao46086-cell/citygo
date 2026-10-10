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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /** 第一次删除后，再等这么久删第二次，盖住事务提交前被写回的旧缓存 */
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
        // 4. 真正的 miss。tryLock 不传租期，Redisson 才启动看门狗；传了固定秒数就不会续期
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        boolean isLock = lock.tryLock();
        // 5. 没抢到锁：别的线程正在重建，休眠后重查缓存
        if (!isLock) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return queryById(id);
        }
        // 6. 抢到锁后再读一次缓存。等待期间可能已经有人回填了
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
                // 查不到也缓存空值，防穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL + RandomUtil.randomInt(0, 5), TimeUnit.MINUTES);
            return shop;
        } finally {
            // 7. 只有自己持有才解锁。Redisson 用 Lua 核对线程标识，不会删掉别人刚加上的锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
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
        // 1. 先改数据库。缓存里可能是上次查询回填的旧 JSON，不能拿请求体直接覆盖
        updateById(shop);
        // 2. 马上删一次。删失败会抛异常，事务把刚才的数据库修改回滚
        String cacheKey = CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(cacheKey);
        // 3. 1 秒后再删一次。这次删除在别的线程里，不挡住接口返回
        CACHE_DELAY_EXECUTOR.schedule(() -> stringRedisTemplate.delete(cacheKey),
                CACHE_DELAY_DELETE_MILLIS, TimeUnit.MILLISECONDS);
        return Result.ok();
    }
}