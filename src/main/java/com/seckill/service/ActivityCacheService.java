package com.seckill.service;

import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.SeckillActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品详情缓存 —— 缓存三大问题（穿透/击穿/雪崩）的完整防护。
 *
 * <p>重要边界：这里缓存的只能是"读多写少、弱一致"的商品详情（名称/描述/价格）。
 * 库存是强一致、高频变化的共享状态，绝对不能放本地/普通缓存，必须走 Redis 集中扣减，
 * 否则多实例副本不一致会直接导致超卖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityCacheService {

    private static final String CACHE_KEY_PREFIX = "seckill:activity:";
    private static final String LOCK_KEY_PREFIX = "seckill:activity:lock:";
    /** 空值缓存标记（防穿透）：DB 查不到时缓存这个，避免每次都打 DB */
    private static final String NULL_FLAG = "NULL";
    private static final long CACHE_TTL_MIN_SEC = 60;
    private static final long CACHE_TTL_MAX_SEC = 120;
    private static final long NULL_TTL_SEC = 30;

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillActivityMapper activityMapper;

    public SeckillActivity getById(Long activityId) {
        String key = CACHE_KEY_PREFIX + activityId;

        // 1. 先查缓存
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            if (NULL_FLAG.equals(cached)) {
                return null; // 命中空值缓存（穿透防护）
            }
            return (SeckillActivity) cached;
        }

        // 2. 未命中：加互斥锁防击穿。
        //    setIfAbsent 带超时 = SET NX EX，是原子的；若拆成 setnx + expire 两步，
        //    两步之间进程挂了会导致锁永不过期（死锁），这是面试常考的点。
        String lockKey = LOCK_KEY_PREFIX + activityId;
        boolean locked = Boolean.TRUE.equals(
                stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(5)));
        if (locked) {
            try {
                // double check：拿到锁后再查一次缓存，避免重复回源
                cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    if (NULL_FLAG.equals(cached)) {
                        return null;
                    }
                    return (SeckillActivity) cached;
                }
                SeckillActivity activity = activityMapper.selectById(activityId);
                if (activity != null) {
                    // 随机 TTL 防雪崩：避免大量 key 同一时刻过期，集体打 DB
                    long ttl = CACHE_TTL_MIN_SEC
                            + ThreadLocalRandom.current().nextLong(CACHE_TTL_MAX_SEC - CACHE_TTL_MIN_SEC + 1);
                    redisTemplate.opsForValue().set(key, activity, Duration.ofSeconds(ttl));
                } else {
                    // 空值缓存防穿透：短 TTL 缓存"不存在"这个事实
                    redisTemplate.opsForValue().set(key, NULL_FLAG, Duration.ofSeconds(NULL_TTL_SEC));
                }
                return activity;
            } finally {
                stringRedisTemplate.delete(lockKey);
            }
        }

        // 3. 没拿到锁：其他线程正在重建缓存，这里直接回源（生产可用自旋等待重试）
        return activityMapper.selectById(activityId);
    }
}
