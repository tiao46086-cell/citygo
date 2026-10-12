package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;

import java.util.List;

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
     * @return 受理时 data 是订单号字符串。数据库由消费者稍后写入
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 普通券下单。同一个事务里扣 tb_voucher.stock 并写入未支付订单，再沿用秒杀那条延迟取消。
     * @param voucherId 优惠券 id，type 必须是普通券
     * @return data 是订单号字符串
     */
    Result placeOrder(Long voucherId);

    /**
     * 查自己的订单。库里还没有行、但 Redis 里有这次预占时，返回处理中。预占已退回时返回下单失败。
     * @param orderId 订单号
     */
    Result queryOrder(Long orderId);

    /**
     * 当前用户的订单，按创建时间从新到旧分页。只包含已经写入数据库的订单。
     * @param current 页码，从 1 开始
     */
    Result pageMine(Integer current);

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
     * 未支付改为已取消，并按券类型退回 MySQL 库存。秒杀券提交前只记下 Redis 回补任务；普通券只加 tb_voucher.stock。
     * @param orderId 订单号
     */
    void markOrderCancelled(Long orderId);

    /**
     * 体验账号交给下一位访客之前调用。未支付的先按券类型退库存。订单行保留，并用新的订单号作为分界，
     * 这个号以及更早的订单不再展示。已支付的秒杀不退库存，只清掉购买资格。还有未入库的秒杀预占时返回 false。
     * @param userId 体验用户 id
     * @return false 表示先不要把这个账号给别人
     */
    boolean releaseDemoOrders(Long userId);

    /**
     * 下单消息次数用完仍没有订单时，退回 Redis 库存和购买资格，并记下失败状态。
     * 库里已经有这一单时只去掉预占，不再加库存。
     * @return false 表示 Redis 没写成功，调用方不要应答原消息
     */
    boolean abandonFailedCreate(Long orderId, Long userId, Long voucherId);

    /**
     * 数据库已经是已取消时，按订单号回补 Redis。预占如果已经换成新订单，脚本不会动它。
     * @param orderId 订单号
     */
    void restoreRedisIfCancelled(Long orderId);

    /**
     * 消费失败后改由重试队列接手。扫描器看到这个状态就不再补发，避免把次数清零。
     * @param orderId 订单号
     */
    void markPendingRetrying(Long orderId);

    /**
     * 已经进失败队列，不再自动补发。
     * @param orderId 订单号
     */
    void markPendingStopped(Long orderId);

    /**
     * 补发已经发出或结果不确定时，把下次补发时间推后。查询订单不会调用这里。
     * @param orderId 订单号
     */
    void postponePendingRetry(Long orderId);

    /**
     * 支付期限已过、仍是未支付的订单。延迟消息丢了时由扫描器取消。
     * @param limit 最多取多少条
     * @return 订单号
     */
    List<Long> findExpiredUnpaidIds(int limit);
}
