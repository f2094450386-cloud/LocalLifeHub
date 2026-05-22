# LocalLifeHub 和普通 hmdp 的区别

## 普通 hmdp 通常有什么

普通黑马点评 hmdp 项目主要用于学习 Redis 实战，常见能力包括：

- 手机号验证码登录。
- 商户类型、商户详情查询。
- 商户缓存。
- 探店笔记发布、点赞、关注、Feed。
- 优惠券秒杀基础链路。
- Redis 分布式锁或 Redis Stream 异步下单。
- 用户签到、UV 统计等 Redis 用法。

这些能力本项目大多保留，包名仍沿用 `com.hmdp`，避免大规模重命名引入风险。

## LocalLifeHub 新增和强化了什么

### 1. RocketMQ 异步秒杀下单

普通 hmdp 秒杀常见实现是 Redis Stream 或同步落库。本项目改为：

```text
Redis Lua 资格校验 -> 本地任务表 -> RocketMQ -> MySQL 落库
```

对应文件：

- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `src/main/java/com/hmdp/mq/VoucherOrderConsumer.java`
- `src/main/java/com/hmdp/dto/VoucherOrderMessage.java`
- `docs/seckill-design.md`

### 2. 本地任务表补偿

新增 `tb_voucher_order_task`，记录秒杀订单从 Redis 预扣到 MQ 投递、消费、失败补偿的状态。

对应文件：

- `sql/20260512_add_voucher_order_task.sql`
- `src/main/java/com/hmdp/entity/VoucherOrderTask.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderTaskServiceImpl.java`
- `src/main/java/com/hmdp/mq/VoucherOrderTaskCompensator.java`
- `docs/consistency.md`

### 3. 未支付订单超时关闭

新增订单关闭链路：

```text
订单创建 -> RocketMQ 延迟消息 -> 超时仍未支付 -> 关闭订单并恢复库存
```

同时使用 Spring Task 兜底扫描，避免延迟消息不可用时订单长期不关闭。

对应文件：

- `src/main/java/com/hmdp/mq/OrderTimeoutConsumer.java`
- `src/main/java/com/hmdp/mq/OrderTimeoutScanner.java`
- `docs/order-timeout.md`

### 4. 通用 AOP 限流

新增 `@RateLimit` 注解，通过 Redis ZSet + Lua 实现滑动窗口限流，已接入：

- 验证码发送。
- 秒杀下单。
- AI 客服。

对应文件：

- `src/main/java/com/hmdp/annotation/RateLimit.java`
- `src/main/java/com/hmdp/aspect/RateLimitAspect.java`
- `docs/rate-limit.md`

### 5. 商户缓存治理更完整

在普通缓存基础上整理并强化：

- 空值缓存防穿透。
- 互斥锁防击穿。
- 随机 TTL 降低雪崩。
- 热点商户逻辑过期。
- 更新后删缓存和延迟双删。

对应文件：

- `src/main/java/com/hmdp/utils/CacheClient.java`
- `src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`
- `docs/cache-design.md`

### 6. LangChain4j AI 客服

新增 AI 客服接口：

```text
POST /ai/customer-service/chat
```

能力：

- 阿里云百炼 OpenAI-compatible API。
- Redis 会话记忆。
- Function Calling 查询商户和优惠券。
- 用户原文命中商户后的预查询上下文兜底。

对应文件：

- `src/main/java/com/hmdp/controller/AiCustomerServiceController.java`
- `src/main/java/com/hmdp/service/impl/AiCustomerServiceImpl.java`
- `src/main/java/com/hmdp/service/ai/LocalLifeHubAiTools.java`
- `src/main/java/com/hmdp/service/ai/RedisChatMemoryStore.java`
- `docs/ai-customer-service.md`

### 7. Docker Compose 本地环境

普通 hmdp 通常要求自己安装 MySQL、Redis。本项目提供：

- MySQL
- Redis
- RocketMQ NameServer
- RocketMQ Broker
- RocketMQ Dashboard

对应文件：

- `docker-compose.yml`
- `docker/rocketmq/broker.conf`
- `docs/local-run.md`

## 简单总结

普通 hmdp 更偏 Redis 学习项目。

LocalLifeHub 在 hmdp 基础上强化成“可写简历的后端工程项目”，重点是：

```text
高并发秒杀 + MQ 异步解耦 + 最终一致补偿 + 缓存治理 + AOP 限流 + AI 工具调用
```

当前仍不是完整商业系统，没有真实支付网关、权限后台、RAG 知识库和正式压测报告。
