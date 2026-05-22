# 下一步增强方案

本文档按优先级列出后续增强方向。每一项都避免虚构结果，完成后再更新简历描述。

## P0：重新验证 AI 客服命中真实数据

目标：确认 `103茶餐厅 有什么优惠券？` 能返回真实商户或优惠券信息，而不是误答未查询到。

步骤：

1. 重启 Spring Boot，让最新 AI 预查询兜底代码生效。

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

2. 登录获取 token，参考 `docs/api-test.md`。

3. 请求 AI 客服：

```powershell
$body = @{
  message = "103茶餐厅 有什么优惠券？"
  sessionId = "demo-session-final-ai"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8081/ai/customer-service/chat" `
  -Headers @{ authorization = $token } `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

验收标准：

- `success = true`
- 回答能提到真实商户或真实优惠券。
- 如果库中没有优惠券，必须明确回答 `未查询到相关数据`，不能编造。

## P1：补演示记录文档

目标：面试时能快速展示项目不是只写在简历上，而是可以复现。

建议新增：

```text
docs/demo-record.md
```

内容包括：

- 登录成功截图。
- 商户缓存查询截图。
- Redis 中 `cache:shop:{id}` 的截图。
- 秒杀下单返回订单 id 的截图。
- RocketMQ Dashboard Topic 或消息截图。
- MySQL `tb_voucher_order` 和 `tb_voucher_order_task` 状态截图。
- AI 客服返回商户/优惠券信息截图。
- 限流失败响应截图。

验收标准：

- 每个核心亮点至少有一个截图或命令输出。
- 截图中不要暴露 API Key、手机号真实隐私或个人 Token。

## P2：补支付与关单并发说明

目标：把当前状态条件更新讲清楚，面试时能回答“支付和超时关闭同时发生怎么办”。

当前代码已经使用：

```text
where id = ? and status = CREATED
```

这保证支付和关闭只有一个状态更新能成功。

建议补充：

- 在 `docs/order-timeout.md` 增加“支付与关单并发”小节。
- 说明 `CREATED -> PAID` 和 `CREATED -> CLOSED` 都基于状态条件更新。
- 说明如果支付先成功，关单看到状态不是 `CREATED` 会幂等跳过。
- 如果关单先成功，支付接口会返回“订单状态不允许支付”。

可选增强：

- 增加 `version` 字段做显式乐观锁。
- 但当前项目不一定需要，状态条件更新已经能覆盖这个场景。

## P3：轻量压测报告

目标：如果简历想写 QPS，必须先有压测依据。

建议新增：

```text
docs/load-test.md
```

压测对象：

- `GET /shop/{id}`：缓存命中查询。
- `POST /user/code`：限流验证，不追求 QPS。
- `POST /voucher-order/seckill/{id}`：秒杀入口。

工具可选：

- JMeter
- wrk
- ApacheBench

注意事项：

- 秒杀压测前要准备足够库存和不同用户 token。
- 不要用一个 token 压测秒杀，否则会被一人一单和限流拦截。
- 报告只写真实数据，包括机器配置、并发数、总请求数、成功数、失败数、P95/P99。

简历写法：

```text
使用 JMeter 对商户缓存命中接口进行本地压测，记录并分析 P95 延迟和错误率。
```

不要写：

```text
支持百万 QPS。
```

## P4：缓存删除失败补偿

目标：让缓存一致性方案更接近工程闭环。

当前实现：

```text
更新 MySQL -> 事务提交后删除缓存 -> 延迟双删
```

可增强为：

```text
删除缓存失败 -> 写入缓存删除任务表或发送 MQ -> 后台重试删除
```

建议方案：

1. 新增 `tb_cache_delete_task`。
2. 商户更新后删除缓存失败时写任务。
3. 定时任务扫描失败任务，重试删除 `cache:shop:{id}` 和 `cache:shop:hot:{id}`。
4. 超过最大重试次数后记录人工处理状态。

验收标准：

- 有 SQL 迁移脚本。
- 有 Service 和定时任务。
- 有文档说明状态流转。
- 有本地验证命令。

## P5：RAG 知识库

目标：把 AI 客服从“查系统数据”扩展到“回答平台规则”。

建议范围：

- 秒杀规则。
- 退款/关单说明。
- 优惠券使用说明。
- 平台客服 FAQ。

技术方案：

- 文档切分。
- EmbeddingModel。
- 向量库或本地内存向量存储。
- LangChain4j ContentRetriever。

注意：

- 实时库存、订单状态、优惠券信息仍然走 Function Calling。
- 规则类问题走 RAG。
- 回答中区分“系统实时数据”和“规则文档信息”。

## P6：管理员鉴权

目标：保护人工处理接口。

当前人工处理接口：

```text
GET /voucher-order-task/manual-review
POST /voucher-order-task/manual-review/{id}/retry
POST /voucher-order-task/manual-review/{id}/release-redis
```

目前只依赖登录态，不区分普通用户和管理员。

增强方向：

- 用户表增加角色字段。
- 增加 `@RequireAdmin` 注解或拦截器。
- 人工处理接口只允许管理员访问。

验收标准：

- 普通用户访问返回无权限。
- 管理员访问成功。
- 文档说明如何创建管理员账号。
