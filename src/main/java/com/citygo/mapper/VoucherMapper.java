package com.citygo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);

    /** 普通券插入后单独写库存。秒杀券不走这一句 */
    @Update("UPDATE tb_voucher SET stock = #{stock} WHERE id = #{id} AND type = 0")
    int initNormalStock(@Param("id") Long id, @Param("stock") Integer stock);

    /** 只扣上架中的普通券。条件不满足时返回 0，不会把库存扣成负数 */
    @Update("UPDATE tb_voucher SET stock = stock - 1 WHERE id = #{id} AND type = 0 AND status = 1 AND stock > 0")
    int deductNormalStock(@Param("id") Long id);

    /** 普通券取消未支付订单时加回 1。秒杀券不走这一句 */
    @Update("UPDATE tb_voucher SET stock = stock + 1 WHERE id = #{id} AND type = 0")
    int restoreNormalStock(@Param("id") Long id);
}
