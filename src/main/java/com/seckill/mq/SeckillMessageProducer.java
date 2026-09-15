package com.seckill.mq;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 生产者：异步削峰的出口。
 */
@Component
@RequiredArgsConstructor
public class SeckillMessageProducer {

    public static final String TOPIC = "seckill-order-topic";

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 同步发送消息（消息体为 JSON 字符串）。
     * 发送失败会抛异常，由本地消息表 + 定时对账兜底重发。
     */
    public void send(String jsonBody) {
        rocketMQTemplate.syncSend(TOPIC, jsonBody);
    }
}
