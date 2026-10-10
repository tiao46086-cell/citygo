package com.hmdp.dto;

import lombok.Data;

/**
 * 给查询接口用。status 是表里的数字；处理中和已超时还没有单独的库状态。
 */
@Data
public class VoucherOrderStatus {

    private Long orderId;

    /** 1 未支付，2 已支付，4 已取消。处理中还没有行，这里是 null */
    private Integer status;

    /** 处理中、未支付、已支付、已取消、已超时 */
    private String state;
}
