package com.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 秒杀系统启动类。
 *
 * <p>核心设计一句话：Redis 负责高并发的"原子预扣"（防超卖），
 * MySQL 负责"最终一致兜底"，RocketMQ 负责"异步削峰"。</p>
 */
@SpringBootApplication
@MapperScan("com.seckill.mapper")
@EnableScheduling
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
