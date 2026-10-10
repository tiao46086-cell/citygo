package com.hmdp.dto;

/**
 * 消费者处理一条秒杀订单消息后的结果。监听器据此决定应答还是重新入队。
 */
public enum VoucherOrderCreateStatus {
    /** 新订单已写入数据库 */
    CREATED,
    /** 这个订单号已经存在，重复投递，不再插入 */
    EXISTS,
    /** 数据库库存不足，已把 Redis 里的库存和购买资格还回去 */
    STOCK_FAILED,
    /** 消息缺字段，应答后丢弃，避免反复入队 */
    INVALID
}
