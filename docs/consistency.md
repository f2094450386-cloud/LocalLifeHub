# 秒杀订单一致性补偿说明

## Redis、MQ、MySQL 的职责

- Redis：承接秒杀入口的高并发资格判断。`seckill:stock:{voucherId}` 负责预扣库存，`seckill:order:{voucherId}` 负责一人一单资格记录。Redis 成功只代表用户拿到下单资格，不代表最终订单已经落库。
- RocketMQ：把接口线程和 MySQL 写库解耦。接口在 Redis 和本地任务表成功后投递 `local-lifehub.voucher-order`，消费者异步扣减 MySQL 库存并创建订单。
- MySQL：承担最终事实。`tb_seckill_voucher.stock > 0` 条件更新防止超卖，`tb_voucher_order` 的 `user_id + voucher_id` 唯一索引保证同一用户同一券只有一笔订单。

## 为什么需要 tb_voucher_order_task

Redis 预扣成功后，如果 MQ 发送失败、应用重启、消费者写库失败，单靠 Redis Set 和 MQ 重试很难说明一笔订单当前处于什么状态。`tb_voucher_order_task` 作为本地任务/outbox 表，记录每个 `orderId` 的投递、消费、失败和补偿次数，让链路有可查询、可补偿、可转人工的闭环。

## 状态流转

- `PENDING`：Lua 成功后，接口先写本地任务表，表示 Redis 已经预扣但 MQ 还未确认发送成功。
- `SENT`：RocketMQ 同步发送返回 `SEND_OK`，记录 `message_id` 和 `last_send_time`。
- `CONSUMED`：消费者成功执行 MySQL 乐观扣库存并创建订单后标记完成。重复消息看到该状态直接返回。
- `FAILED`：MQ 发送失败、消费者创建订单失败或补偿投递失败时记录失败原因，并设置下一次补偿时间。
- `MANUAL_REVIEW`：补偿次数达到 `max_retry` 后进入人工处理，不再无限重试。
- `RESOLVED`：人工确认订单不存在并释放 Redis 资格后标记完成，不再进入自动补偿。

## MQ 发送失败如何处理

接口端在 MQ 发送失败或返回非 `SEND_OK` 时：

1. 将任务标记为 `FAILED`，写入失败原因。
2. 立即回滚 Redis 预扣：`seckill:stock:{voucherId}` 加 1，并从 `seckill:order:{voucherId}` 移除用户。
3. 接口返回失败，用户可以稍后重试。

这一步只覆盖“发送 MQ 当场失败”的窗口。失败任务仍保留在任务表中，便于排查；补偿任务也会按 `next_retry_time` 尝试重新投递。由于 MySQL 仍有库存乐观扣减和唯一索引兜底，即使用户重试和补偿投递并发发生，也不会造成 MySQL 超卖或同一用户重复订单。

## 消费失败如何处理

消费者收到消息后先查询 `tb_voucher_order_task`：

- 任务不存在：记录错误日志并抛出异常，交给 RocketMQ 重试，不静默丢消息。
- 任务已 `CONSUMED`：说明是重复消息，直接幂等返回。
- 任务未完成：调用订单服务扣减 MySQL 库存并创建 `tb_voucher_order`。

如果 MySQL 库存不足、唯一索引冲突或保存失败，消费者会把任务标记为 `FAILED`、增加 `retry_count`、记录失败原因，然后继续抛出异常，让 RocketMQ 自身重试机制继续工作。

## 补偿任务如何避免无限重试

应用通过 `@EnableScheduling` 启用定时任务，扫描 `PENDING` / `FAILED` 且 `next_retry_time` 到期的记录：

- `retry_count < max_retry`：重新投递 RocketMQ，发送成功标记 `SENT`，发送失败标记 `FAILED` 并增加重试次数。
- `retry_count >= max_retry`：标记 `MANUAL_REVIEW`，不再自动投递。

补偿参数可通过环境变量调整：

- `SECKILL_TASK_COMPENSATE_INTERVAL_MS`：扫描间隔，默认 30000。
- `SECKILL_TASK_COMPENSATE_LIMIT`：单次扫描数量，默认 50。
- `SECKILL_TASK_RETRY_DELAY_SECONDS`：失败后下一次补偿延迟，默认 60。
- `SECKILL_TASK_DEFAULT_MAX_RETRY`：新任务默认最大重试次数，默认 5。

## 当前边界和后续改进

- 补偿扫描已覆盖 `PENDING`、`FAILED` 和超时未完成的 `SENT`。补偿重新投递成功也会增加 `retry_count`，达到 `max_retry` 后进入 `MANUAL_REVIEW`，避免无限重投。
- MQ 发送失败会立即释放 Redis 资格；未支付订单关闭时也会恢复 MySQL 库存并释放 Redis 资格。补偿达到上限转人工前，如果确认 MySQL 订单不存在，也会释放 Redis 预扣资格；如果订单已存在则不释放，避免误放已支付或未支付订单资格。
- 当前仍是单库闭环，不引入 Seata、分库分表或事务消息。后续如果需要更强一致性，可以继续补充 RocketMQ 死信队列告警、人工修复后台和 Redis/MySQL 定期对账报表。

人工处理接口见 `docs/manual-review.md`。
