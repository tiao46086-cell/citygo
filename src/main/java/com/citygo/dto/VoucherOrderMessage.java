package com.citygo.dto;

import lombok.Data;

/**
 * 秒杀下单消息。消费者在另一条线程里，读不到当时的 UserHolder，所以用户 id 要放在消息里。
 */
@Data
public class VoucherOrderMessage {
    private Long orderId;
    private Long userId;
    private Long voucherId;
}
