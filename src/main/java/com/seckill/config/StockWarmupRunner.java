package com.seckill.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.SeckillActivityMapper;
import com.seckill.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动预热：把活动库存从 MySQL 加载到 Redis。
 * 秒杀开始前必须预热，否则 Redis 里没有库存 key，扣减会返回 -1（未预热）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWarmupRunner implements ApplicationRunner {

    private final SeckillActivityMapper activityMapper;
    private final StockService stockService;

    @Override
    public void run(ApplicationArguments args) {
        List<SeckillActivity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().ne(SeckillActivity::getStatus, 3));
        for (SeckillActivity a : activities) {
            stockService.warmup(a.getId(), a.getStock());
        }
        log.info("启动预热完成，共 {} 个活动库存已加载到 Redis", activities.size());
    }
}
