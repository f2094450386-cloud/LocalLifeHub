# 秒杀链路设计

## 当前主线

当前 LocalLifeHub 的优惠券秒杀链路已经从普通 hmdp 的 Redis Stream 方案演进为：

```text
前端点击抢券
-> POST /voucher-order/seckill/{voucherId}
-> Controller 进入 VoucherOrderServiceImpl#seckillVoucher
-> Redis Lua 原子校验库存和一人一单
-> 写入 tb_voucher_order_task 本地任务表
-> 同步投递 RocketMQ
-> 消费者异步扣减 MySQL 库存并创建订单
-> 创建成功后发送未支付订单超时检查延迟消息
-> RocketMQ 延迟消息或 Spring Task 兜底扫描关闭超时未支付订单
```

接口语义保持为：抢购资格获取成功后，接口立即返回订单 ID；最终订单是否落库，以 MySQL 和任务表状态为准。

## 为什么 Redis 扣库存

秒杀请求集中在同一个券 ID 上，如果每个请求都直接访问 MySQL，`tb_seckill_voucher` 的库存行会成为高并发写热点。

Redis 单线程执行命令，吞吐更高，适合先做轻量级的资格判断和库存预扣。

当前 Redis Key：

- `seckill:stock:{voucherId}`：秒杀券 Redis 预扣库存，String。
- `seckill:order:{voucherId}`：已抢到该券资格的用户 ID 集合，Set。

Redis 预扣成功只代表用户拿到了下单资格，不代表订单已经最终落库。

## Lua 原子校验

库存判断、重复下单判断、扣库存、记录用户已下单必须作为一个不可拆分的整体执行。如果拆成多条 Redis 命令，多个请求可能在判断后同时通过，导致超卖或重复下单。

脚本位置：

```text
src/main/resources/seckill.lua
```

返回码：

- `0`：成功获得下单资格。
- `1`：库存不存在或库存不足。
- `2`：同一用户重复下单。

## 本地任务表

Redis 预扣成功后，接口先写入本地任务表：

```text
tb_voucher_order_task
```

对应脚本：

```text
sql/20260512_add_voucher_order_task.sql
```

任务表记录 Redis 预扣成功后的后续状态，让“Redis 成功、MQ 失败、消费者失败、补偿失败”这些中间状态可查询、可补偿、可人工处理。

核心状态：

- `PENDING`：Redis 预扣成功，任务已创建，MQ 尚未确认投递成功。
- `SENT`：RocketMQ 同步投递返回 `SEND_OK`。
- `CONSUMED`：消费者已成功扣减 MySQL 库存并创建订单。
- `FAILED`：发送或消费失败，等待补偿扫描。
- `MANUAL_REVIEW`：重试达到上限，进入人工处理。
- `RESOLVED`：人工确认并完成处理。

## RocketMQ 异步下单

Topic 定义在：

```text
src/main/java/com/hmdp/utils/RocketMqConstants.java
```

当前 Topic：

```text
local-lifehub.voucher-order
```

生产入口：

```text
VoucherOrderServiceImpl#seckillVoucher
```

消费入口：

```text
VoucherOrderConsumer#onMessage
```

消息体：

```text
VoucherOrderMessage
```

包含：

- `taskId`
- `orderId`
- `userId`
- `voucherId`

## MySQL 兜底

消费者真正写 MySQL 时仍然需要两层兜底：

- 业务幂等：按 `orderId` 或 `userId + voucherId + 非 CLOSED 状态` 判断订单是否已存在。
- 库存保护：扣减 `tb_seckill_voucher.stock` 时带 `stock > 0` 条件。

订单唯一约束已经调整为：

```text
user_id + active_voucher_id
```

其中 `active_voucher_id` 是 MySQL 8 生成列。订单不是 `CLOSED(4)` 时取 `voucher_id`，关闭后为 `NULL`，从而允许用户在未支付订单关闭后重新抢同一张券。

对应脚本：

```text
sql/20260512_adjust_voucher_order_active_unique_index.sql
```

## Redis 成功但 MQ 失败

`RocketMQTemplate.syncSend` 发送失败或返回非 `SEND_OK` 时：

1. 任务表标记为 `FAILED`。
2. 立即回滚 Redis 预扣库存：`seckill:stock:{voucherId}` 加 1。
3. 从 `seckill:order:{voucherId}` 移除当前用户。
4. 接口返回“订单排队失败，请稍后重试”。

这覆盖的是“MQ 当场发送失败”的窗口。

## MQ 成功但消费失败

消费者处理失败时：

1. 任务表标记为 `FAILED`。
2. 增加重试次数。
3. 抛出异常交给 RocketMQ 自身重试。
4. 定时补偿任务也会扫描 `PENDING`、`SENT`、`FAILED` 且到达 `next_retry_time` 的任务重新投递。

补偿任务：

```text
VoucherOrderTaskCompensator
```

达到最大重试次数后，任务进入 `MANUAL_REVIEW`，避免无限重试。

## 未支付订单超时关闭

订单创建成功后会发送延迟消息到：

```text
local-lifehub.voucher-order-timeout
```

处理入口：

```text
OrderTimeoutConsumer#onMessage
VoucherOrderServiceImpl#closeUnpaidOrder
```

兜底扫描：

```text
OrderTimeoutScanner#scanExpiredOrders
```

关闭逻辑只允许：

```text
CREATED(1) -> CLOSED(4)
```

只有状态条件更新成功的那一次，才恢复 MySQL 库存、递增 Redis 秒杀库存，并从 Redis Set 中移除用户下单资格。已支付订单不会释放 Redis 一人一单标记。

## 面试表达

这个项目的秒杀入口没有直接写 MySQL，而是先用 Redis Lua 原子完成库存预扣和一人一单校验，避免接口层并发竞态。Redis 校验成功后，系统写入本地任务表记录订单任务状态，再同步投递 RocketMQ，由消费者异步扣减 MySQL 库存并创建订单。MySQL 层仍然使用 `stock > 0` 条件更新和唯一索引兜底，防止消息重复或并发情况下出现超卖和重复订单。对于 Redis 成功但 MQ 或 DB 失败的情况，项目通过任务表记录 PENDING、SENT、FAILED、CONSUMED、MANUAL_REVIEW 等状态，并由定时任务补偿重投，超过重试上限后转人工处理。未支付订单通过 RocketMQ 延迟消息和 Spring Task 兜底扫描幂等关闭，并恢复库存和 Redis 下单资格。

## 当前边界

- 当前不是分布式事务强一致方案，而是 Redis 预扣、本地任务表、MQ 重试、幂等落库和补偿的最终一致方案。
- 当前没有真实支付网关，`/voucher-order/pay/{id}` 只是最小支付状态接口，用于验证支付与关单并发。
- 当前没有压测报告，不应在简历中写 QPS 或性能提升百分比。
