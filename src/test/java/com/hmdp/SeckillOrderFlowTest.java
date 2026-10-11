package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 对着本机 MySQL 和 Redis。不启动 Web，也不再挂一个消费者。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false"
})
public class SeckillOrderFlowTest {

    private static final long VOUCHER_ID = 99021L;
    private static final long USER_ID = 9902101L;
    private static final long ORDER_ID = 99021001L;
    private static final long NEXT_ORDER_ID = 99021002L;

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    public void cleanup() {
        UserHolder.removeUser();
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE id IN (?, ?) OR voucher_id = ?",
                ORDER_ID, NEXT_ORDER_ID, VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", VOUCHER_ID);
        stringRedisTemplate.delete("seckill:stock:" + VOUCHER_ID);
        stringRedisTemplate.delete("seckill:order:" + VOUCHER_ID);
        stringRedisTemplate.delete("seckill:hold:" + VOUCHER_ID);
        stringRedisTemplate.delete("seckill:voucher:" + VOUCHER_ID);
        stringRedisTemplate.delete("seckill:pending:" + ORDER_ID);
        stringRedisTemplate.delete("seckill:pending:" + NEXT_ORDER_ID);
        stringRedisTemplate.delete("seckill:restore:" + ORDER_ID);
        stringRedisTemplate.delete("seckill:restore:" + NEXT_ORDER_ID);
    }

    @Test
    public void queryDoesNotMovePendingRetryTime() {
        prepareVoucher(1);
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "1");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "beginTime", "1");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "endTime", "9999999999");
        Assertions.assertEquals(Long.valueOf(0), runSeckill(ORDER_ID));
        String before = (String) stringRedisTemplate.opsForHash().get("seckill:pending:" + ORDER_ID, "nextRetryAt");
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
        Result result = voucherOrderService.queryOrder(ORDER_ID);
        String after = (String) stringRedisTemplate.opsForHash().get("seckill:pending:" + ORDER_ID, "nextRetryAt");
        Assertions.assertEquals("0", before);
        Assertions.assertEquals(before, after);
        Assertions.assertEquals("处理中", ((com.hmdp.dto.VoucherOrderStatus) result.getData()).getState());
    }

    @Test
    public void sameMessageDecrementsStockOnce() {
        prepareVoucher(3);
        VoucherOrderMessage message = message(ORDER_ID);
        Assertions.assertEquals(VoucherOrderCreateStatus.CREATED, voucherOrderService.createVoucherOrder(message));
        Assertions.assertEquals(VoucherOrderCreateStatus.EXISTS, voucherOrderService.createVoucherOrder(message));
        Assertions.assertEquals(Integer.valueOf(2), currentStock());
    }

    @Test
    public void payAndCancelCannotBothWin() throws Exception {
        prepareVoucher(5);
        jdbcTemplate.update(
                "INSERT INTO tb_voucher_order (id, user_id, voucher_id, status) VALUES (?, ?, ?, 1)",
                ORDER_ID, USER_ID, VOUCHER_ID);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Result> pay = pool.submit(() -> {
                UserDTO user = new UserDTO();
                user.setId(USER_ID);
                UserHolder.saveUser(user);
                ready.countDown();
                start.await();
                try {
                    return voucherOrderService.payOrder(ORDER_ID);
                } finally {
                    UserHolder.removeUser();
                }
            });
            Future<?> cancel = pool.submit(() -> {
                ready.countDown();
                start.await();
                voucherOrderService.markOrderCancelled(ORDER_ID);
                return null;
            });
            ready.await();
            start.countDown();
            Result payResult = pay.get();
            cancel.get();
            VoucherOrder order = voucherOrderService.getById(ORDER_ID);
            Integer stock = currentStock();
            if (Boolean.TRUE.equals(payResult.getSuccess())) {
                Assertions.assertEquals(Integer.valueOf(2), order.getStatus());
                Assertions.assertEquals(Integer.valueOf(5), stock);
            } else {
                Assertions.assertEquals(Integer.valueOf(4), order.getStatus());
                Assertions.assertEquals(Integer.valueOf(6), stock);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void cancelThenRebuyKeepsTheNewHold() {
        prepareVoucher(2);
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "2");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "beginTime", "1");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "endTime", "9999999999");
        Assertions.assertEquals(Long.valueOf(0), runSeckill(ORDER_ID));
        Assertions.assertEquals(VoucherOrderCreateStatus.CREATED, voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        voucherOrderService.markOrderCancelled(ORDER_ID);
        voucherOrderService.restoreRedisIfCancelled(ORDER_ID);

        Assertions.assertEquals(Long.valueOf(0), runSeckill(NEXT_ORDER_ID));
        Long restored = runRestore(ORDER_ID);
        Assertions.assertEquals(Long.valueOf(0), restored);
        Assertions.assertEquals(String.valueOf(NEXT_ORDER_ID),
                stringRedisTemplate.opsForHash().get("seckill:hold:" + VOUCHER_ID, String.valueOf(USER_ID)));
        Assertions.assertEquals("1", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
    }

    private void prepareVoucher(int stock) {
        cleanup();
        jdbcTemplate.update(
                "INSERT INTO tb_voucher (id, shop_id, title, pay_value, actual_value, type, status) VALUES (?, 1, '测试券', 100, 100, 1, 1)",
                VOUCHER_ID);
        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) VALUES (?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))",
                VOUCHER_ID, stock);
    }

    private VoucherOrderMessage message(long orderId) {
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(USER_ID);
        message.setVoucherId(VOUCHER_ID);
        return message;
    }

    private Integer currentStock() {
        SeckillVoucher voucher = seckillVoucherService.getById(VOUCHER_ID);
        return voucher == null ? null : voucher.getStock();
    }

    private Long runSeckill(long orderId) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
        return stringRedisTemplate.execute(script, Arrays.asList(
                "seckill:stock:" + VOUCHER_ID,
                "seckill:order:" + VOUCHER_ID,
                "seckill:voucher:" + VOUCHER_ID,
                "seckill:hold:" + VOUCHER_ID,
                "seckill:pending:" + orderId
        ), String.valueOf(USER_ID), String.valueOf(orderId), String.valueOf(VOUCHER_ID), "0");
    }

    private Long runRestore(long orderId) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("seckill_restore.lua"));
        script.setResultType(Long.class);
        return stringRedisTemplate.execute(script, Arrays.asList(
                "seckill:stock:" + VOUCHER_ID,
                "seckill:hold:" + VOUCHER_ID,
                "seckill:pending:" + orderId
        ), String.valueOf(USER_ID), String.valueOf(orderId));
    }
}
