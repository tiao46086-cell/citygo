package com.hmdp.dto;

import lombok.Data;

/**
 * 延迟取消消息。到点后只靠订单号查出库里的用户和券，再决定要不要还库存。
 */
@Data
public class VoucherOrderCancelMessage {
    private Long orderId;
}
