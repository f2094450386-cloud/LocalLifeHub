# 秒杀任务人工处理

## 背景

`tb_voucher_order_task` 的自动补偿会限制最大重试次数。任务达到 `max_retry` 后进入 `MANUAL_REVIEW`，系统不再自动重投，避免无限重试压垮 MQ 或数据库。

## 状态

- `MANUAL_REVIEW`：需要人工检查的任务。
- `SENT`：人工确认可以重投后，重新发送 MQ 成功。
- `RESOLVED`：人工确认 MySQL 订单不存在，且已经释放 Redis 资格。

`RESOLVED` 是人工处理闭环状态，不参与自动补偿扫描。

## 接口

查询人工处理任务：

```bash
curl -X GET "http://127.0.0.1:8081/voucher-order-task/manual-review?limit=50" \
  -H "authorization: <token>"
```

人工重投 MQ：

```bash
curl -X POST "http://127.0.0.1:8081/voucher-order-task/manual-review/{taskId}/retry" \
  -H "authorization: <token>"
```

人工释放 Redis 资格：

```bash
curl -X POST "http://127.0.0.1:8081/voucher-order-task/manual-review/{taskId}/release-redis" \
  -H "authorization: <token>"
```

释放 Redis 资格只允许 `MANUAL_REVIEW` 任务，并且会先检查 `tb_voucher_order` 中是否存在对应订单。订单存在时拒绝释放，避免误放已支付或未支付订单资格。释放成功后任务标记为 `RESOLVED`，重复调用不会再次恢复库存。

## 操作建议

1. 先查 `tb_voucher_order_task` 的 `fail_reason`、`retry_count`、`message_id`。
2. 如果 MySQL 已有订单，但任务未 `CONSUMED`，优先执行人工重投，让消费者幂等修正任务状态。
3. 如果 MySQL 没有订单，且确认该用户可重新抢购，可以执行释放 Redis 资格。
4. 对频繁进入 `MANUAL_REVIEW` 的任务，需要进一步查看 RocketMQ Broker、消费者日志和 MySQL 异常。
