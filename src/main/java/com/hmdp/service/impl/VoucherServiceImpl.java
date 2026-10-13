package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
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

    private static final int TYPE_NORMAL = 0;
    private static final int TYPE_SECKILL = 1;
    private static final int STATUS_ON = 1;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IShopService shopService;

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
    public Result addNormalVoucher(Voucher voucher) {
        Result invalid = prepareVoucher(voucher, TYPE_NORMAL, false);
        if (invalid != null) {
            return invalid;
        }
        save(voucher);
        int written = getBaseMapper().initNormalStock(voucher.getId(), voucher.getStock());
        if (written != 1) {
            throw new IllegalStateException("普通券库存没有写入, id=" + voucher.getId());
        }
        return Result.ok(voucher.getId());
    }

    @Override
    @Transactional
    public Result addSeckillVoucher(Voucher voucher) {
        if (voucher == null || voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            return Result.fail("库存、开始时间、结束时间不能为空");
        }
        if (!voucher.getEndTime().isAfter(voucher.getBeginTime())) {
            return Result.fail("结束时间必须晚于开始时间");
        }
        Result invalid = prepareVoucher(voucher, TYPE_SECKILL, true);
        if (invalid != null) {
            return invalid;
        }
        // 1. 保存优惠券。id 由数据库自增，后面的 Redis key 要用这个 id。type 已固定为秒杀券
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
     * 请求里的 id、类型和时间不采用。普通券库存可以为 0；秒杀券必须大于 0，并且是上架。
     * @return 不通过时的失败结果，通过时 null
     */
    private Result prepareVoucher(Voucher voucher, int type, boolean seckill) {
        if (voucher == null) {
            return Result.fail("优惠券不能为空");
        }
        String title = voucher.getTitle() == null ? "" : voucher.getTitle().trim();
        if (title.isEmpty() || title.length() > 255) {
            return Result.fail("请填写标题");
        }
        if (voucher.getShopId() == null || shopService.getById(voucher.getShopId()) == null) {
            return Result.fail("商户不存在");
        }
        if (voucher.getPayValue() == null || voucher.getPayValue() < 0
                || voucher.getActualValue() == null || voucher.getActualValue() < 0) {
            return Result.fail("金额不正确");
        }
        Integer stock = voucher.getStock();
        if (stock == null || stock < 0 || (seckill && stock == 0)) {
            return Result.fail(seckill ? "库存必须大于 0" : "库存不能为负数");
        }
        Integer status = voucher.getStatus();
        if (status == null) {
            status = STATUS_ON;
        } else if (status != 1 && status != 2 && status != 3) {
            return Result.fail("上下架状态不正确");
        }
        if (seckill && status != STATUS_ON) {
            return Result.fail("秒杀券需要先上架");
        }
        voucher.setTitle(title);
        voucher.setStatus(status);
        voucher.setType(type);
        voucher.setId(null);
        voucher.setCreateTime(null);
        voucher.setUpdateTime(null);
        return null;
    }

    /**
     * LocalDateTime 没有时区。活动时间固定按北京时间解释，不依赖 JVM 所在机器。
     */
    private long toEpochSecond(LocalDateTime time) {
        return time.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }
}
