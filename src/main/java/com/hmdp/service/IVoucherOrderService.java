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
     * 查自己的订单。库里还没有行、但 Redis 里有这次预占时，返回处理中。
     * @param orderId 订单号
     */
    Result queryOrder(Long orderId);

    /**
     * 把未支付且未超过支付期限的订单改成已支付。这是模拟支付，不碰库存。
     * @param orderId 订单号
     * @return 成功，或订单不存在、已支付、已取消、已超时
     */
    Result payOrder(Long orderId);

    /**
     * 消费者调用：按订单号幂等写入。库存扣失败时回滚这一单的 Redis 预占
     * @param message 队列里的订单号、用户 id、优惠券 id
     * @return 监听器据此应答，或在抛异常时进入有限次重试
     */
    VoucherOrderCreateStatus createVoucherOrder(VoucherOrderMessage message);

    /**
     * 只改数据库：未支付改为已取消，并退回 MySQL 库存。不碰 Redis，避免和数据库事务缠在一起。
     * @param orderId 订单号
     */
    void markOrderCancelled(Long orderId);

    /**
     * 数据库已经是已取消时，按订单号回补 Redis。预占如果已经换成新订单，脚本不会动它。
     * @param orderId 订单号
     */
    void restoreRedisIfCancelled(Long orderId);
}
