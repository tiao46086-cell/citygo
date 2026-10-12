package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /** 占住订单号。status=0 表示创建和终止都还没赢。主键冲突时不动已有行 */
    @Insert("INSERT IGNORE INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (#{id}, #{userId}, #{voucherId}, 0)")
    int insertUnclaimed(@Param("id") Long id, @Param("userId") Long userId, @Param("voucherId") Long voucherId);

    /** 只有还没分出结果的行能改状态。返回 1 表示这次赢了，0 表示另一边已经赢了 */
    @Update("UPDATE tb_voucher_order SET status = #{status} WHERE id = #{id} AND status = 0")
    int claim(@Param("id") Long id, @Param("status") int status);
}
