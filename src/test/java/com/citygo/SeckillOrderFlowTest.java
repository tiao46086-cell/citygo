package com.citygo;

import com.citygo.dto.Result;
import com.citygo.dto.UserDTO;
import com.citygo.dto.VoucherOrderCreateStatus;
import com.citygo.dto.VoucherOrderMessage;
import com.citygo.dto.VoucherOrderStatus;
import com.citygo.entity.SeckillVoucher;
import com.citygo.entity.VoucherOrder;
import com.citygo.service.ISeckillVoucherService;
import com.citygo.service.IVoucherOrderService;
import com.citygo.utils.UserHolder;
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
import java.util.List;
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
    private static final long NORMAL_VOUCHER_ID = 99022L;
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
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE id IN (?, ?) OR voucher_id IN (?, ?)",
                ORDER_ID, NEXT_ORDER_ID, VOUCHER_ID, NORMAL_VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_voucher WHERE id IN (?, ?)", VOUCHER_ID, NORMAL_VOUCHER_ID);
        stringRedisTemplate.delete("seckill:stock:" + VOUCHER_ID);
        stringRedisTemplate.delete("seckill:order:" + VOUCHER_ID);
        stringRedisTemplate.delete("seckill:hold:" + VOUCHER_ID);
        stringRedisTemplate.delete("seckill:voucher:" + VOUCHER_ID);
        stringRedisTemplate.delete("seckill:pending:" + ORDER_ID);
        stringRedisTemplate.delete("seckill:pending:" + NEXT_ORDER_ID);
        stringRedisTemplate.delete("seckill:restore:" + ORDER_ID);
        stringRedisTemplate.delete("seckill:restore:" + NEXT_ORDER_ID);
        stringRedisTemplate.delete("seckill:failed:" + ORDER_ID);
        stringRedisTemplate.delete("seckill:failed:" + NEXT_ORDER_ID);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ? OR phone = ?", USER_ID, "12000009921");
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
        Assertions.assertEquals("处理中", ((com.citygo.dto.VoucherOrderStatus) result.getData()).getState());
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
        Integer stockAfterCancel = currentStock();
        voucherOrderService.markOrderCancelled(ORDER_ID);
        Assertions.assertEquals(stockAfterCancel, currentStock());
        voucherOrderService.restoreRedisIfCancelled(ORDER_ID);

        Assertions.assertEquals(Long.valueOf(0), runSeckill(NEXT_ORDER_ID));
        Long restored = runRestore(ORDER_ID);
        Assertions.assertEquals(Long.valueOf(0), restored);
        Assertions.assertEquals(String.valueOf(NEXT_ORDER_ID),
                stringRedisTemplate.opsForHash().get("seckill:hold:" + VOUCHER_ID, String.valueOf(USER_ID)));
        Assertions.assertEquals("1", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
    }

    @Test
    public void normalOrderDeductsAndCancelRestoresOnlyThatStock() {
        jdbcTemplate.update(
                "INSERT INTO tb_voucher (id, shop_id, title, pay_value, actual_value, type, status, stock) VALUES (?, 1, 'normal-test', 100, 100, 0, 1, 2)",
                NORMAL_VOUCHER_ID);
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
        Result first = voucherOrderService.placeOrder(NORMAL_VOUCHER_ID);
        Result second = voucherOrderService.placeOrder(NORMAL_VOUCHER_ID);
        Result third = voucherOrderService.placeOrder(NORMAL_VOUCHER_ID);
        Assertions.assertEquals(Boolean.TRUE, first.getSuccess());
        Assertions.assertEquals(Boolean.TRUE, second.getSuccess());
        Assertions.assertEquals(Boolean.FALSE, third.getSuccess());
        Assertions.assertEquals(Integer.valueOf(0), normalStock());
        Long orderId = Long.valueOf(String.valueOf(first.getData()));
        voucherOrderService.markOrderCancelled(orderId);
        Assertions.assertEquals(Integer.valueOf(1), normalStock());
        voucherOrderService.markOrderCancelled(orderId);
        Assertions.assertEquals(Integer.valueOf(1), normalStock());
        Assertions.assertNull(stringRedisTemplate.opsForHash().get("seckill:restore:" + orderId, "userId"));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, NORMAL_VOUCHER_ID).intValue());
    }

    @Test
    public void scannerCancelsExpiredNormalOrderOnlyOnce() {
        jdbcTemplate.update(
                "INSERT INTO tb_voucher (id, shop_id, title, pay_value, actual_value, type, status, stock) VALUES (?, 1, 'normal-test', 100, 100, 0, 1, 1)",
                NORMAL_VOUCHER_ID);
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
        Result placed = voucherOrderService.placeOrder(NORMAL_VOUCHER_ID);
        Assertions.assertEquals(Boolean.TRUE, placed.getSuccess());
        Long orderId = Long.valueOf(String.valueOf(placed.getData()));
        jdbcTemplate.update(
                "UPDATE tb_voucher_order SET create_time = DATE_SUB(NOW(), INTERVAL 20 MINUTE) WHERE id = ?",
                orderId);
        Integer matched = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE id = ? AND status = 1 AND create_time <= DATE_SUB(NOW(), INTERVAL 900 SECOND)",
                Integer.class, orderId);
        Assertions.assertEquals(Integer.valueOf(1), matched);
        Integer expired = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE status = 1 AND create_time <= DATE_SUB(NOW(), INTERVAL 900 SECOND)",
                Integer.class);
        if (expired != null && expired <= 50) {
            Assertions.assertTrue(voucherOrderService.findExpiredUnpaidIds(50).contains(orderId));
        }
        voucherOrderService.markOrderCancelled(orderId);
        Assertions.assertEquals(Integer.valueOf(1), normalStock());
        voucherOrderService.markOrderCancelled(orderId);
        Assertions.assertEquals(Integer.valueOf(1), normalStock());
        Assertions.assertEquals(Integer.valueOf(4), voucherOrderService.getById(orderId).getStatus());
    }

    @Test
    public void failedCreateReleasesHoldAndReportsFailure() {
        prepareVoucher(3);
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "2");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "beginTime", "1");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "endTime", "9999999999");
        Assertions.assertEquals(Long.valueOf(0), runSeckill(ORDER_ID));
        Assertions.assertTrue(voucherOrderService.abandonFailedCreate(ORDER_ID, USER_ID, VOUCHER_ID));
        Assertions.assertEquals("2", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
        Assertions.assertNull(stringRedisTemplate.opsForHash().get("seckill:hold:" + VOUCHER_ID, String.valueOf(USER_ID)));
        Assertions.assertEquals(Boolean.FALSE, stringRedisTemplate.hasKey("seckill:pending:" + ORDER_ID));
        Assertions.assertEquals(String.valueOf(USER_ID), stringRedisTemplate.opsForValue().get("seckill:failed:" + ORDER_ID));
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
        Result query = voucherOrderService.queryOrder(ORDER_ID);
        Assertions.assertEquals("下单失败", ((VoucherOrderStatus) query.getData()).getState());
        Assertions.assertEquals(Integer.valueOf(3), currentStock());
        Assertions.assertTrue(voucherOrderService.abandonFailedCreate(ORDER_ID, USER_ID, VOUCHER_ID));
        Assertions.assertEquals("2", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
        stringRedisTemplate.delete("seckill:failed:" + ORDER_ID);
        Result again = voucherOrderService.queryOrder(ORDER_ID);
        Assertions.assertEquals("下单失败", ((VoucherOrderStatus) again.getData()).getState());
        Assertions.assertEquals(VoucherOrderCreateStatus.TERMINATED, voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        Assertions.assertEquals(Integer.valueOf(3), currentStock());
        Assertions.assertEquals(Integer.valueOf(7), voucherOrderService.getById(ORDER_ID).getStatus());
        Assertions.assertEquals("2", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
        Assertions.assertTrue(((List<?>) voucherOrderService.pageMine(1).getData()).isEmpty());
        Assertions.assertEquals("订单不存在", voucherOrderService.payOrder(ORDER_ID).getErrorMsg());
    }

    @Test
    public void createAndTerminateCannotBothSucceed() throws Exception {
        prepareVoucher(3);
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "beginTime", "1");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "endTime", "9999999999");
        for (int i = 0; i < 8; i++) {
            jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE id = ?", ORDER_ID);
            jdbcTemplate.update("UPDATE tb_seckill_voucher SET stock = 3 WHERE voucher_id = ?", VOUCHER_ID);
            stringRedisTemplate.delete("seckill:stock:" + VOUCHER_ID);
            stringRedisTemplate.delete("seckill:hold:" + VOUCHER_ID);
            stringRedisTemplate.delete("seckill:pending:" + ORDER_ID);
            stringRedisTemplate.delete("seckill:failed:" + ORDER_ID);
            stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "2");
            Assertions.assertEquals(Long.valueOf(0), runSeckill(ORDER_ID));
            String heldStock = stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            VoucherOrderCreateStatus createStatus;
            try {
                Future<VoucherOrderCreateStatus> created = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return voucherOrderService.createVoucherOrder(message(ORDER_ID));
                });
                Future<Boolean> terminated = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return voucherOrderService.abandonFailedCreate(ORDER_ID, USER_ID, VOUCHER_ID);
                });
                ready.await();
                start.countDown();
                createStatus = created.get();
                Assertions.assertEquals(Boolean.TRUE, terminated.get());
            } finally {
                pool.shutdownNow();
            }
            VoucherOrder order = voucherOrderService.getById(ORDER_ID);
            Assertions.assertNotNull(order);
            if (Integer.valueOf(1).equals(order.getStatus())) {
                Assertions.assertEquals(VoucherOrderCreateStatus.CREATED, createStatus);
                Assertions.assertEquals(Integer.valueOf(2), currentStock());
                Assertions.assertEquals(heldStock, stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
            } else {
                Assertions.assertEquals(Integer.valueOf(7), order.getStatus());
                Assertions.assertEquals(VoucherOrderCreateStatus.TERMINATED, createStatus);
                Assertions.assertEquals(Integer.valueOf(3), currentStock());
                Assertions.assertEquals("2", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
            }
            Integer stock = currentStock();
            Integer status = order.getStatus();
            voucherOrderService.createVoucherOrder(message(ORDER_ID));
            Assertions.assertTrue(voucherOrderService.abandonFailedCreate(ORDER_ID, USER_ID, VOUCHER_ID));
            Assertions.assertEquals(stock, currentStock());
            Assertions.assertEquals(status, voucherOrderService.getById(ORDER_ID).getStatus());
        }
    }

    @Test
    public void failedCreateDoesNotRestoreStockWhenOrderExists() {
        prepareVoucher(3);
        Assertions.assertEquals(VoucherOrderCreateStatus.CREATED, voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "0");
        stringRedisTemplate.opsForHash().put("seckill:hold:" + VOUCHER_ID, String.valueOf(USER_ID), String.valueOf(ORDER_ID));
        stringRedisTemplate.opsForHash().put("seckill:pending:" + ORDER_ID, "userId", String.valueOf(USER_ID));
        stringRedisTemplate.opsForHash().put("seckill:pending:" + ORDER_ID, "voucherId", String.valueOf(VOUCHER_ID));
        stringRedisTemplate.opsForHash().put("seckill:pending:" + ORDER_ID, "state", "stopped");
        Assertions.assertTrue(voucherOrderService.abandonFailedCreate(ORDER_ID, USER_ID, VOUCHER_ID));
        Assertions.assertEquals(Integer.valueOf(2), currentStock());
        Assertions.assertEquals("0", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
        Assertions.assertEquals(String.valueOf(ORDER_ID),
                stringRedisTemplate.opsForHash().get("seckill:hold:" + VOUCHER_ID, String.valueOf(USER_ID)));
        Assertions.assertNull(stringRedisTemplate.opsForValue().get("seckill:failed:" + ORDER_ID));
        Assertions.assertEquals(Boolean.FALSE, stringRedisTemplate.hasKey("seckill:pending:" + ORDER_ID));
    }

    @Test
    public void recycleKeepsOrderSoRedeliveryDoesNotDeductAgain() {
        prepareVoucher(3);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ? OR phone = ?", USER_ID, "12000009921");
        jdbcTemplate.update(
                "INSERT INTO tb_user (id, phone, nick_name) VALUES (?, '12000009921', 'flow')", USER_ID);
        Assertions.assertEquals(VoucherOrderCreateStatus.CREATED, voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        Assertions.assertEquals(Integer.valueOf(2), currentStock());
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
        Assertions.assertTrue(voucherOrderService.releaseDemoOrders(USER_ID));
        Assertions.assertEquals(Integer.valueOf(3), currentStock());
        Assertions.assertEquals(Integer.valueOf(4), voucherOrderService.getById(ORDER_ID).getStatus());
        Assertions.assertEquals(VoucherOrderCreateStatus.EXISTS, voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        Assertions.assertEquals(Integer.valueOf(3), currentStock());
        Assertions.assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE id = ?", Integer.class, ORDER_ID));
        Result page = voucherOrderService.pageMine(1);
        Assertions.assertTrue(((List<?>) page.getData()).isEmpty());
        Assertions.assertEquals("订单不存在", voucherOrderService.queryOrder(ORDER_ID).getErrorMsg());
        Assertions.assertEquals("订单不存在", voucherOrderService.payOrder(ORDER_ID).getErrorMsg());
    }

    @Test
    public void seckillOrderRejectsNormalPlace() {
        prepareVoucher(3);
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
        Result result = voucherOrderService.placeOrder(VOUCHER_ID);
        Assertions.assertEquals(Boolean.FALSE, result.getSuccess());
        Assertions.assertEquals(Integer.valueOf(3), currentStock());
    }

    @Test
    public void stockFailureIsDurableAndLateMessageCannotCreateOrder() {
        prepareVoucher(0);
        prepareReservation();
        Assertions.assertEquals(VoucherOrderCreateStatus.STOCK_FAILED,
                voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        Assertions.assertEquals(Integer.valueOf(7), voucherOrderService.getById(ORDER_ID).getStatus());
        Assertions.assertEquals("1", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
        jdbcTemplate.update("UPDATE tb_seckill_voucher SET stock=1 WHERE voucher_id=?", VOUCHER_ID);
        Assertions.assertEquals(VoucherOrderCreateStatus.TERMINATED,
                voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        Assertions.assertEquals(Integer.valueOf(1), currentStock());
    }

    @Test
    public void failedRestoreSurvivesRedeliveryAndRetryingPendingScan() {
        prepareVoucher(0);
        prepareReservation();
        // INCR 报错，模拟回补失败；脚本必须保留 hold 和 pending。
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "invalid-stock");
        Assertions.assertEquals(VoucherOrderCreateStatus.STOCK_FAILED,
                voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        Assertions.assertEquals(VoucherOrderCreateStatus.TERMINATED,
                voucherOrderService.createVoucherOrder(message(ORDER_ID)));
        Assertions.assertEquals(String.valueOf(ORDER_ID), stringRedisTemplate.opsForHash()
                .get("seckill:hold:" + VOUCHER_ID, String.valueOf(USER_ID)));
        Assertions.assertTrue(stringRedisTemplate.hasKey("seckill:pending:" + ORDER_ID));
        stringRedisTemplate.opsForHash().put("seckill:pending:" + ORDER_ID, "state", "retrying");
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "0");
        com.citygo.listener.SeckillPendingScheduler scheduler = new com.citygo.listener.SeckillPendingScheduler();
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "stringRedisTemplate", stringRedisTemplate);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "voucherOrderService", voucherOrderService);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(scheduler, "retryOnePending", "seckill:pending:" + ORDER_ID);
        Assertions.assertFalse(stringRedisTemplate.hasKey("seckill:pending:" + ORDER_ID));
        Assertions.assertEquals("1", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
        Assertions.assertTrue(voucherOrderService.abandonFailedCreate(ORDER_ID, USER_ID, VOUCHER_ID));
        Assertions.assertEquals("1", stringRedisTemplate.opsForValue().get("seckill:stock:" + VOUCHER_ID));
    }

    @Test
    public void restoreMarkerIsNotRemovedBeforeCancelCommit() {
        prepareVoucher(1);
        jdbcTemplate.update("INSERT INTO tb_voucher_order (id,user_id,voucher_id,status) VALUES (?,?,?,1)",
                ORDER_ID, USER_ID, VOUCHER_ID);
        stringRedisTemplate.opsForHash().put("seckill:restore:" + ORDER_ID, "nextRetryAt", "0");
        voucherOrderService.restoreRedisIfCancelled(ORDER_ID);
        Assertions.assertTrue(stringRedisTemplate.hasKey("seckill:restore:" + ORDER_ID));
        voucherOrderService.markOrderCancelled(ORDER_ID);
        voucherOrderService.restoreRedisIfCancelled(ORDER_ID);
        Assertions.assertFalse(stringRedisTemplate.hasKey("seckill:restore:" + ORDER_ID));
    }

    private void prepareReservation() {
        stringRedisTemplate.opsForValue().set("seckill:stock:" + VOUCHER_ID, "1");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "beginTime", "1");
        stringRedisTemplate.opsForHash().put("seckill:voucher:" + VOUCHER_ID, "endTime", "9999999999");
        Assertions.assertEquals(Long.valueOf(0), runSeckill(ORDER_ID));
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

    private Integer normalStock() {
        return jdbcTemplate.queryForObject("SELECT stock FROM tb_voucher WHERE id = ?", Integer.class, NORMAL_VOUCHER_ID);
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
