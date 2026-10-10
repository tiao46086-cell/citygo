package com.hmdp.listener;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_KEY;

/**
 * 发布确认超时后，预占还在 seckill:pending 里。这里补发同一条订单消息。
 * 刚写进去的 key 先不动，免得和还在等确认的请求叠在一起。
 */
@Slf4j
@Component
public class SeckillPendingScheduler {

    /** 小于这个秒数的预占留给正在等确认的请求 */
    private static final long MIN_IDLE_SECONDS = 10L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelay = 5000)
    public void retryPending() {
        ScanOptions options = ScanOptions.scanOptions().match(SECKILL_PENDING_KEY + "*").count(50).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                retryOne(cursor.next());
            }
        } catch (RuntimeException e) {
            log.error("扫描待确认秒杀订单失败", e);
        }
    }

    private void retryOne(String key) {
        Duration idle = idleTime(key);
        if (idle == null || idle.getSeconds() < MIN_IDLE_SECONDS) {
            return;
        }
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return;
        }
        String orderIdText = key.substring(SECKILL_PENDING_KEY.length());
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            log.error("待确认秒杀订单格式不对, key={}, value={}", key, value);
            return;
        }
        Long orderId;
        Long userId;
        Long voucherId;
        try {
            orderId = Long.valueOf(orderIdText);
            userId = Long.valueOf(parts[0]);
            voucherId = Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            log.error("待确认秒杀订单无法解析, key={}, value={}", key, value);
            return;
        }
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order != null) {
            stringRedisTemplate.delete(key);
            return;
        }
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setVoucherId(voucherId);
        try {
            // 发不出去就留着预占，下一轮再试。不在这里回补
            rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_ORDER_QUEUE, message);
            // 读一次，把空闲时间清零，10 秒内不会再补发同一单
            stringRedisTemplate.opsForValue().get(key);
            log.info("已补发秒杀订单, orderId={}", orderId);
        } catch (RuntimeException e) {
            log.error("补发秒杀订单失败, orderId={}", orderId, e);
        }
    }

    private Duration idleTime(String key) {
        return stringRedisTemplate.execute((RedisCallback<Duration>) connection -> idle(connection, key));
    }

    private Duration idle(RedisConnection connection, String key) {
        return connection.keyCommands().idletime(key.getBytes(StandardCharsets.UTF_8));
    }
}
