package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀下单：Lua 通过后发送消息，并等待 RabbitMQ 的发布确认
     * @param voucherId 优惠券 id，对应 tb_seckill_voucher.voucher_id
     * @return 确认被队列接受时 data 为订单 id。数据库由消费者稍后写入
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 把未支付订单改成已支付。不碰库存，库存在下单时已经扣过。
     * @param orderId 订单号
     * @return 成功，或订单不存在、已支付、已取消
     */
    Result payOrder(Long orderId);

    /**
     * 消费者调用：按订单号幂等写入。库存扣失败时回滚 Redis 资格
     * @param message 队列里的订单号、用户 id、优惠券 id
     * @return 监听器据此应答，或在抛异常时重新入队
     */
    VoucherOrderCreateStatus createVoucherOrder(VoucherOrderMessage message);

    /**
     * 只取消仍未支付的订单，并退回 MySQL 库存和 Redis 资格。已经支付或已经取消则什么都不做。
     * @param orderId 订单号
     */
    void cancelUnpaidOrder(Long orderId);
}
