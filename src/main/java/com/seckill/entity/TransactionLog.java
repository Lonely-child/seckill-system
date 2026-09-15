package com.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（分布式事务/最终一致性的核心）。
 * 与订单在同一本地事务落库，保证"发消息"和"业务"原子；
 * 定时对账任务扫描未完成的日志做补偿（重发 / 回滚库存）。
 */
@Data
@TableName("t_transaction_log")
public class TransactionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String msgBody;

    /** 0待发送 1已发送 2已完成 3已回滚 */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
