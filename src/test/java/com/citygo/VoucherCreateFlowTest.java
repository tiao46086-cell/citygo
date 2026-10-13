package com.citygo;

import com.citygo.dto.Result;
import com.citygo.entity.Voucher;
import com.citygo.service.IVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员新增券。对着本机 MySQL 和 Redis，测完按标题删掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false"
})
public class VoucherCreateFlowTest {

    @Resource
    private IVoucherService voucherService;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    public void cleanup() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tb_voucher WHERE title LIKE 'citygo-voucher-test%'", Long.class);
        for (Long id : ids) {
            jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", id);
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", id);
            stringRedisTemplate.delete("seckill:stock:" + id);
            stringRedisTemplate.delete("seckill:voucher:" + id);
            stringRedisTemplate.delete("seckill:hold:" + id);
            stringRedisTemplate.delete("seckill:order:" + id);
        }
    }

    @Test
    public void normalVoucherStoresStockAndIgnoresClientType() {
        Voucher voucher = sample("citygo-voucher-test-normal");
        voucher.setId(99L);
        voucher.setType(1);
        voucher.setStock(7);
        Result result = voucherService.addNormalVoucher(voucher);
        Assertions.assertEquals(Boolean.TRUE, result.getSuccess());
        Long id = (Long) result.getData();
        Assertions.assertNotEquals(Long.valueOf(99L), id);
        Assertions.assertEquals(Integer.valueOf(0), column(id, "type"));
        Assertions.assertEquals(Integer.valueOf(7), column(id, "stock"));
        Assertions.assertEquals(Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, id));
    }

    @Test
    public void missingNormalStockDoesNotInsert() {
        Voucher voucher = sample("citygo-voucher-test-empty");
        voucher.setStock(null);
        Result result = voucherService.addNormalVoucher(voucher);
        Assertions.assertEquals(Boolean.FALSE, result.getSuccess());
        Assertions.assertEquals(Integer.valueOf(0), countByTitle("citygo-voucher-test-empty"));
    }

    @Test
    public void seckillCreateForcesTypeAndStock() {
        Voucher voucher = sample("citygo-voucher-test-seckill");
        voucher.setType(null);
        voucher.setStatus(null);
        voucher.setStock(4);
        voucher.setBeginTime(LocalDateTime.now().minusHours(1));
        voucher.setEndTime(LocalDateTime.now().plusDays(1));
        Result result = voucherService.addSeckillVoucher(voucher);
        Assertions.assertEquals(Boolean.TRUE, result.getSuccess());
        Long id = (Long) result.getData();
        Assertions.assertEquals(Integer.valueOf(1), column(id, "type"));
        Assertions.assertEquals(Integer.valueOf(1), column(id, "status"));
        Assertions.assertNull(column(id, "stock"));
        Assertions.assertEquals(Integer.valueOf(4), jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, id));
        Assertions.assertEquals("4", stringRedisTemplate.opsForValue().get("seckill:stock:" + id));
    }

    @Test
    public void seckillWithoutStockDoesNotInsert() {
        Voucher voucher = sample("citygo-voucher-test-seckill-empty");
        voucher.setStock(0);
        voucher.setBeginTime(LocalDateTime.now().minusHours(1));
        voucher.setEndTime(LocalDateTime.now().plusDays(1));
        Result result = voucherService.addSeckillVoucher(voucher);
        Assertions.assertEquals(Boolean.FALSE, result.getSuccess());
        Assertions.assertEquals(Integer.valueOf(0), countByTitle("citygo-voucher-test-seckill-empty"));
    }

    private Voucher sample(String title) {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle(title);
        voucher.setPayValue(100L);
        voucher.setActualValue(100L);
        voucher.setStatus(1);
        return voucher;
    }

    private Integer column(Long id, String name) {
        return jdbcTemplate.queryForObject("SELECT " + name + " FROM tb_voucher WHERE id = ?", Integer.class, id);
    }

    private Integer countByTitle(String title) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher WHERE title = ?", Integer.class, title);
    }
}
