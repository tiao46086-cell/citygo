package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    /**
     * 保存秒杀券，并把库存、开始时间、结束时间写入 Redis
     * @param voucher 优惠券，stock、beginTime、endTime 必须有值
     * @return 成功时 data 为优惠券 id
     */
    Result addSeckillVoucher(Voucher voucher);
}
