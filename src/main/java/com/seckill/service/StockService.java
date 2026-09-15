package com.seckill.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 库存服务 —— 秒杀防超卖的核心。
 *
 * <p>为什么用 Redis + Lua：库存扣减必须是"判断 + 扣减"两步的原子操作。
 * 如果拆成两条命令（先 GET 再 DECRBY），高并发下会读到旧值导致超卖。
 * Redis 是单线程顺序执行命令的，把两步写进一个 Lua 脚本交给 Redis 执行，
 * 脚本执行期间不会被其他命令打断，从而保证原子性。</p>
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String TOTAL_KEY_PREFIX = "seckill:stock:total:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 原子扣减库存 Lua：
     * 返回 -1 未预热 / 0 库存不足 / 1 扣减成功
     */
    private static final DefaultRedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>(
            """
            local stock = redis.call('get', KEYS[1])
            if not stock then
                return -1
            end
            if tonumber(stock) >= tonumber(ARGV[1]) then
                redis.call('decrby', KEYS[1], ARGV[1])
                return 1
            end
            return 0
            """, Long.class);

    /**
     * 回滚库存 Lua：加回但不允许超过总库存（防止重复回滚导致库存溢出）
     */
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT = new DefaultRedisScript<>(
            """
            local stock = redis.call('get', KEYS[1])
            local total = redis.call('get', KEYS[2])
            if stock and total and tonumber(stock) < tonumber(total) then
                redis.call('incrby', KEYS[1], ARGV[1])
            end
            return 1
            """, Long.class);

    /**
     * 预热库存：把 MySQL 里的库存加载到 Redis（秒杀开始前必须做，否则 deduct 返回 -1）
     */
    public void warmup(Long activityId, Integer stock) {
        stringRedisTemplate.opsForValue().set(STOCK_KEY_PREFIX + activityId, String.valueOf(stock));
        stringRedisTemplate.opsForValue().set(TOTAL_KEY_PREFIX + activityId, String.valueOf(stock));
    }

    /**
     * 原子扣减库存。
     * @return -1 未预热 / 0 库存不足 / 1 成功
     */
    public long deduct(Long activityId, Integer quantity) {
        Long result = stringRedisTemplate.execute(
                DEDUCT_SCRIPT,
                List.of(STOCK_KEY_PREFIX + activityId),
                String.valueOf(quantity));
        return result == null ? -1 : result;
    }

    /**
     * 回滚库存（对账补偿失败时调用，加回且不超过总库存）。
     */
    public void rollback(Long activityId, Integer quantity) {
        stringRedisTemplate.execute(
                ROLLBACK_SCRIPT,
                List.of(STOCK_KEY_PREFIX + activityId, TOTAL_KEY_PREFIX + activityId),
                String.valueOf(quantity));
    }
}
