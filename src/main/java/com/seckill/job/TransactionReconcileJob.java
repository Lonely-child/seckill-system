package com.seckill.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.entity.SeckillOrder;
import com.seckill.entity.TransactionLog;
import com.seckill.mapper.SeckillOrderMapper;
import com.seckill.mapper.TransactionLogMapper;
import com.seckill.mq.SeckillMessageProducer;
import com.seckill.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时对账任务 —— 本地消息表的补偿机制，保证最终一致性。
 *
 * <p>分布式事务的两难：强一致（Seata/2PC）会拖慢吞吐，不适合高并发秒杀。
 * 这里选"最终一致 + 对账补偿"：牺牲极短实时性，换高吞吐 + 数据最终可靠。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionReconcileJob {

    private static final int MAX_RETRY = 5;

    private final TransactionLogMapper logMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillMessageProducer messageProducer;
    private final StockService stockService;

    /** 每分钟对账一次 */
    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        // 1. 待发送(status=0)超过 30 秒的：发 MQ 时失败或进程崩了，需要重发
        LocalDateTime pendingBefore = LocalDateTime.now().minusSeconds(30);
        List<TransactionLog> pending = logMapper.selectList(
                new LambdaQueryWrapper<TransactionLog>()
                        .eq(TransactionLog::getStatus, 0)
                        .lt(TransactionLog::getCreateTime, pendingBefore));
        for (TransactionLog txLog : pending) {
            resend(txLog);
        }

        // 2. 已发送(status=1)超过 3 分钟仍未完成的：消费者可能挂了，检查并补偿
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(3);
        List<TransactionLog> stale = logMapper.selectList(
                new LambdaQueryWrapper<TransactionLog>()
                        .eq(TransactionLog::getStatus, 1)
                        .lt(TransactionLog::getCreateTime, staleBefore));
        for (TransactionLog txLog : stale) {
            compensate(txLog);
        }
    }

    /** 重发待发送的消息 */
    private void resend(TransactionLog txLog) {
        if (txLog.getRetryCount() >= MAX_RETRY) {
            rollback(txLog);
            return;
        }
        try {
            messageProducer.send(txLog.getMsgBody());
            logMapper.update(null, new LambdaUpdateWrapper<TransactionLog>()
                    .eq(TransactionLog::getId, txLog.getId())
                    .set(TransactionLog::getStatus, 1)
                    .set(TransactionLog::getRetryCount, txLog.getRetryCount() + 1));
        } catch (Exception e) {
            log.error("重发失败: {}", txLog.getOrderNo(), e);
        }
    }

    /** 补偿已发送但长时间未完成的 */
    private void compensate(TransactionLog txLog) {
        SeckillOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, txLog.getOrderNo()));
        if (order != null && order.getStatus() == 1) {
            // 订单其实已完成，只是消息表状态没更新，补上即可
            logMapper.update(null, new LambdaUpdateWrapper<TransactionLog>()
                    .eq(TransactionLog::getId, txLog.getId())
                    .set(TransactionLog::getStatus, 2));
            return;
        }
        if (txLog.getRetryCount() >= MAX_RETRY) {
            rollback(txLog);
            return;
        }
        try {
            messageProducer.send(txLog.getMsgBody());
            logMapper.update(null, new LambdaUpdateWrapper<TransactionLog>()
                    .eq(TransactionLog::getId, txLog.getId())
                    .set(TransactionLog::getRetryCount, txLog.getRetryCount() + 1));
        } catch (Exception e) {
            log.error("补偿重发失败: {}", txLog.getOrderNo(), e);
        }
    }

    /** 重试耗尽后回滚：取消订单 + 加回 Redis 库存 + 标记已回滚 */
    private void rollback(TransactionLog txLog) {
        SeckillOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, txLog.getOrderNo()));
        if (order != null && order.getStatus() == 0) {
            orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getOrderNo, txLog.getOrderNo())
                    .set(SeckillOrder::getStatus, 2));
            stockService.rollback(order.getActivityId(), order.getQuantity());
        }
        logMapper.update(null, new LambdaUpdateWrapper<TransactionLog>()
                .eq(TransactionLog::getId, txLog.getId())
                .set(TransactionLog::getStatus, 3));
        log.warn("对账回滚完成: {}", txLog.getOrderNo());
    }
}
