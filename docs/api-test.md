# 核心接口本地验证

本文档使用本地 `local` profile 默认环境：

- 应用：`http://127.0.0.1:8081`
- MySQL：`127.0.0.1:3307`，`root / locallifehub_root`
- Redis：`127.0.0.1:6380`，密码 `locallifehub_redis`

PowerShell 建议先切到 UTF-8，避免中文响应显示乱码：

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
```

## 1. 登录/验证码

发送验证码：

```powershell
curl.exe -X POST "http://127.0.0.1:8081/user/code?phone=13800138000"
```

本地验证可从 Redis 读取验证码：

```powershell
docker exec -i locallifehub-redis redis-cli -a locallifehub_redis GET login:code:13800138000
```

登录并保存 token：

```powershell
$loginBody = @{
  phone = "13800138000"
  code = "替换为Redis中的验证码"
} | ConvertTo-Json

$login = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/user/login" `
  -ContentType "application/json; charset=utf-8" `
  -Body $loginBody

$token = $login.data
$token
```

查看当前用户：

```powershell
Invoke-RestMethod `
  -Method Get `
  -Uri "http://127.0.0.1:8081/user/me" `
  -Headers @{ authorization = $token }
```

## 2. 商户查询

按 id 查询商户详情，走 Redis 缓存：

```powershell
Invoke-RestMethod `
  -Method Get `
  -Uri "http://127.0.0.1:8081/shop/1"
```

按名称关键词查询：

```powershell
Invoke-RestMethod `
  -Method Get `
  -Uri "http://127.0.0.1:8081/shop/of/name?name=103茶餐厅&current=1"
```

热点商户缓存预热：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/shop/1/cache/preheat" `
  -Headers @{ authorization = $token }
```

## 3. 秒杀下单

秒杀下单需要登录 token，接口会先执行 Redis Lua 原子校验，再写本地任务表并投递 RocketMQ：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/voucher-order/seckill/1" `
  -Headers @{ authorization = $token }
```

成功时 `data` 为订单 id。保存订单 id：

```powershell
$seckill = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/voucher-order/seckill/1" `
  -Headers @{ authorization = $token }

$orderId = $seckill.data
$orderId
```

查询订单是否落库：

```powershell
docker exec -i locallifehub-mysql mysql -uroot -plocallifehub_root hmdp `
  -e "SELECT id,user_id,voucher_id,status,create_time,pay_time FROM tb_voucher_order WHERE id = $orderId;"
```

查询本地任务表状态：

```powershell
docker exec -i locallifehub-mysql mysql -uroot -plocallifehub_root hmdp `
  -e "SELECT order_id,status,retry_count,message_id,fail_reason FROM tb_voucher_order_task WHERE order_id = $orderId;"
```

## 4. 订单关闭

当前没有对外暴露“手动关闭订单”接口。未支付订单关闭由两条链路触发：

- RocketMQ 延迟消息：`local-lifehub.voucher-order-timeout`
- Spring Task 兜底扫描：`OrderTimeoutScanner`

本地快速验证建议临时缩短配置后重启应用：

```powershell
$env:SECKILL_ORDER_TIMEOUT_MINUTES="1"
$env:SECKILL_ORDER_TIMEOUT_DELAY_LEVEL="1"
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

下单后等待 1 分钟左右，查询订单状态：

```powershell
docker exec -i locallifehub-mysql mysql -uroot -plocallifehub_root hmdp `
  -e "SELECT id,status,create_time,pay_time,update_time FROM tb_voucher_order WHERE id = $orderId;"
```

期望状态：

- `status = 1`：未支付，等待关闭。
- `status = 4`：已关闭，系统已恢复 MySQL/Redis 库存并释放一人一单资格。

支付接口用于验证“已支付订单不会被关闭”：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/voucher-order/pay/$orderId" `
  -Headers @{ authorization = $token }
```

## 5. AI 客服

启动应用前配置阿里云百炼 OpenAI-compatible 环境变量，不要写入代码：

```powershell
$env:LOCAL_LIFEHUB_LLM_API_KEY="你的API Key"
$env:LOCAL_LIFEHUB_LLM_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:LOCAL_LIFEHUB_LLM_MODEL="qwen-plus"
```

商户查询：

```powershell
$body = @{
  message = "帮我查一下 103茶餐厅 的商户信息"
  sessionId = "demo-session-001"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/ai/customer-service/chat" `
  -Headers @{ authorization = $token } `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

优惠券查询：

```powershell
$body = @{
  message = "103茶餐厅 有什么优惠券？"
  sessionId = "demo-session-001"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/ai/customer-service/chat" `
  -Headers @{ authorization = $token } `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

Redis 会话记忆验证：

```powershell
$body = @{
  message = "它的地址在哪里？"
  sessionId = "demo-session-001"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/ai/customer-service/chat" `
  -Headers @{ authorization = $token } `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

查看会话 Key：

```powershell
docker exec -i locallifehub-redis redis-cli -a locallifehub_redis keys "ai:customer-service:memory:*"
```

## 6. 限流验证

验证码限流：60 秒内同一手机号只能请求 1 次。

```powershell
curl.exe -X POST "http://127.0.0.1:8081/user/code?phone=13800138000"
curl.exe -X POST "http://127.0.0.1:8081/user/code?phone=13800138000"
```

第二次期望返回：

```json
{"success":false,"errorMsg":"验证码发送过于频繁，请稍后再试"}
```

秒杀限流：同一用户同一券 1 秒内只能请求 1 次。

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/voucher-order/seckill/1" -Headers @{ authorization = $token }
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8081/voucher-order/seckill/1" -Headers @{ authorization = $token }
```

AI 客服限流：同一用户 60 秒内最多 20 次。

```powershell
1..21 | ForEach-Object {
  $body = @{ message = "查一下商户信息"; sessionId = "limit-test" } | ConvertTo-Json
  Invoke-RestMethod `
    -Method Post `
    -Uri "http://127.0.0.1:8081/ai/customer-service/chat" `
    -Headers @{ authorization = $token } `
    -ContentType "application/json; charset=utf-8" `
    -Body $body
}
```

超过阈值时期望返回：

```json
{"success":false,"errorMsg":"AI客服请求过于频繁，请稍后再试"}
```
