package com.hmdp.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 给查询接口用。status 是表里的数字；处理中和已超时还没有单独的库状态。
 * orderId 用字符串，避免前端把超过 2^53 的订单号读成另一个数。
 */
@Data
public class VoucherOrderStatus {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 1 未支付，2 已支付，4 已取消。处理中还没有行，这里是 null */
    private Integer status;

    /** 处理中、下单失败、未支付、已支付、已取消、已超时 */
    private String state;
}
