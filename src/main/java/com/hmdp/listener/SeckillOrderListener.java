package com.hmdp.listener;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.VoucherOrderCancelMessage;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.ConfirmedSender;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

@Slf4j
@Component
public class SeckillOrderListener {

    /** 第一次加上两次重试，一共处理 3 次。再失败就进失败队列，不再立刻入队 */
    private static final int MAX_ATTEMPTS = 3;
    private static final String RETRY_HEADER = "x-seckill-retry";

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ConfirmedSender confirmedSender;

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_QUEUE)
    public void listenSeckillOrder(VoucherOrderMessage message, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                    Message raw) throws IOException {
        int times = retryCount(header(raw));
        if (times >= MAX_ATTEMPTS) {
            park(channel, deliveryTag, message, times);
            return;
        }
        VoucherOrderCreateStatus status;
        try {
            status = voucherOrderService.createVoucherOrder(message);
        } catch (Exception e) {
            log.error("秒杀订单消费异常, message={}, times={}", message, times, e);
            retryLater(channel, deliveryTag, message, RabbitMqConfig.SECKILL_ORDER_RETRY_QUEUE, times);
            return;
        }
        channel.basicAck(deliveryTag, false);
        log.info("秒杀订单已应答, orderId={}, status={}", message == null ? null : message.getOrderId(), status);
    }

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_CANCEL_QUEUE)
    public void listenCancelOrder(VoucherOrderCancelMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                   Message raw) throws IOException {
        int times = retryCount(header(raw));
        if (times >= MAX_ATTEMPTS) {
            park(channel, deliveryTag, message, times);
            return;
        }
        Long orderId = message == null ? null : message.getOrderId();
        try {
            // 两个方法都走接口代理。第一个返回时数据库事务已经提交，然后再动 Redis
            voucherOrderService.markOrderCancelled(orderId);
            voucherOrderService.restoreRedisIfCancelled(orderId);
        } catch (Exception e) {
            log.error("取消未支付订单失败, message={}, times={}", message, times, e);
            retryLater(channel, deliveryTag, message, RabbitMqConfig.SECKILL_ORDER_CANCEL_RETRY_QUEUE, times);
            return;
        }
        channel.basicAck(deliveryTag, false);
        log.info("取消消息已应答, orderId={}", orderId);
    }

    private void retryLater(Channel channel, long deliveryTag, Object payload, String retryQueue,
                            int times) throws IOException {
        int next = times + 1;
        if (next >= MAX_ATTEMPTS) {
            park(channel, deliveryTag, payload, next);
            return;
        }
        // 先标成重试中，扫描器才不会把没有重试头的新消息再塞进来
        if (!markForwarded(payload, false)) {
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        ConfirmedSender.Outcome outcome = confirmedSender.send(retryQueue, payload, retryHeader(next));
        if (outcome != ConfirmedSender.Outcome.ACCEPTED) {
            log.error("重试消息没有确认进入队列，原消息重新入队, payload={}, outcome={}", payload, outcome);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        channel.basicAck(deliveryTag, false);
        log.warn("消息 {} 秒后重试, times={}, payload={}", RabbitMqConfig.SECKILL_RETRY_DELAY_MILLIS / 1000, next, payload);
    }

    private void park(Channel channel, long deliveryTag, Object payload, int times) throws IOException {
        if (!markForwarded(payload, true)) {
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        ConfirmedSender.Outcome outcome = confirmedSender.send(
                RabbitMqConfig.SECKILL_ORDER_FAIL_QUEUE, payload, retryHeader(times));
        if (outcome != ConfirmedSender.Outcome.ACCEPTED) {
            log.error("失败队列没有确认收到，原消息重新入队, payload={}, outcome={}", payload, outcome);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        channel.basicAck(deliveryTag, false);
        log.error("消费失败次数用完，已进入失败队列, times={}, payload={}", times, payload);
    }

    /**
     * 下单消息转走之后要改预占状态。取消消息没有预占，不用改。
     * @return false 表示状态没写上，调用方不要应答原消息
     */
    private boolean markForwarded(Object payload, boolean stopped) {
        if (!(payload instanceof VoucherOrderMessage)) {
            return true;
        }
        Long orderId = ((VoucherOrderMessage) payload).getOrderId();
        try {
            if (stopped) {
                voucherOrderService.markPendingStopped(orderId);
            } else {
                voucherOrderService.markPendingRetrying(orderId);
            }
            return true;
        } catch (RuntimeException e) {
            log.error("更新预占状态失败, orderId={}, stopped={}", orderId, stopped, e);
            return false;
        }
    }

    private MessagePostProcessor retryHeader(int times) {
        return message -> {
            message.getMessageProperties().setHeader(RETRY_HEADER, times);
            return message;
        };
    }

    private Object header(Message raw) {
        if (raw == null || raw.getMessageProperties() == null) {
            return null;
        }
        return raw.getMessageProperties().getHeader(RETRY_HEADER);
    }

    private int retryCount(Object retry) {
        if (retry instanceof Number) {
            return ((Number) retry).intValue();
        }
        if (retry == null) {
            return 0;
        }
        try {
            return Integer.parseInt(retry.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
