# 高并发秒杀系统

一个 Java 高并发项目，覆盖秒杀场景最核心的工程问题：**防超卖、削峰、最终一致性、缓存三大问题、限流**。

> 核心设计一句话：**Redis 负责高并发的原子预扣（防超卖），MySQL 负责最终一致兜底，RocketMQ 负责异步削峰。**

## 技术栈

| 层次 | 技术 |
|---|---|
| 语言/框架 | Java 17 · Spring Boot 3.2 |
| 缓存 | Redis 7（Lua 原子扣库存 / 缓存 / 限流） |
| 数据库 | MySQL 8（MyBatis-Plus） |
| 消息队列 | RocketMQ 5（削峰 + 异步下单） |
| 压测 | JMeter |

## 核心架构（一条请求的完整链路）

```
用户请求
   │
   ▼
① 限流（Redis 令牌桶 Lua）── 挡在最外层，拒绝超阈值流量
   │
   ▼
② 查活动（Redis 缓存，防穿透/击穿/雪崩）
   │
   ▼
③ 幂等防重（Redis setnx 快路径 + DB 唯一索引兜底）
   │
   ▼
④ Redis Lua 原子扣库存（防超卖的核心）
   │
   ▼
⑤ 本地事务：订单 + 本地消息表 一起落库
   │
   ▼
⑥ 发 RocketMQ（异步削峰，发送失败由对账补偿）
   │
   ▼
⑦ 消费者：扣 MySQL 兜底库存 + 更新订单状态 + 更新消息表
   │
   ▼
⑧ 定时对账：扫描未完成消息 → 重发 / 回滚库存（最终一致性）
```

## 快速开始

### 1. 启动中间件（Docker）

```bash
docker compose up -d
```

这会一键启动 MySQL、Redis、RocketMQ（namesrv + broker）三个容器。

> ⚠️ **重要**：`rocketmq/broker.conf` 里的 `brokerIP1` 必须是你**当前电脑的局域网 IP**。
> 我用的是 `10.27.197.107`，如果你换 WiFi/网络，IP 会变，请先执行 `ipconfig` 查到你当前的 IPv4，
> 改掉 `broker.conf` 里的 `brokerIP1`，再 `docker compose up -d`。

### 2. 启动应用

```bash
mvn spring-boot:run
```

启动时会自动把活动库存从 MySQL 预热到 Redis（`StockWarmupRunner`）。

### 3. 测试接口

```bash
# 商品详情（走缓存）
curl "http://localhost:8080/seckill/activity/1"

# 秒杀下单（userId 每次换不同值，避免重复下单）
curl -X POST "http://localhost:8080/seckill/1?userId=1001&quantity=1"

# 手动预热库存（测试用）
curl -X POST "http://localhost:8080/seckill/activity/1/warmup"
```

返回示例：`{"code":0,"msg":"success","data":"订单号"}`

## 核心亮点（面试重点）

### 1. 防超卖：Redis + Lua 原子扣库存
库存扣减是"判断 + 扣减"两步，拆开执行会有竞态。Redis 单线程顺序执行命令，把两步写进一个 Lua 脚本交给 Redis 执行，脚本执行期间不被其他命令打断，从而保证原子性。MySQL 侧再用 `WHERE stock >= quantity` 乐观扣减兜底。

### 2. 最终一致性：本地消息表 + 定时对账（亮点二）
扣 Redis 成功后，订单和消息表在**同一个本地事务**落库，再发 MQ。若发 MQ 失败或消费者挂了，定时对账任务扫描未完成的消息做补偿（重发 / 重试耗尽后回滚库存）。**不用 Seata**——强一致 2PC 会拖慢吞吐，不适合秒杀，选"最终一致 + 对账"换高吞吐。

### 3. 缓存三大问题：穿透 / 击穿 / 雪崩（亮点三修正版）
- **穿透**：空值缓存（DB 查不到也缓存短 TTL）
- **击穿**：`SET NX EX` 互斥锁（注意不是 setnx + expire 两步，两步之间进程挂了会死锁）
- **雪崩**：随机 TTL（避免大量 key 同一时刻过期集体打 DB）

> 边界：缓存只缓存"读多写少、弱一致"的商品详情。**库存绝不能放本地/普通缓存**（多实例副本不一致会直接超卖），必须走 Redis 集中扣减。

### 4. 削峰：RocketMQ 异步下单
抢到库存的请求发 MQ 异步落库，而不是同步写 MySQL，避免瞬时流量打崩数据库。

### 5. 幂等：防重复下单 + 防重复消费
- 下单：Redis setnx 快路径 + DB 唯一索引 `uk_user_activity` 兜底
- 消费：订单状态做幂等守卫 + 同一事务保证原子，RocketMQ 重复投递不会重复扣库存

### 6. 限流：令牌桶
令牌桶以恒定速率补令牌，允许一定突发但总量可控，避免固定窗口的"临界突刺"问题。

## 压测

**环境**：单台笔记本（Windows 11 + Docker Desktop），MySQL / Redis / RocketMQ 跑在 Docker，Spring Boot 应用与 JMeter 同机运行。**所有组件挤在一台机器上，数字是"下限"，部署到独立服务器会显著更高。**

**场景**：1000 线程并发抢 100 件商品（`jmeter/seckill-test.jmx`），启动预热后库存 100。

| 指标 | 实测值 |
|---|---|
| 总请求 | 1000（1 秒内全部发出） |
| 成功处理 | 914（HTTP 200） |
| 连接被拒 | 86（8.6%，Tomcat 默认 backlog 打满） |
| 吞吐 | 813 req/s |
| RT p50 / p90 / p99 | 132ms / 512ms / 885ms |
| 实际成交 | 100 单 |
| **超卖** | **0**（订单数 == 库存数，Redis 扣到 0） |

**三个关键结论：**

1. **0 超卖**：100 库存 → 100 订单 → Redis 剩余 0，三层防护（Lua 原子扣减 + MySQL `WHERE stock >= quantity` 乐观扣减 + 幂等）扛住了 1000 并发。
2. **瓶颈在连接层，不在业务层**：222 个请求在 TCP 层就被拒（`Connect refused`），因为单机 Tomcat 默认 `maxThreads=200` + accept 队列（默认 100）被打满——这是「为什么还需要 Nginx 连接限流 + 水平扩容」的实测证据，也是限流的边界：**应用层限流挡不住 TCP 连接层的拒绝**。
3. **压测方法决定数字**：同一环境三次压测，冷启动（JIT 未预热）时 p50 飙到 809ms、25% 连接被拒；关掉 SQL 日志 + 预热后 p50 降到 132ms、错误率 8.6%。所以压测前必须预热 JIT、关 SQL 日志，否则数字失真。RocketMQ 同步发送仍是 RT 大头，改异步发送还能再降。

## 项目结构

```
src/main/java/com/seckill/
├── SeckillApplication.java        # 启动类
├── common/                        # Result / BizException / 全局异常
├── config/                        # Redis 配置、启动预热
├── controller/SeckillController   # 接口
├── entity/                        # 活动 / 订单 / 本地消息表
├── mapper/                        # MyBatis-Plus Mapper
├── service/                       # 秒杀主流程 / 库存 / 限流 / 缓存
├── mq/                            # RocketMQ 生产者 / 消费者 / 消息体
└── job/TransactionReconcileJob    # 定时对账
```
