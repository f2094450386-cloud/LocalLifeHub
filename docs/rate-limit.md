# 通用接口限流设计

## 接入方式

新增注解：

```java
@RateLimit(
    key = "'user:code:' + #p0",
    windowSeconds = 60,
    maxRequests = 1,
    message = "验证码发送过于频繁，请稍后再试"
)
```

参数说明：

- `key`：业务维度，支持 SpEL。可以使用 `#p0` / `#a0` 访问第一个参数，也可以访问方法参数名。
- `windowSeconds`：滑动窗口秒数。
- `maxRequests`：窗口内最大请求数。
- `message`：超过限流后返回给前端的提示。

当前已接入：

- `POST /voucher-order/seckill/{id}`：按“券 id + 用户 id”限流，1 秒 1 次。
- `POST /user/code`：按手机号限流，60 秒 1 次。
- `POST /ai/customer-service/chat`：按用户 id 限流，60 秒 20 次。

## 为什么不用简单固定窗口

固定窗口通常使用 `INCR + EXPIRE`，例如每分钟最多 10 次。问题是窗口边界会放大流量：用户可以在上一分钟最后 1 秒请求 10 次，再在下一分钟第 1 秒请求 10 次，实际 2 秒内打出 20 次。

本项目使用 Redis ZSet 滑动窗口：

1. 删除窗口外请求记录。
2. 统计窗口内请求数量。
3. 未超过阈值时写入当前请求。
4. 超过阈值时拒绝。

这样限制的是“任意连续 windowSeconds 时间内”的请求数量，更适合秒杀、验证码、AI 客服这类高频接口。

## Redis Key 设计

统一前缀：

```text
rate-limit:
```

完整 Key：

```text
rate-limit:{business}:{dimension}
```

示例：

```text
rate-limit:user:code:13800138000
rate-limit:voucher:seckill:10:101
```

ZSet 结构：

- `score`：当前时间戳毫秒。
- `member`：`timestamp + UUID`，保证同一毫秒内多个请求不会互相覆盖。
- TTL：`windowSeconds + 1`，窗口无请求后自动释放 Redis Key。

## Lua 原子性

限流脚本在 Redis 内原子执行，避免多实例并发时先查数量、再写记录之间出现竞态。

伪流程：

```text
ZREMRANGEBYSCORE key 0 now-window
count = ZCARD key
if count >= maxRequests then reject
ZADD key now member
EXPIRE key window+1
allow
```

## 返回语义

限流由 Spring AOP 拦截带 `@RateLimit` 的方法。超过限制时返回：

```java
Result.fail(message)
```

不会直接抛出未处理异常。Redis 执行异常时当前策略是失败关闭，返回同样的限流提示，避免 Redis 异常期间高频接口直接冲击后端服务。

AI 客服配置和验证见 `docs/ai-customer-service.md`。
