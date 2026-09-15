package com.seckill.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀下单消息（RocketMQ 消息体）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage {

    private String orderNo;

    private Long userId;

    private Long activityId;

    private Integer quantity;
}
