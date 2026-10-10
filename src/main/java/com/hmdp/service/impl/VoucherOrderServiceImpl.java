package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderCancelMessage;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hmdp.utils.RedisConstants.SECKILL_LIMIT_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
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

    /**
     * 脚本内容不变，类加载时读一次 classpath 里的 seckill.lua。
     * 返回值是 Lua 里的 return 数字，对应 Long。
     */
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
        // 1. 三个 key 的顺序要和 seckill.lua 的 KEYS[1] KEYS[2] KEYS[3] 一致
        List<String> keys = Arrays.asList(
                SECKILL_STOCK_KEY + voucherId,
                SECKILL_ORDER_KEY + voucherId,
                SECKILL_VOUCHER_KEY + voucherId
        );
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, keys, userId.toString());
        if (result == null) {
            return Result.fail("秒杀失败");
        }
        int code = result.intValue();
        if (code != 0) {
            return Result.fail(seckillFailMessage(code));
        }
        // 2. 先生成订单号再发消息。确认被接受后，页面拿到的就是即将入库的那个号
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(redisIdWorker.nextId("order"));
        message.setUserId(userId);
        message.setVoucherId(voucherId);
        CorrelationData correlationData = new CorrelationData(message.getOrderId().toString());
        // 默认交换机，routing key 写成队列名，消息进入 seckill.order.queue
        rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_ORDER_QUEUE, message, correlationData);
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_WAIT_SECONDS, TimeUnit.SECONDS);
            // ack 只说明交换机收到了。getReturned 非空说明路由失败，队列里没有这条消息
            boolean accepted = confirm != null && confirm.isAck() && correlationData.getReturned() == null;
            if (accepted) {
                return Result.ok(message.getOrderId());
            }
            log.error("秒杀消息被拒绝, orderId={}, confirm={}", message.getOrderId(), confirm);
        } catch (TimeoutException e) {
            // 确认没在 3 秒内回来。消息可能已经在队列里，回滚 Redis 会让同一个人再买一单
            log.error("等待发布确认超时, orderId={}", message.getOrderId());
            return Result.ok(message.getOrderId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待发布确认被中断, orderId={}", message.getOrderId());
            return Result.ok(message.getOrderId());
        } catch (ExecutionException e) {
            log.error("发布确认失败, orderId={}", message.getOrderId(), e);
            return Result.ok(message.getOrderId());
        }
        restoreSeckill(voucherId, userId);
        return Result.fail("下单失败，请重试");
    }

    @Override
    @Transactional
    public VoucherOrderCreateStatus createVoucherOrder(VoucherOrderMessage message) {
        if (message == null || message.getOrderId() == null
                || message.getUserId() == null || message.getVoucherId() == null) {
            log.error("秒杀订单消息不完整: {}", message);
            return VoucherOrderCreateStatus.INVALID;
        }
        // 同一个订单号再投递一次时，库存不能再减。主键冲突之前就返回
        if (getById(message.getOrderId()) != null) {
            log.info("秒杀订单已存在, orderId={}", message.getOrderId());
            return VoucherOrderCreateStatus.EXISTS;
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", message.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("秒杀订单入库失败，数据库库存不足，回滚 Redis, orderId={}", message.getOrderId());
            restoreSeckill(message.getVoucherId(), message.getUserId());
            return VoucherOrderCreateStatus.STOCK_FAILED;
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());
        save(voucherOrder);
        // 取消消息先进等待队列。确认被拒绝就抛异常，让上面的插入和扣库存一起回滚
        scheduleUnpaidCancel(message.getOrderId());
        return VoucherOrderCreateStatus.CREATED;
    }

    @Override
    public Result payOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        // 只改「这个人的、仍未支付」的订单。和取消消息抢的是同一行，谁先改掉 status=1 谁生效
        boolean paid = update()
                .set("status", ORDER_STATUS_PAID)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", ORDER_STATUS_UNPAID)
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
        return Result.fail("订单无法支付");
    }

    @Override
    @Transactional
    public void cancelUnpaidOrder(Long orderId) {
        if (orderId == null) {
            log.error("取消消息没有订单号");
            return;
        }
        // 只有未支付改得了。影响 0 行表示已支付、已取消，或订单不存在，不能再还库存
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
        restoreSeckill(order.getVoucherId(), order.getUserId());
    }

    /**
     * 发到 seckill.order.delay。队列上的过期时间到了以后，RabbitMQ 把它转进取消队列。
     */
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

    /**
     * 用户还在 seckill:order 的 Set 里时，库存加 1 并移出 Set。脚本是原子的，调用两次也只加一次。
     */
    private void restoreSeckill(Long voucherId, Long userId) {
        Long restored = stringRedisTemplate.execute(
                RESTORE_SCRIPT,
                Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                userId.toString()
        );
        log.info("回滚秒杀资格, voucherId={}, userId={}, restored={}", voucherId, userId, restored);
    }

    /**
     * 把 Lua 的返回码翻译成接口提示。数字含义写在 seckill.lua 顶部。
     */
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
