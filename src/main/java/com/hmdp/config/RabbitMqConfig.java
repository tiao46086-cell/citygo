package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMqConfig {

    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    /** 没有消费者。消息在这里等到过期，再被投到死信交换机 */
    public static final String SECKILL_ORDER_DELAY_QUEUE = "seckill.order.delay";
    public static final String SECKILL_ORDER_CANCEL_QUEUE = "seckill.order.cancel";
    public static final String SECKILL_ORDER_DLX = "seckill.order.dlx";

    /**
     * durable=true：RabbitMQ 重启后这个队列还在。
     * 不单独声明交换机。发送时 routing key 用队列名，会走到自带的默认交换机。
     */
    @Bean
    public Queue seckillOrderQueue() {
        return new Queue(SECKILL_ORDER_QUEUE, true);
    }

    /**
     * 等待队列。x-message-ttl 到期后，消息进入死信交换机，再转到取消队列。
     * 这个时间写进了队列参数，以后要改必须先删掉 seckill.order.delay，否则启动报参数不一致。
     */
    @Bean
    public Queue seckillOrderDelayQueue(@Value("${citygo.seckill.unpaid-timeout:15m}") Duration unpaidTimeout) {
        long millis = unpaidTimeout.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalStateException("unpaid-timeout 超出范围: " + unpaidTimeout);
        }
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", (int) millis);
        args.put("x-dead-letter-exchange", SECKILL_ORDER_DLX);
        args.put("x-dead-letter-routing-key", SECKILL_ORDER_CANCEL_QUEUE);
        return new Queue(SECKILL_ORDER_DELAY_QUEUE, true, false, false, args);
    }

    @Bean
    public DirectExchange seckillOrderDlx() {
        return new DirectExchange(SECKILL_ORDER_DLX);
    }

    @Bean
    public Queue seckillOrderCancelQueue() {
        return new Queue(SECKILL_ORDER_CANCEL_QUEUE, true);
    }

    @Bean
    public Binding seckillOrderCancelBinding(
            @Qualifier("seckillOrderCancelQueue") Queue seckillOrderCancelQueue,
            DirectExchange seckillOrderDlx) {
        return BindingBuilder.bind(seckillOrderCancelQueue).to(seckillOrderDlx).with(SECKILL_ORDER_CANCEL_QUEUE);
    }

    /**
     * 生产者和消费者都用 JSON，而不是 Java 原生序列化。
     */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
