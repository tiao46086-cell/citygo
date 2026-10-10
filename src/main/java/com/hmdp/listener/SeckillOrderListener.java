package com.hmdp.listener;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.VoucherOrderCancelMessage;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

@Slf4j
@Component
public class SeckillOrderListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_QUEUE)
    public void listenSeckillOrder(VoucherOrderMessage message, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        VoucherOrderCreateStatus status;
        try {
            // 接口代理调用，方法返回时事务已经提交，然后再应答
            status = voucherOrderService.createVoucherOrder(message);
        } catch (Exception e) {
            // 还没应答。重新入队后可以再减库存、再插入；订单号已存在的那次会走幂等
            log.error("秒杀订单消费异常，重新入队, message={}", message, e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        channel.basicAck(deliveryTag, false);
        log.info("秒杀订单已应答, orderId={}, status={}", message == null ? null : message.getOrderId(), status);
    }

    @RabbitListener(queues = RabbitMqConfig.SECKILL_ORDER_CANCEL_QUEUE)
    public void listenCancelOrder(VoucherOrderCancelMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            voucherOrderService.cancelUnpaidOrder(message == null ? null : message.getOrderId());
        } catch (Exception e) {
            log.error("取消未支付订单失败，重新入队, message={}", message, e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        channel.basicAck(deliveryTag, false);
        log.info("取消消息已应答, orderId={}", message == null ? null : message.getOrderId());
    }
}
