-- ============================================================
-- 秒杀系统建表脚本
-- MySQL 8.0（由 docker-compose 自动初始化）
-- ============================================================
CREATE DATABASE IF NOT EXISTS seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE seckill;

-- ------------------------------------------------------------
-- 1. 秒杀活动（商品）表
-- stock 是 MySQL 兜底库存，真正的秒杀扣减在 Redis 里完成
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_seckill_activity (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128)  NOT NULL COMMENT '商品名称',
    description VARCHAR(512)           COMMENT '商品描述（读多写少，适合缓存）',
    price       DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
    stock       INT           NOT NULL COMMENT '总库存（MySQL 兜底，最终一致）',
    start_time  DATETIME      NOT NULL COMMENT '活动开始时间',
    end_time    DATETIME      NOT NULL COMMENT '活动结束时间',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1未开始 2进行中 3已结束',
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB COMMENT = '秒杀活动表';

-- ------------------------------------------------------------
-- 2. 订单表
-- uk_user_activity 唯一索引：从数据库层面兜底"一人一单"，防重复下单
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_seckill_order (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(64)   NOT NULL COMMENT '订单号',
    user_id     BIGINT        NOT NULL COMMENT '用户ID',
    activity_id BIGINT        NOT NULL COMMENT '活动ID',
    quantity    INT           NOT NULL DEFAULT 1 COMMENT '购买数量',
    amount      DECIMAL(10,2) NOT NULL COMMENT '订单金额',
    status      TINYINT       NOT NULL DEFAULT 0 COMMENT '0创建中 1已创建 2已取消',
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_activity (user_id, activity_id)
) ENGINE = InnoDB COMMENT = '秒杀订单表';

-- ------------------------------------------------------------
-- 3. 本地消息表（分布式事务/最终一致性的核心）
-- 与订单在同一本地事务落库，保证"发消息"和"业务"原子；
-- 定时任务扫描未完成日志做补偿。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_transaction_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(64) NOT NULL COMMENT '关联订单号',
    msg_body    TEXT                 COMMENT 'MQ 消息体（JSON）',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2已完成 3已回滚',
    retry_count INT         NOT NULL DEFAULT 0 COMMENT '补偿重试次数',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status_create (status, create_time)
) ENGINE = InnoDB COMMENT = '本地消息表';

-- ------------------------------------------------------------
-- 初始化测试数据
-- ------------------------------------------------------------
INSERT INTO t_seckill_activity (name, description, price, stock, start_time, end_time, status)
VALUES ('iPhone 15 Pro 秒杀', 'iPhone 15 Pro 256G 钛金属，限时秒杀', 6999.00, 100, '2026-01-01 00:00:00', '2030-01-01 00:00:00', 2);
