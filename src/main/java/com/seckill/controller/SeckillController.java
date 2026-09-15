package com.seckill.controller;

import com.seckill.common.BizException;
import com.seckill.common.Result;
import com.seckill.entity.SeckillActivity;
import com.seckill.mapper.SeckillActivityMapper;
import com.seckill.service.ActivityCacheService;
import com.seckill.service.SeckillService;
import com.seckill.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final ActivityCacheService activityCacheService;
    private final SeckillActivityMapper activityMapper;
    private final StockService stockService;

    /**
     * 秒杀下单（压测打这个接口）。
     * 例如：POST /seckill/1?userId=1001&quantity=1
     */
    @PostMapping("/{activityId}")
    public Result<String> seckill(@PathVariable Long activityId,
                                  @RequestParam Long userId,
                                  @RequestParam(defaultValue = "1") Integer quantity) {
        return seckillService.seckill(userId, activityId, quantity);
    }

    /** 商品详情（走缓存，验证穿透/击穿/雪崩防护） */
    @GetMapping("/activity/{activityId}")
    public Result<SeckillActivity> detail(@PathVariable Long activityId) {
        return Result.ok(activityCacheService.getById(activityId));
    }

    /** 手动预热库存（测试用，把 MySQL 库存重新加载到 Redis） */
    @PostMapping("/activity/{activityId}/warmup")
    public Result<Void> warmup(@PathVariable Long activityId) {
        SeckillActivity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BizException("活动不存在");
        }
        stockService.warmup(activityId, a.getStock());
        return Result.ok();
    }
}
