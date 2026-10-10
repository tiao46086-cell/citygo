package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderCancelMessage;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.dto.VoucherOrderStatus;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hmdp.utils.RedisConstants.SECKILL_HOLD_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_LIMIT_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_VOUCHER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> RESTORE_SCRIPT;
    private static final DefaultRedisScript<Long> LIMIT_SCRIPT;
    /** 同一个用户 1 秒内最多打这么多次秒杀接口，多出来的请求不进入 Lua */
    private static final int SECKILL_LIMIT_COUNT = 5;
    private static final int SECKILL_LIMIT_SECONDS = 1;
    /** 等待 broker 确认的时间。超时只能说明确认没回来，消息仍可能已经进队列 */
    private static final long CONFIRM_WAIT_SECONDS = 3L;
    /** 与 tb_voucher_order.status 的约定一致：1 未支付，2 已支付，4 已取消 */
    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int ORDER_STATUS_PAID = 2;
    private static final int ORDER_STATUS_CANCELLED = 4;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
        RESTORE_SCRIPT = new DefaultRedisScript<>();
        RESTORE_SCRIPT.setLocation(new ClassPathResource("seckill_restore.lua"));
        RESTORE_SCRIPT.setResultType(Long.class);
        LIMIT_SCRIPT = new DefaultRedisScript<>();
        LIMIT_SCRIPT.setLocation(new ClassPathResource("seckill_limit.lua"));
        LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Value("${citygo.seckill.unpaid-timeout:15m}")
    private Duration unpaidTimeout;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long allowed = stringRedisTemplate.execute(
                LIMIT_SCRIPT,
                Collections.singletonList(SECKILL_LIMIT_KEY + userId),
                String.valueOf(SECKILL_LIMIT_COUNT),
                String.valueOf(SECKILL_LIMIT_SECONDS)
        );
        if (allowed == null || allowed == 0L) {
            return Result.fail("操作太频繁，请稍后再试");
        }
        // 先有订单号，再预占。Redis 里的资格才能对上这一单
        Long orderId = redisIdWorker.nextId("order");
        List<String> keys = Arrays.asList(
                SECKILL_STOCK_KEY + voucherId,
                SECKILL_ORDER_KEY + voucherId,
                SECKILL_VOUCHER_KEY + voucherId,
                SECKILL_HOLD_KEY + voucherId,
                SECKILL_PENDING_KEY + orderId
        );
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                keys,
                userId.toString(),
                orderId.toString(),
                voucherId.toString()
        );
        if (result == null) {
            return Result.fail("秒杀失败");
        }
        int code = result.intValue();
        if (code != 0) {
            return Result.fail(seckillFailMessage(code));
        }
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setVoucherId(voucherId);
        CorrelationData correlationData = new CorrelationData(orderId.toString());
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_ORDER_QUEUE, message, correlationData);
        } catch (AmqpConnectException | AmqpAuthenticationException e) {
            // 连接或账号在发出去之前就失败了，broker 里没有这条消息
            log.error("秒杀消息没有发出去, orderId={}", orderId, e);
            restoreSeckill(voucherId, userId, orderId);
            return Result.fail("下单失败，请重试");
        } catch (RuntimeException e) {
            // 已经调用了发送，但不能确定进没进队列。预占留着，查询接口会看到处理中
            log.error("秒杀消息发送结果不确定, orderId={}", orderId, e);
            return Result.ok(orderId);
        }
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_WAIT_SECONDS, TimeUnit.SECONDS);
            boolean accepted = confirm != null && confirm.isAck() && correlationData.getReturned() == null;
            if (accepted) {
                return Result.ok(orderId);
            }
            log.error("秒杀消息被拒绝, orderId={}, confirm={}", orderId, confirm);
        } catch (TimeoutException e) {
            log.error("等待发布确认超时, orderId={}", orderId);
            return Result.ok(orderId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待发布确认被中断, orderId={}", orderId);
            return Result.ok(orderId);
        } catch (ExecutionException e) {
            log.error("发布确认失败, orderId={}", orderId, e);
            return Result.ok(orderId);
        }
        restoreSeckill(voucherId, userId, orderId);
        return Result.fail("下单失败，请重试");
    }

    @Override
    public Result queryOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order != null) {
            if (!userId.equals(order.getUserId())) {
                return Result.fail("订单不存在");
            }
            VoucherOrderStatus view = new VoucherOrderStatus();
            view.setOrderId(orderId);
            view.setStatus(order.getStatus());
            view.setState(orderState(order));
            return Result.ok(view);
        }
        String pending = stringRedisTemplate.opsForValue().get(SECKILL_PENDING_KEY + orderId);
        if (pending != null && pending.startsWith(userId + ":")) {
            VoucherOrderStatus view = new VoucherOrderStatus();
            view.setOrderId(orderId);
            view.setState("处理中");
            return Result.ok(view);
        }
        return Result.fail("订单不存在");
    }

    @Override
    @Transactional
    public VoucherOrderCreateStatus createVoucherOrder(VoucherOrderMessage message) {
        if (message == null || message.getOrderId() == null
                || message.getUserId() == null || message.getVoucherId() == null) {
            log.error("秒杀订单消息不完整: {}", message);
            return VoucherOrderCreateStatus.INVALID;
        }
        if (getById(message.getOrderId()) != null) {
            log.info("秒杀订单已存在, orderId={}", message.getOrderId());
            stringRedisTemplate.delete(SECKILL_PENDING_KEY + message.getOrderId());
            return VoucherOrderCreateStatus.EXISTS;
        }
        // 先插入订单。主键冲突说明另一条相同消息已经在写，这次不能再扣库存
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());
        try {
            save(voucherOrder);
        } catch (DuplicateKeyException e) {
            log.info("秒杀订单已存在, orderId={}", message.getOrderId());
            stringRedisTemplate.delete(SECKILL_PENDING_KEY + message.getOrderId());
            return VoucherOrderCreateStatus.EXISTS;
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", message.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("秒杀订单入库失败，数据库库存不足，回滚 Redis, orderId={}", message.getOrderId());
            removeById(message.getOrderId());
            restoreSeckill(message.getVoucherId(), message.getUserId(), message.getOrderId());
            return VoucherOrderCreateStatus.STOCK_FAILED;
        }
        scheduleUnpaidCancel(message.getOrderId());
        stringRedisTemplate.delete(SECKILL_PENDING_KEY + message.getOrderId());
        return VoucherOrderCreateStatus.CREATED;
    }

    @Override
    public Result payOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        long seconds = unpaidTimeout.getSeconds();
        // 期限用数据库的 NOW()，避免 JVM 时区和 JDBC 的 UTC 差 8 小时
        boolean paid = update()
                .set("status", ORDER_STATUS_PAID)
                .setSql("pay_time = NOW()")
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", ORDER_STATUS_UNPAID)
                .apply("create_time > DATE_SUB(NOW(), INTERVAL {0} SECOND)", seconds)
                .update();
        if (paid) {
            return Result.ok();
        }
        VoucherOrder order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_PAID) {
            return Result.fail("订单已支付");
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_CANCELLED) {
            return Result.fail("订单已取消");
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_UNPAID && unpaidExpired(orderId)) {
            return Result.fail("订单已超时");
        }
        return Result.fail("订单无法支付");
    }

    @Override
    @Transactional
    public void markOrderCancelled(Long orderId) {
        if (orderId == null) {
            log.error("取消消息没有订单号");
            return;
        }
        boolean cancelled = update()
                .set("status", ORDER_STATUS_CANCELLED)
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
        if (!cancelled) {
            log.info("订单无需取消, orderId={}", orderId);
            return;
        }
        VoucherOrder order = getById(orderId);
        boolean stockRestored = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!stockRestored) {
            throw new IllegalStateException("秒杀库存回补失败, orderId=" + orderId);
        }
    }

    @Override
    public void restoreRedisIfCancelled(Long orderId) {
        if (orderId == null) {
            return;
        }
        VoucherOrder order = getById(orderId);
        if (order == null || order.getStatus() == null || order.getStatus() != ORDER_STATUS_CANCELLED) {
            return;
        }
        restoreSeckill(order.getVoucherId(), order.getUserId(), orderId);
    }

    private void scheduleUnpaidCancel(Long orderId) {
        VoucherOrderCancelMessage cancelMessage = new VoucherOrderCancelMessage();
        cancelMessage.setOrderId(orderId);
        CorrelationData correlationData = new CorrelationData("cancel-" + orderId);
        rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_ORDER_DELAY_QUEUE, cancelMessage, correlationData);
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_WAIT_SECONDS, TimeUnit.SECONDS);
            boolean accepted = confirm != null && confirm.isAck() && correlationData.getReturned() == null;
            if (!accepted) {
                throw new IllegalStateException("取消消息被拒绝, orderId=" + orderId + ", confirm=" + confirm);
            }
        } catch (TimeoutException e) {
            log.error("等待取消消息确认超时, orderId={}", orderId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待取消消息确认被中断, orderId=" + orderId, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("取消消息确认失败, orderId=" + orderId, e);
        }
    }

    private void restoreSeckill(Long voucherId, Long userId, Long orderId) {
        Long restored = stringRedisTemplate.execute(
                RESTORE_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_HOLD_KEY + voucherId,
                        SECKILL_PENDING_KEY + orderId
                ),
                userId.toString(),
                orderId.toString()
        );
        log.info("回滚秒杀资格, voucherId={}, userId={}, orderId={}, restored={}", voucherId, userId, orderId, restored);
    }

    private boolean unpaidExpired(Long orderId) {
        Long rows = query()
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .apply("create_time <= DATE_SUB(NOW(), INTERVAL {0} SECOND)", unpaidTimeout.getSeconds())
                .count();
        return rows != null && rows > 0;
    }

    private String orderState(VoucherOrder order) {
        Integer status = order.getStatus();
        if (status != null && status == ORDER_STATUS_PAID) {
            return "已支付";
        }
        if (status != null && status == ORDER_STATUS_CANCELLED) {
            return "已取消";
        }
        if (status != null && status == ORDER_STATUS_UNPAID && unpaidExpired(order.getId())) {
            return "已超时";
        }
        if (status != null && status == ORDER_STATUS_UNPAID) {
            return "未支付";
        }
        return "未知";
    }

    private String seckillFailMessage(int code) {
        if (code == 1) {
            return "库存不足";
        }
        if (code == 2) {
            return "不能重复下单";
        }
        if (code == 3) {
            return "秒杀尚未开始";
        }
        if (code == 4) {
            return "秒杀已经结束";
        }
        if (code == 5) {
            return "秒杀券不存在";
        }
        return "秒杀失败";
    }
}
