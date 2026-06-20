# LocalLifeHub 面试主线精简稿

> 用途：这份文档只保留面试中最该讲、最容易被追问、最能代表当前项目客观情况的内容。不要把完整学习手册里的所有细节都背下来。

## 一句话项目描述

LocalLifeHub 是一个本地生活服务后端项目，包含用户登录、商户查询、探店互动、优惠券秒杀、订单超时关闭、接口限流和 AI 客服等功能。项目重点解决秒杀资格校验、异步下单、最终一致补偿、Redis 缓存治理和业务数据智能查询问题。

简历上可以这样写：

> 本地生活服务平台后端项目，支持用户登录、商户查询、探店互动、优惠券秒杀、订单超时关闭和 AI 客服等功能。秒杀链路使用 Redis Lua 完成活动时间、库存和一人一单校验，结合本地任务表与 RocketMQ 异步落库，并通过任务状态流转、定时补偿和人工处理保证最终一致；商户查询使用 Redis 缓存治理；AI 客服基于 LangChain4j、Redis 会话记忆和 Function Calling 查询系统业务数据。

## 面试只背这五件事

1. 秒杀入口：Redis Lua 用 Redis `TIME` 原子校验活动时间、库存和一人一单，成功后预扣 Redis 库存并记录用户资格。
2. 异步下单：Redis 成功后写 `tb_voucher_order_task`，再发送 RocketMQ，由消费者异步扣 MySQL 库存并创建订单。
3. 一致性补偿：任务表用 `PROCESSING / CONSUMING / RELEASING` 做 CAS 抢占，避免补偿、消费、人工处理互相覆盖。
4. 超时关单：订单创建后发送 RocketMQ 延迟消息；Spring Task 兜底扫描；`redis_released` 记录关单后的 Redis 释放进度。
5. 缓存和 AI：商户详情用空值缓存、随机 TTL、互斥锁、逻辑过期、延迟双删；AI 客服用 LangChain4j、Redis 会话记忆和 Function Calling 查询真实业务数据。

## 总链路

```text
用户抢券
-> POST /voucher-order/seckill/{voucherId}
-> Redis Lua 校验活动时间、库存、一人一单
-> Redis 预扣库存 + 写入用户资格 Set
-> 写 tb_voucher_order_task
-> RocketMQ local-lifehub.voucher-order
-> VoucherOrderConsumer 消费消息
-> MySQL 条件扣库存 + 创建 tb_voucher_order
-> 发送订单超时关闭延迟消息
-> 未支付则 CLOSED，恢复 MySQL 库存并释放 Redis 资格
```

面试表达：

> 这条链路不是追求分布式强一致，而是把 Redis、MQ、MySQL 的状态都变成可追踪、可补偿。Redis 先挡住高并发资格判断，MySQL 作为最终事实，RocketMQ 用来削峰和解耦，任务表负责记录中间状态并驱动补偿。

## 秒杀入口怎么讲

入口接口：

```text
POST /voucher-order/seckill/{voucherId}
```

核心判断：

- 活动是否存在，Redis 中是否有 `seckill:begin:{voucherId}` 和 `seckill:end:{voucherId}`。
- 使用 Redis `TIME` 判断是否开始或结束，避免多台应用服务器时间不一致。
- `seckill:stock:{voucherId}` 是否大于 0。
- `seckill:order:{voucherId}` Set 中是否已有当前用户。

成功后：

- `INCRBY seckill:stock:{voucherId} -1`
- `SADD seckill:order:{voucherId} userId`
- 生成 `orderId`
- 写 `tb_voucher_order_task`
- 发送 RocketMQ

常见追问：

**Q：Redis 已经扣库存了，MySQL 为什么还要 `stock > 0`？**

A：Redis 是入口资格层，MySQL 是最终事实层。Redis 可以拦住大部分并发，但消费者重复消息、补偿重投或数据异常仍可能到 MySQL，所以 MySQL 必须用 `stock > 0` 条件更新兜底防超卖。

**Q：一人一单为什么 Redis 和 MySQL 都要做？**

A：Redis Set 是快速资格判断；MySQL 的 `user_id + active_voucher_id` 唯一索引是最终兜底，防止重复消息或异常补偿导致同一用户有多笔未关闭订单。

## RocketMQ 和任务表怎么讲

任务表：`tb_voucher_order_task`

核心状态：

```text
PENDING      刚写任务，还没确认 MQ
SENT         MQ 返回 SEND_OK
PROCESSING   补偿任务抢占发送权
CONSUMING    消费者抢占落库权
RELEASING    正在释放 Redis 资格，阻止再消费
CONSUMED     订单已成功落库
FAILED       发送或消费失败，等待补偿
MANUAL_REVIEW 自动处理失败，需要人工判断
RESOLVED     确认订单不存在且资格已释放
```

面试表达：

> 我没有只依赖 MQ 的重试，而是加了一张本地任务表。它记录 Redis 预扣成功后的中间状态，配合定时补偿和人工处理，让“Redis 成功但 MQ 或 MySQL 失败”的情况可查询、可重试、可释放。

常见追问：

**Q：`syncSend` 抛异常能不能直接释放 Redis？**

A：不能。异常只代表客户端没拿到确认，Broker 可能已经收到并持久化消息。如果这时释放 Redis，后面迟到消息仍可能创建订单，导致资格和订单不一致。当前做法是保留任务和 Redis 资格，返回 `orderId`，让状态查询和补偿任务收敛。

**Q：多实例补偿会不会重复发消息？**

A：补偿前先用数据库条件更新把任务从旧状态抢成 `PROCESSING`，只有抢占成功的实例才能发送 MQ。消费者落库前也要抢 `CONSUMING`，释放资格前要抢 `RELEASING`。

## 消费者落库怎么讲

消费者收到 `VoucherOrderMessage` 后：

1. 查询任务表。
2. 已 `CONSUMED` 直接跳过。
3. 已 `RELEASING / RESOLVED / MANUAL_REVIEW` 不再自动创建订单。
4. CAS 抢占为 `CONSUMING`。
5. MySQL 中检查重复订单。
6. `tb_seckill_voucher.stock > 0` 条件扣库存。
7. 写 `tb_voucher_order`。
8. 标记任务为 `CONSUMED`。
9. 事务提交后发送超时关单延迟消息。

面试表达：

> 消费者必须幂等，因为 RocketMQ 可能重复投递，补偿也可能重发消息。我的幂等手段包括任务状态、订单 id 检查、用户券唯一索引和 MySQL 条件扣库存。

## 超时关单怎么讲

当前订单状态：

```text
CREATED -> PAID
CREATED -> CLOSED
```

关单触发：

- RocketMQ 延迟消息到期检查。
- Spring Task 定时扫描兜底。

关单逻辑：

1. 查订单。
2. `PAID` 直接跳过。
3. `CLOSED` 但 `redis_released=0`，继续补 Redis 释放。
4. `CREATED` 用 `where id = ? and status = CREATED` 更新为 `CLOSED`。
5. 成功关闭的一次恢复 MySQL 库存。
6. 事务提交后执行 Redis Lua 释放用户资格。
7. 成功后更新 `redis_released=1`。

面试表达：

> 关单和支付是并发竞争关系，所以都用 `status=CREATED` 条件更新。谁先更新成功，另一个就失败并幂等返回。Redis 释放不放在 MySQL 事务中间，而是在事务提交后执行，并用 `redis_released` 持久化补偿进度。

## 商户缓存怎么讲

商户详情：

```text
GET /shop/{id}
```

缓存策略：

- 空值缓存：防止缓存穿透。
- 随机 TTL：降低同一时间大量 Key 过期导致缓存雪崩。
- UUID token + Lua 解锁：防止误删其他线程的锁。
- 有限等待：拿不到锁时有限循环重查缓存，不无限递归。
- 逻辑过期：热点商户过期后先返回旧值，再异步重建。
- 更新后删缓存 + 延迟双删：降低并发读写下旧值回写概率。

面试表达：

> 商户查询是读多写少场景。普通商户用互斥锁重建，热点商户用逻辑过期保证高并发下低延迟。更新商户时以 MySQL 为准，事务提交后删除缓存，并做一次延迟双删。

边界要主动说明：

- 延迟双删不是强一致。
- 逻辑过期会短时间返回旧值。
- 当前延迟双删还是进程内最佳努力任务。

## 限流怎么讲

实现：

- 自定义 `@RateLimit`。
- AOP 拦截方法。
- Redis ZSet 保存请求时间戳。
- Lua 原子删除窗口外请求、统计窗口内请求数、写入当前请求。

已接入：

- 发送验证码。
- 登录尝试。
- 秒杀下单。
- AI 客服同步和流式接口。

面试表达：

> 固定窗口可能在窗口边界产生双倍流量，滑动窗口用时间戳统计最近一段时间内的真实请求数，更平滑。Redis Lua 保证统计和写入原子执行。

## AI 客服怎么讲

接口：

```text
POST /ai/customer-service/chat
POST /ai/customer-service/chat/stream
```

能力：

- LangChain4j 接入 OpenAI-compatible 模型。
- 支持 DeepSeek / Qwen / MiMo 配置切换。
- Redis 保存会话记忆。
- Function Calling 查询商户和优惠券数据。
- 关键词知识库提供平台规则、秒杀规则、退款规则、商户入驻说明。
- SSE 流式输出。
- 同步路径记录 [REQ]/[TOOL]/[RESP] 审计日志；流式路径当前没有工具调用审计。

面试表达：

> AI 客服不是让模型凭空回答业务数据，而是通过 Function Calling 调用后端只读工具查询真实商户和优惠券数据。模型负责组织语言，数据来源仍然是系统数据库。

边界要主动说明：

- 不是完整向量 RAG。
- 没有订单查询工具，所以不能查用户订单。
- 系统提示词不是安全边界，真正边界是工具能力、参数校验和字段过滤。

## 当前项目不能夸大的点

不要写：

- 支持多少 QPS。
- 性能提升多少百分比。
- 完整支付系统。
- 完整 RBAC。
- 标准 Outbox。
- 分布式事务。
- 完整向量 RAG。
- 全链路 AI 审计。
- 文件病毒扫描或内容审核平台。

可以写：

- 面向高并发秒杀场景设计 Redis Lua 资格校验。
- 使用 RocketMQ 异步落库削峰。
- 使用本地任务表、状态 CAS、定时补偿和人工处理保证最终一致。
- 使用 RocketMQ 延迟消息和 Spring Task 做未支付订单超时关闭。
- 使用 Redis 缓存治理提高商户查询稳定性。
- 使用 LangChain4j Function Calling 查询真实业务数据。

## 一分钟口述版本

> LocalLifeHub 是一个本地生活服务后端项目，包含用户登录、商户查询、探店互动、优惠券秒杀、订单超时关闭和 AI 客服。秒杀入口用 Redis Lua 基于 Redis 时间原子校验活动时间、库存和一人一单，成功后写本地任务表并发送 RocketMQ，由消费者异步扣 MySQL 库存和创建订单。任务表通过 `PROCESSING / CONSUMING / RELEASING` 状态 CAS 控制补偿、消费和释放，发送结果不确定时不立即释放资格，而是返回订单 ID，由状态查询和补偿任务收敛。未支付订单通过 RocketMQ 延迟消息和 Spring Task 关闭，并用 `redis_released` 持久化 Redis 释放进度。商户查询做了空值缓存、随机 TTL、互斥锁、逻辑过期和延迟双删。AI 客服基于 LangChain4j、Redis 会话记忆和 Function Calling 查询真实商户和优惠券数据。

## 五个必会追问

### 1. Redis 成功后应用宕机怎么办？

当前任务表写入前仍有孤儿资格窗口，这是项目剩余边界。已经覆盖的是任务表写入后的 MQ 发送、消费、关单和释放补偿。可以继续通过资格事件表、Redis pending 资格扫描或更标准的消息事务方案增强。

### 2. MQ 发送异常为什么返回订单 ID？

因为异常不等于 Broker 没收到。返回订单 ID 后，前端查询状态；后端任务表和补偿器继续收敛，避免误释放 Redis 资格导致重复下单。

### 3. 任务表是不是标准 Outbox？

不是。它思想上类似 Outbox，用来记录中间状态和补偿，但当前业务事实不是和事件在同一个 MySQL 事务里提交，因为 Redis 资格校验发生在前面。

### 4. `redis_released` 和 Redis release key 有什么区别？

Redis release key 是 Lua 幂等标记，防止短期重复释放。`redis_released` 是 MySQL 持久化进度，防止应用在关单提交后、Redis 释放前宕机导致永远没有补偿线索。

### 5. AI 客服怎么防止编造数据？

商户和优惠券数据必须通过 Function Calling 工具查询数据库，工具查不到就返回“未查询到相关数据”。模型不能直接编造订单、手机号或支付信息，因为没有对应工具能力。

