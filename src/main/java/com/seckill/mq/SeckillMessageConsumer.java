package com.seckill.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.mapper.SeckillActivityMapper;
import com.seckill.mapper.SeckillOrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RocketMQ 消费者：真正做下游"重活"的地方（扣 MySQL 兜底库存、更新订单状态）。
 *
 * <p>幂等性：RocketMQ 是"至少一次"投递，消息可能重复。这里用订单状态做幂等守卫——
 * 已处理(status=1)的直接跳过；且"扣库存+更新订单+更新消息表"在同一事务里，要么全成要么全滚，
 * 重复消费不会重复扣库存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = "seckill-order-topic", consumerGroup = "seckill-consumer-group")
public class SeckillMessageConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final SeckillOrderMapper orderMapper;
    private final TransactionLogMapper logMapper;
    private final SeckillActivityMapper activityMapper;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void onMessage(String json) {
        SeckillMessage msg;
        try {
            msg = objectMapper.readValue(json, SeckillMessage.class);
        } catch (Exception e) {
            log.error("消息解析失败，丢弃: {}", json, e);
            return;
        }

        SeckillOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, msg.getOrderNo()));
        if (order == null) {
            log.warn("订单不存在: {}", msg.getOrderNo());
            return;
        }
        // 幂等守卫：已处理过直接跳过
        if (order.getStatus() == 1) {
            return;
        }

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // 扣 MySQL 兜底库存（乐观扣减，防超卖）
                int rows = activityMapper.deductStock(msg.getActivityId(), msg.getQuantity());
                if (rows == 0) {
                    // 理论上不会发生（Redis 已预扣），抛异常触发 MQ 重试
                    throw new IllegalStateException("MySQL 库存不足: " + msg.getActivityId());
                }
                // 更新订单状态 0 -> 1
                orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getOrderNo, msg.getOrderNo())
                        .set(SeckillOrder::getStatus, 1));
                // 更新消息表 1 -> 2
                logMapper.update(null, new LambdaUpdateWrapper<TransactionLog>()
                        .eq(TransactionLog::getOrderNo, msg.getOrderNo())
                        .set(TransactionLog::getStatus, 2));
            });
        } catch (Exception e) {
            // 抛异常让 RocketMQ 重试（默认重试 16 次）
            log.error("消费处理失败，等待重试: {}", msg.getOrderNo(), e);
            throw e;
        }
    }
}
