package com.citygo.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的订单列表里的一行。orderId 用字符串，避免前端把超过 2^53 的订单号读成另一个数。
 */
@Data
public class VoucherOrderView {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 1 未支付，2 已支付，4 已取消 */
    private Integer status;

    /** 未支付、已支付、已取消、已超时 */
    private String state;

    private String title;

    private String shopName;

    /** 单位是分，和优惠券表里的 pay_value 一样 */
    private Long payValue;

    private LocalDateTime createTime;
}
