package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_VOUCHER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public Result addSeckillVoucher(Voucher voucher) {
        if (voucher.getStock() == null || voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            return Result.fail("库存、开始时间、结束时间不能为空");
        }
        if (!voucher.getEndTime().isAfter(voucher.getBeginTime())) {
            return Result.fail("结束时间必须晚于开始时间");
        }
        // 1. 保存优惠券。id 由数据库自增，后面的 Redis key 要用这个 id
        save(voucher);
        // 2. 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 3. 写入 Redis。这里抛异常会让上面两次插入一起回滚
        String voucherId = voucher.getId().toString();
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, voucher.getStock().toString());
        Map<String, String> timeMap = new HashMap<>();
        timeMap.put("beginTime", String.valueOf(toEpochSecond(voucher.getBeginTime())));
        timeMap.put("endTime", String.valueOf(toEpochSecond(voucher.getEndTime())));
        stringRedisTemplate.opsForHash().putAll(SECKILL_VOUCHER_KEY + voucherId, timeMap);
        return Result.ok(voucher.getId());
    }

    /**
     * LocalDateTime 没有时区。按 JVM 默认时区换成「从 1970-01-01 起的秒数」，Lua 才能和 Redis 的 TIME 比较。
     */
    private long toEpochSecond(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
