package com.seckill.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 限流服务 —— 令牌桶算法，挡在最外层保护下游。
 *
 * <p>为什么选令牌桶而不是固定窗口：固定窗口有"临界突刺"问题（前 1 秒末尾 + 后 1 秒开头
 * 各放满配额，实际 1 秒内通过了 2 倍流量）。令牌桶以恒定速率补充令牌，允许一定突发但总量可控。</p>
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 令牌桶 Lua（原子）：
     * ARGV[1]=桶容量，ARGV[2]=每秒补充令牌数，ARGV[3]=当前时间戳(ms)，ARGV[4]=请求令牌数
     * 返回 1 放行 / 0 拒绝
     */
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local data = redis.call('hmget', key, 'tokens', 'last')
            local tokens = tonumber(data[1])
            local last = tonumber(data[2])

            if tokens == nil then
                tokens = capacity
                last = now
            end

            local elapsed = (now - last) / 1000
            local refill = elapsed * rate
            tokens = math.min(capacity, tokens + refill)

            local allowed = 0
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            end

            redis.call('hmset', key, 'tokens', tokens, 'last', now)
            redis.call('expire', key, 60)
            return allowed
            """, Long.class);

    /**
     * 尝试获取一个令牌。
     * @param key 限流维度（如按活动、按用户）
     * @param capacity 桶容量（允许的最大突发）
     * @param ratePerSecond 每秒补充令牌数
     */
    public boolean tryAcquire(String key, long capacity, double ratePerSecond) {
        Long result = stringRedisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(ratePerSecond),
                String.valueOf(System.currentTimeMillis()),
                "1");
        return result != null && result == 1L;
    }
}
