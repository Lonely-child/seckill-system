package com.seckill.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.BizException;
import com.seckill.common.Result;
import com.seckill.entity.SeckillActivity;
import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.mapper.SeckillOrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.mq.SeckillMessage;
import com.seckill.mq.SeckillMessageProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * 秒杀下单主流程。
 *
 * <p>一条请求依次经过：限流 → 查活动(缓存) → 幂等防重 → Redis Lua 原子扣库存 →
 * 本地事务(订单+消息表) → 发 MQ 削峰。Redis 负责高并发预扣（防超卖），
 * MySQL 负责最终一致兜底，RocketMQ 负责异步削峰。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private static final String FLAG_KEY_PREFIX = "seckill:order:flag:";
    private static final String RATE_KEY_PREFIX = "seckill:rate:";

    private final StringRedisTemplate stringRedisTemplate;
    private final StockService stockService;
    private final RateLimitService rateLimitService;
    private final ActivityCacheService activityCacheService;
    private final SeckillOrderMapper orderMapper;
    private final TransactionLogMapper logMapper;
    private final SeckillMessageProducer messageProducer;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    public Result<String> seckill(Long userId, Long activityId, Integer quantity) {
        // 1. 令牌桶限流，挡在最外层，保护下游不被打垮
        if (!rateLimitService.tryAcquire(RATE_KEY_PREFIX + activityId, 1000, 1000)) {
            throw new BizException(429, "系统繁忙，请稍后再试");
        }

        // 2. 查活动（商品详情走缓存），顺带拿到价格
        SeckillActivity activity = activityCacheService.getById(activityId);
        if (activity == null) {
            throw new BizException("活动不存在");
        }

        // 3. 幂等防重：Redis setnx 是快路径（DB 唯一索引是最终兜底）
        String flagKey = FLAG_KEY_PREFIX + activityId + ":" + userId;
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(flagKey, "1", Duration.ofMinutes(10));
        if (Boolean.FALSE.equals(first)) {
            throw new BizException(409, "请勿重复下单");
        }

        // 4. 构造订单号 + 消息体（在扣库存前完成，失败无需回滚库存）
        String orderNo = generateOrderNo();
        final String msgJson;
        try {
            msgJson = objectMapper.writeValueAsString(new SeckillMessage(orderNo, userId, activityId, quantity));
        } catch (JsonProcessingException e) {
            stringRedisTemplate.delete(flagKey);
            throw new BizException("下单失败");
        }

        // 5. Redis Lua 原子扣库存（防超卖核心）
        long deduct = stockService.deduct(activityId, quantity);
        if (deduct == -1) {
            stringRedisTemplate.delete(flagKey);
            throw new BizException("活动未预热，请先预热库存");
        }
        if (deduct == 0) {
            stringRedisTemplate.delete(flagKey);
            throw new BizException("库存不足");
        }

        // 6. 本地事务：订单 + 消息表 一起落库，保证"业务"和"消息"原子
        BigDecimal amount = activity.getPrice().multiply(BigDecimal.valueOf(quantity));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                SeckillOrder order = new SeckillOrder();
                order.setOrderNo(orderNo);
                order.setUserId(userId);
                order.setActivityId(activityId);
                order.setQuantity(quantity);
                order.setAmount(amount);
                order.setStatus(0);
                orderMapper.insert(order);

                TransactionLog txLog = new TransactionLog();
                txLog.setOrderNo(orderNo);
                txLog.setMsgBody(msgJson);
                txLog.setStatus(0);
                txLog.setRetryCount(0);
                logMapper.insert(txLog);
            });
        } catch (DuplicateKeyException e) {
            // DB 唯一索引兜底：重复下单 → 回滚库存 + 释放 flag
            stockService.rollback(activityId, quantity);
            stringRedisTemplate.delete(flagKey);
            throw new BizException(409, "请勿重复下单");
        } catch (Exception e) {
            // 落库失败 → 回滚库存，避免"扣了库存却没订单"的不一致
            stockService.rollback(activityId, quantity);
            stringRedisTemplate.delete(flagKey);
            log.error("下单落库失败", e);
            throw new BizException("下单失败");
        }

        // 7. 事务提交后发 MQ（异步削峰）；发送失败不阻塞下单结果，由对账任务补偿重发
        try {
            messageProducer.send(msgJson);
            // 条件更新：仅当 status=0 时才置为 1，避免覆盖消费者已经写下的 status=2
            logMapper.update(null, new LambdaUpdateWrapper<TransactionLog>()
                    .eq(TransactionLog::getOrderNo, orderNo)
                    .eq(TransactionLog::getStatus, 0)
                    .set(TransactionLog::getStatus, 1));
        } catch (Exception e) {
            log.warn("MQ 发送失败，等待对账重发, orderNo={}", orderNo, e);
        }

        return Result.ok(orderNo);
    }

    private String generateOrderNo() {
        // 生产环境应使用雪花算法 / 美团 Leaf 号段发号器（高吞吐、趋势递增）
        return UUID.randomUUID().toString().replace("-", "");
    }
}
