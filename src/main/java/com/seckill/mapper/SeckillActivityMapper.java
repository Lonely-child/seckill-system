package com.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seckill.entity.SeckillActivity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 扣减 MySQL 兜底库存（乐观扣减：WHERE stock >= quantity 防止超卖）。
     * 由 MQ 消费者异步调用，保证 MySQL 与 Redis 最终一致。
     *
     * @return 受影响行数，0 表示库存不足
     */
    @Update("UPDATE t_seckill_activity SET stock = stock - #{quantity} " +
            "WHERE id = #{activityId} AND stock >= #{quantity}")
    int deductStock(@Param("activityId") Long activityId, @Param("quantity") Integer quantity);

    /**
     * 回滚库存（对账补偿失败时加回）。
     */
    @Update("UPDATE t_seckill_activity SET stock = stock + #{quantity} WHERE id = #{activityId}")
    int addStock(@Param("activityId") Long activityId, @Param("quantity") Integer quantity);
}
