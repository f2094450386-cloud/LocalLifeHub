# 简历版本项目描述

## 项目名称

邻享生活 LocalLifeHub

## 项目定位

基于黑马点评 hmdp 二次改造的本地生活服务平台后端项目，围绕商户查询、优惠券秒杀、订单状态流转、接口限流和 AI 客服等场景，补充 Redis 缓存治理、RocketMQ 异步解耦、本地任务表补偿和 LangChain4j Function Calling 能力。

## 技术栈

Java 8、Spring Boot 2.7、MyBatis-Plus、MySQL、Redis、Lua、Redisson、RocketMQ、AOP、LangChain4j、Maven、Docker Compose。

## 可写进简历的项目描述

邻享生活 LocalLifeHub 是一个本地生活服务平台后端项目，基于 hmdp 业务底座进行增量改造。项目实现了手机号验证码登录、商户查询与缓存、优惠券秒杀下单、订单超时关闭、通用接口限流和 AI 客服查询商户/优惠券信息等能力。秒杀链路使用 Redis Lua 原子完成库存预扣与一人一单校验，通过 RocketMQ 异步创建订单，并使用本地任务表记录投递和消费状态，支持失败补偿和人工处理。AI 客服使用 LangChain4j 接入阿里云百炼 OpenAI-compatible API，结合 Redis 会话记忆和 Function Calling 工具查询系统数据，避免编造不存在的商户或优惠券。

## 简历亮点

### Redis + Lua 秒杀资格校验

使用 Redis Key `seckill:stock:{voucherId}` 和 `seckill:order:{voucherId}` 分别维护预扣库存和用户下单资格，通过 Lua 脚本把库存判断、一人一单判断、扣库存、写入用户 Set 合并为一次原子操作，避免接口层并发竞态。

对应代码和文档：

- `src/main/resources/seckill.lua`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `docs/seckill-design.md`

### RocketMQ 异步下单与削峰

秒杀接口在 Redis 资格校验成功后写入本地任务表，再同步投递 RocketMQ 消息。消费者异步扣减 MySQL 秒杀库存并创建订单，接口可快速返回订单 id，MySQL 作为最终事实承担库存和订单落库。

对应代码和文档：

- `src/main/java/com/hmdp/mq/VoucherOrderConsumer.java`
- `src/main/java/com/hmdp/dto/VoucherOrderMessage.java`
- `src/main/java/com/hmdp/utils/RocketMqConstants.java`
- `docs/seckill-design.md`

### 本地任务表补偿与人工处理

新增 `tb_voucher_order_task` 记录订单任务状态，覆盖 `PENDING`、`SENT`、`CONSUMED`、`FAILED`、`MANUAL_REVIEW`、`RESOLVED`。定时任务扫描失败或超时任务进行重投，超过最大重试次数后进入人工处理，避免无限重试。

对应代码和文档：

- `sql/20260512_add_voucher_order_task.sql`
- `src/main/java/com/hmdp/entity/VoucherOrderTask.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderTaskServiceImpl.java`
- `src/main/java/com/hmdp/mq/VoucherOrderTaskCompensator.java`
- `src/main/java/com/hmdp/controller/VoucherOrderTaskController.java`
- `docs/consistency.md`
- `docs/manual-review.md`

### 未支付订单超时关闭

订单创建后发送 RocketMQ 延迟消息，超时仍未支付则幂等关闭订单，并恢复 MySQL/Redis 库存和一人一单资格。额外使用 Spring Task 扫描超时订单，作为延迟消息不可用时的兜底。

对应代码和文档：

- `src/main/java/com/hmdp/mq/OrderTimeoutConsumer.java`
- `src/main/java/com/hmdp/mq/OrderTimeoutScanner.java`
- `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `docs/order-timeout.md`

### 商户缓存治理

商户详情接口使用 Redis 缓存，覆盖缓存穿透、击穿、雪崩和更新一致性问题：空值缓存防穿透，互斥锁重建防击穿，随机 TTL 降低雪崩概率，更新数据库后删除缓存并延迟双删。

对应代码和文档：

- `src/main/java/com/hmdp/utils/CacheClient.java`
- `src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`
- `src/main/java/com/hmdp/utils/RedisConstants.java`
- `docs/cache-design.md`

### 通用 AOP 限流

自定义 `@RateLimit` 注解，通过 Spring AOP 拦截接口方法，使用 Redis ZSet + Lua 实现滑动窗口限流。已接入验证码、秒杀下单和 AI 客服接口。

对应代码和文档：

- `src/main/java/com/hmdp/annotation/RateLimit.java`
- `src/main/java/com/hmdp/aspect/RateLimitAspect.java`
- `src/main/java/com/hmdp/controller/UserController.java`
- `src/main/java/com/hmdp/controller/VoucherOrderController.java`
- `src/main/java/com/hmdp/controller/AiCustomerServiceController.java`
- `docs/rate-limit.md`

### LangChain4j AI 客服

使用 LangChain4j 接入 OpenAI-compatible ChatModel，配置通过环境变量读取，不硬编码 API Key。AI 客服支持 Redis 会话记忆和 Function Calling 工具查询商户、优惠券数据；对用户原文命中的商户做预查询上下文兜底，降低模型参数抽取失败导致的误答。

对应代码和文档：

- `src/main/java/com/hmdp/controller/AiCustomerServiceController.java`
- `src/main/java/com/hmdp/service/impl/AiCustomerServiceImpl.java`
- `src/main/java/com/hmdp/service/ai/LocalLifeHubAiTools.java`
- `src/main/java/com/hmdp/service/ai/RedisChatMemoryStore.java`
- `docs/ai-customer-service.md`

## 面试时可以展开的问题

- Redis Lua 如何保证秒杀资格校验原子性，MySQL 为什么仍然需要库存条件更新和唯一索引兜底。
- Redis 成功、MQ 失败、DB 失败分别如何处理，任务表状态如何流转。
- 未支付订单关闭时为什么已支付订单不能释放 Redis 一人一单标记。
- 缓存更新为什么选择先更新数据库再删缓存，以及延迟双删仍有哪些边界。
- ZSet 滑动窗口限流相比固定窗口有什么优势。
- AI 客服为什么要使用工具查询系统数据，如何避免大模型编造业务数据。

## 不建议写的内容

- 不写“支持 xx 万 QPS”，当前没有压测报告支撑。
- 不写“性能提升 xx%”，当前没有基准对比数据。
- 不写“分布式事务强一致”，当前方案是本地任务表 + MQ 重试 + 幂等补偿的最终一致。
- 不写“完整支付系统”，当前只有最小支付状态接口用于验证订单关闭链路。
- 不写“完整 RAG 知识库”，当前 AI 客服只实现 Function Calling 和 Redis 会话记忆，RAG 是后续扩展方向。
