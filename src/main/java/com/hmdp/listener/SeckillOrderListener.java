package com.hmdp.listener;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.VoucherOrderCancelMessage;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_QUEUE)
    public void listenSeckillOrder(VoucherOrderMessage message, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                    @Header(name = RETRY_HEADER, required = false) Object retry) throws IOException {
        int times = retryCount(retry);
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
                                   @Header(name = RETRY_HEADER, required = false) Object retry) throws IOException {
        int times = retryCount(retry);
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
        try {
            rabbitTemplate.convertAndSend(retryQueue, payload, retryHeader(next));
            channel.basicAck(deliveryTag, false);
            log.warn("消息 {} 秒后重试, times={}, payload={}", RabbitMqConfig.SECKILL_RETRY_DELAY_MILLIS / 1000, next, payload);
        } catch (RuntimeException sendError) {
            log.error("重试消息没有发出去，暂时重新入队, payload={}", payload, sendError);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void park(Channel channel, long deliveryTag, Object payload, int times) throws IOException {
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_ORDER_FAIL_QUEUE, payload, retryHeader(times));
            channel.basicAck(deliveryTag, false);
            log.error("消费失败次数用完，已进入失败队列, times={}, payload={}", times, payload);
        } catch (RuntimeException sendError) {
            log.error("失败队列没有写进去，暂时重新入队, payload={}", payload, sendError);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private MessagePostProcessor retryHeader(int times) {
        return message -> {
            message.getMessageProperties().setHeader(RETRY_HEADER, times);
            return message;
        };
    }

    private int retryCount(Object retry) {
        if (retry instanceof Number) {
            return ((Number) retry).intValue();
        }
        if (retry == null) {
            return 0;
        }
        return Integer.parseInt(retry.toString());
    }
}
