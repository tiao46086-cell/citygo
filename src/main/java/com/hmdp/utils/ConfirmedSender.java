package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 等到发布确认，并且没有被退回，才算消息进入了目标队列。
 */
@Slf4j
@Component
public class ConfirmedSender {

    public enum Outcome {
        ACCEPTED, REJECTED, UNCERTAIN
    }

    private static final long CONFIRM_WAIT_SECONDS = 3L;

    @Resource
    private RabbitTemplate rabbitTemplate;

    public Outcome send(String queue, Object payload, MessagePostProcessor processor) {
        CorrelationData correlationData = new CorrelationData();
        try {
            if (processor == null) {
                rabbitTemplate.convertAndSend("", queue, payload, correlationData);
            } else {
                rabbitTemplate.convertAndSend("", queue, payload, processor, correlationData);
            }
        } catch (AmqpConnectException | AmqpAuthenticationException e) {
            log.error("消息没有发出去, queue={}", queue, e);
            return Outcome.REJECTED;
        } catch (RuntimeException e) {
            log.error("消息发送结果不确定, queue={}", queue, e);
            return Outcome.UNCERTAIN;
        }
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_WAIT_SECONDS, TimeUnit.SECONDS);
            if (confirm != null && confirm.isAck() && correlationData.getReturned() == null) {
                return Outcome.ACCEPTED;
            }
            log.error("消息被拒绝, queue={}, confirm={}", queue, confirm);
            return Outcome.REJECTED;
        } catch (TimeoutException e) {
            log.error("等待发布确认超时, queue={}", queue);
            return Outcome.UNCERTAIN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待发布确认被中断, queue={}", queue);
            return Outcome.UNCERTAIN;
        } catch (ExecutionException e) {
            log.error("发布确认失败, queue={}", queue, e);
            return Outcome.UNCERTAIN;
        }
    }
}
