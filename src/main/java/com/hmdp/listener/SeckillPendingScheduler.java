package com.hmdp.listener;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.ConfirmedSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.PENDING_NEXT_FIELD;
import static com.hmdp.utils.RedisConstants.PENDING_STATE_FIELD;
import static com.hmdp.utils.RedisConstants.PENDING_STATE_STOPPED;
import static com.hmdp.utils.RedisConstants.PENDING_STATE_WAITING;
import static com.hmdp.utils.RedisConstants.PENDING_USER_FIELD;
import static com.hmdp.utils.RedisConstants.PENDING_VOUCHER_FIELD;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_DELAY_MILLIS;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_RESTORE_KEY;

/**
 * 发布确认超时后，预占还在 seckill:pending 里。到了 nextRetryAt 就补发同一单。
 * 查询订单只读这个 Hash，不会把时间清掉。state 为 stopped 时退回预占，不再补发下单消息。
 * 取消已经写进数据库、Redis 还占着资格时，按 seckill:restore 再回补。
 * 延迟取消消息如果没到，这里再扫已超时的未支付订单。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.task.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SeckillPendingScheduler {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ConfirmedSender confirmedSender;

    @Scheduled(fixedDelay = 5000)
    public void recover() {
        retryPending();
        retryRestores();
        cancelExpiredUnpaid();
    }

    private void retryPending() {
        ScanOptions options = ScanOptions.scanOptions().match(SECKILL_PENDING_KEY + "*").count(50).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                retryOnePending(cursor.next());
            }
        } catch (RuntimeException e) {
            log.error("扫描待确认秒杀订单失败", e);
        }
    }

    private void retryOnePending(String key) {
        DataType type = stringRedisTemplate.type(key);
        if (type == DataType.STRING) {
            retryLegacyPending(key);
            return;
        }
        if (type != DataType.HASH) {
            return;
        }
        Map<Object, Object> pending = stringRedisTemplate.opsForHash().entries(key);
        Long pendingId = parseLong(key.substring(SECKILL_PENDING_KEY.length()));
        VoucherOrder terminal = pendingId == null ? null : voucherOrderService.getById(pendingId);
        if (terminal != null && Integer.valueOf(7).equals(terminal.getStatus())) {
            // 即使 state 仍是 retrying，也必须恢复失败订单的 Redis 预占。
            voucherOrderService.abandonFailedCreate(pendingId, terminal.getUserId(), terminal.getVoucherId());
            return;
        }
        String state = text(pending.get(PENDING_STATE_FIELD));
        if (PENDING_STATE_STOPPED.equals(state)) {
            compensateStopped(key, pending);
            return;
        }
        if (!PENDING_STATE_WAITING.equals(state)) {
            return;
        }
        if (!due(text(pending.get(PENDING_NEXT_FIELD)))) {
            return;
        }
        String orderIdText = key.substring(SECKILL_PENDING_KEY.length());
        Long orderId = parseLong(orderIdText);
        Long userId = parseLong(text(pending.get(PENDING_USER_FIELD)));
        Long voucherId = parseLong(text(pending.get(PENDING_VOUCHER_FIELD)));
        if (orderId == null || userId == null || voucherId == null) {
            log.error("待确认秒杀订单无法解析, key={}", key);
            return;
        }
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order != null) {
            if (Integer.valueOf(7).equals(order.getStatus())) {
                voucherOrderService.abandonFailedCreate(orderId, order.getUserId(), order.getVoucherId());
                return;
            }
            stringRedisTemplate.delete(key);
            return;
        }
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setVoucherId(voucherId);
        ConfirmedSender.Outcome outcome = confirmedSender.send(RabbitMqConfig.SECKILL_ORDER_QUEUE, message, null);
        if (outcome == ConfirmedSender.Outcome.REJECTED) {
            log.error("补发秒杀订单被拒绝, orderId={}", orderId);
            return;
        }
        // 确认成功，或确认超时但消息可能已经在队列里。把下次时间推后，查询不会改这个字段
        voucherOrderService.postponePendingRetry(orderId);
        log.info("已补发秒杀订单, orderId={}, outcome={}", orderId, outcome);
    }

    /** 以前次数用完只把状态改成 stopped。这里补上退库存，避免预占一直占着 */
    private void compensateStopped(String key, Map<Object, Object> pending) {
        Long orderId = parseLong(key.substring(SECKILL_PENDING_KEY.length()));
        Long userId = parseLong(text(pending.get(PENDING_USER_FIELD)));
        Long voucherId = parseLong(text(pending.get(PENDING_VOUCHER_FIELD)));
        if (orderId == null || userId == null || voucherId == null) {
            log.error("失败的秒杀预占无法解析, key={}", key);
            return;
        }
        try {
            if (!voucherOrderService.abandonFailedCreate(orderId, userId, voucherId)) {
                log.error("秒杀失败补偿没有完成, orderId={}", orderId);
            }
        } catch (RuntimeException e) {
            log.error("秒杀失败补偿出错, orderId={}", orderId, e);
        }
    }

    /** 改成 Hash 之前的预占是 userId:voucherId。读完改写成 Hash，避免再靠空闲时间计时 */
    private void retryLegacyPending(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return;
        }
        String[] parts = value.split(":", 2);
        String orderIdText = key.substring(SECKILL_PENDING_KEY.length());
        Long orderId = parseLong(orderIdText);
        Long userId = parts.length == 2 ? parseLong(parts[0]) : null;
        Long voucherId = parts.length == 2 ? parseLong(parts[1]) : null;
        if (orderId == null || userId == null || voucherId == null) {
            log.error("待确认秒杀订单格式不对, key={}, value={}", key, value);
            return;
        }
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order != null) {
            if (Integer.valueOf(7).equals(order.getStatus())) {
                voucherOrderService.abandonFailedCreate(orderId, order.getUserId(), order.getVoucherId());
                return;
            }
            stringRedisTemplate.delete(key);
            return;
        }
        stringRedisTemplate.delete(key);
        stringRedisTemplate.opsForHash().put(key, PENDING_USER_FIELD, userId.toString());
        stringRedisTemplate.opsForHash().put(key, PENDING_VOUCHER_FIELD, voucherId.toString());
        stringRedisTemplate.opsForHash().put(key, PENDING_STATE_FIELD, PENDING_STATE_WAITING);
        stringRedisTemplate.opsForHash().put(key, PENDING_NEXT_FIELD, "0");
        retryOnePending(key);
    }

    private void retryRestores() {
        ScanOptions options = ScanOptions.scanOptions().match(SECKILL_RESTORE_KEY + "*").count(50).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                retryOneRestore(cursor.next());
            }
        } catch (RuntimeException e) {
            log.error("扫描秒杀回补任务失败", e);
        }
    }

    private void retryOneRestore(String key) {
        Object next = stringRedisTemplate.opsForHash().get(key, PENDING_NEXT_FIELD);
        if (!due(text(next))) {
            return;
        }
        Long orderId = parseLong(key.substring(SECKILL_RESTORE_KEY.length()));
        if (orderId == null) {
            log.error("秒杀回补任务无法解析, key={}", key);
            return;
        }
        try {
            voucherOrderService.restoreRedisIfCancelled(orderId);
        } catch (RuntimeException e) {
            log.error("回补秒杀资格失败, orderId={}", orderId, e);
            stringRedisTemplate.opsForHash().put(key, PENDING_NEXT_FIELD,
                    String.valueOf(System.currentTimeMillis() + SECKILL_PENDING_DELAY_MILLIS));
        }
    }

    private void cancelExpiredUnpaid() {
        try {
            for (Long orderId : voucherOrderService.findExpiredUnpaidIds(20)) {
                try {
                    voucherOrderService.markOrderCancelled(orderId);
                    voucherOrderService.restoreRedisIfCancelled(orderId);
                } catch (RuntimeException e) {
                    log.error("取消超时未支付订单失败, orderId={}", orderId, e);
                }
            }
        } catch (RuntimeException e) {
            log.error("扫描超时未支付订单失败", e);
        }
    }

    private boolean due(String nextRetryAt) {
        if (nextRetryAt == null || nextRetryAt.isEmpty()) {
            return true;
        }
        Long at = parseLong(nextRetryAt);
        return at != null && System.currentTimeMillis() >= at;
    }

    private Long parseLong(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }
}
