# 未支付订单超时自动关闭

## 订单状态

`tb_voucher_order.status` 已能表达本轮需要的状态：

- `1`：`CREATED`，未支付。
- `2`：`PAID`，已支付。
- `4`：`CLOSED`，已关闭。

本轮不新增订单状态字段。为了让已关闭订单不再占用用户购买资格，新增迁移脚本：

```text
sql/20260512_adjust_voucher_order_active_unique_index.sql
```

该脚本把原 `user_id + voucher_id` 唯一索引调整为 `user_id + active_voucher_id`。`active_voucher_id` 是 MySQL 8 生成列，订单状态不是 `CLOSED(4)` 时取 `voucher_id`，关闭后为 `NULL`，从而允许用户在订单关闭后重新抢同一张券。

## 状态流转

```text
CREATED -> PAID
CREATED -> CLOSED
```

- `CREATED -> PAID`：支付链路后续实现时，只允许未支付订单更新为已支付。
- `CREATED -> CLOSED`：RocketMQ 延迟消息或 Spring Task 兜底扫描发现订单仍未支付时关闭。

## 支付接口

当前实现了最小支付状态接口，用于闭环演示和本地验证：

```bash
curl -X POST "http://127.0.0.1:8081/voucher-order/pay/{orderId}" \
  -H "authorization: <token>"
```

接口只允许订单所属用户支付自己的订单。只有 `CREATED(1)` 可以更新为 `PAID(2)`，更新时写入 `pay_time`。如果延迟消息稍后到达，看到订单已经是 `PAID` 会幂等跳过，不会恢复库存，也不会释放 Redis 一人一单标记。

## RocketMQ 延迟消息

秒杀订单成功落库后发送延迟消息到：

```text
local-lifehub.voucher-order-timeout
```

本地 RocketMQ 4.9.7 使用延迟等级，`docker/rocketmq/broker.conf` 配置了：

```text
messageDelayLevel=1s 5s 10s 30s 1m 2m 3m 4m 5m 10m 15m 20m 30m 1h 2h
```

默认 `SECKILL_ORDER_TIMEOUT_DELAY_LEVEL=11`，对应 15 分钟。修改该配置后需要重启 Broker。

## 延迟消息消费逻辑

消费者收到 `OrderTimeoutMessage` 后：

1. 查询 `tb_voucher_order`。
2. 如果订单不存在，记录日志并返回。
3. 如果状态不是 `CREATED(1)`，说明已支付、已关闭或进入其他状态，直接幂等返回。
4. 使用 `where id = ? and status = 1` 把订单更新为 `CLOSED(4)`。
5. 只有第 4 步更新成功的那一次，才恢复 `tb_seckill_voucher.stock`。
6. 删除 Redis 一人一单标记 `seckill:order:{voucherId}` 中的用户，并递增 `seckill:stock:{voucherId}`。

已支付订单不会释放 Redis 一人一单标记，也不会恢复库存。

## Spring Task 兜底扫描

如果本地 RocketMQ 延迟消息不可用、Broker 未配置 15 分钟延迟等级，或者延迟消息丢失，`OrderTimeoutScanner` 会定时扫描超时未支付订单：

- `SECKILL_ORDER_TIMEOUT_MINUTES`：超时时间，默认 15。
- `SECKILL_ORDER_TIMEOUT_SCAN_INTERVAL_MS`：扫描间隔，默认 60000。
- `SECKILL_ORDER_TIMEOUT_SCAN_LIMIT`：单次扫描数量，默认 100。

兜底扫描调用同一个 `closeUnpaidOrder` 方法，幂等逻辑与延迟消息消费一致。

## 验证建议

1. 执行迁移脚本：

```powershell
Get-Content .\sql\20260512_adjust_voucher_order_active_unique_index.sql | docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp
```

2. 本地调试可临时缩短延迟等级，例如设置 `SECKILL_ORDER_TIMEOUT_DELAY_LEVEL=1`，并把扫描分钟数调小：

```powershell
$env:SECKILL_ORDER_TIMEOUT_DELAY_LEVEL="1"
$env:SECKILL_ORDER_TIMEOUT_MINUTES="1"
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

3. 调用 `POST /voucher-order/seckill/{id}` 后观察：

- `tb_voucher_order` 先生成 `status=1` 的订单。
- 延迟消息或兜底扫描触发后，未支付订单变为 `status=4`。
- `tb_seckill_voucher.stock` 恢复 1。
- Redis `seckill:order:{voucherId}` 中移除该用户。

4. 在超时前调用 `POST /voucher-order/pay/{orderId}` 后观察：

- `tb_voucher_order.status` 从 `1` 变为 `2`。
- 延迟消息或兜底扫描不会关闭该订单。
