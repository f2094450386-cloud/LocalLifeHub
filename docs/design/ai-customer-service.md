# AI 客服模块

## 接口

### 普通对话（同步）

```
POST /ai/customer-service/chat
```

请求体：

```json
{
  "message": "帮我查一下海底捞有什么优惠券",
  "sessionId": "demo-session-001"
}
```

`sessionId` 可为空。为空时后端会生成一个 UUID 并在响应中返回，后续多轮对话建议继续携带该值。

响应示例：

```json
{
  "success": true,
  "data": {
    "sessionId": "demo-session-001",
    "answer": "查询到海底捞火锅(水晶城购物中心店)..."
  }
}
```

### 流式对话（SSE）

```
POST /ai/customer-service/chat/stream
```

请求体同 `/chat`。返回 `text/event-stream`（SSE），每个 token 作为一个事件推送：

```
data: {"token":"查询","done":false}
data: {"token":"到","done":false}
data: {"token":"海底捞火锅(水晶城购物中心店)有...","done":false}
data: {"token":"","done":true,"sessionId":"demo-session-001"}
```

- `done:false` 事件仅包含 `token` 字段，表示中间文本片段。
- `done:true` 事件必定包含 `sessionId` 和空 `token`。如果发生业务错误（如配置缺失、消息为空），会额外包含 `error` 字段。
- 后端设置 `X-Accel-Buffering: no`，Nginx 需配置 `proxy_buffering off` 避免缓冲导致流式退化为一次性返回。

### 前端页面

访问路径（通过 Nginx）：

```
http://<host>/ai-cs.html
```

页面位于 `src/main/resources/nginx-1.18.0/html/hmdp/ai-cs.html`，使用 `fetch` + `ReadableStream` 消费 SSE。需先登录获取 token；未登录时页面会提示跳转到登录页。

---

## LangChain4j 接入方式

当前实现使用 `langchain4j` 和 `langchain4j-open-ai` `0.35.0`。该版本兼容项目当前 Java 8 编译目标。

核心链路：

1. `AiCustomerServiceController` 接收 `/ai/customer-service/chat` 和 `/ai/customer-service/chat/stream` 请求。
2. `AiCustomerServiceImpl` 校验 LLM 配置，懒加载 `OpenAiChatModel`（同步）和 `OpenAiStreamingChatModel`（流式）。
3. 通过 `AiServices.builder(CustomerServiceAgent.class)` 绑定：
   - OpenAI-compatible ChatModel；
   - Redis 会话记忆；
   - `LocalLifeHubAiTools` 工具函数。
4. 流式 Agent 使用 `AiServices.builder(StreamingCustomerServiceAgent.class)` + `streamingChatLanguageModel`，返回 `TokenStream`。
5. 服务端会先根据用户原文做轻量商户名命中，命中时把候选商户和优惠券作为预查询上下文传给 LLM。
6. LLM 根据用户自然语言选择工具查询数据库，或基于预查询上下文回答。

系统提示要求：商户、优惠券问题必须先调用工具；工具返回"未查询到相关数据"时，AI 必须明确回答"未查询到相关数据"，不能编造店铺或优惠券。

预查询上下文只作为 Function Calling 的兜底：当模型没有正确抽取店铺名参数时，后端仍能把原文命中的商户和优惠券交给模型，降低"数据库有数据但模型误答未查到"的概率。

## 阿里云百炼 OpenAI-compatible 配置

不要把 API Key 写入代码或配置文件。使用环境变量：

```powershell
$env:LOCAL_LIFEHUB_LLM_API_KEY="sk-xxx"
$env:LOCAL_LIFEHUB_LLM_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:LOCAL_LIFEHUB_LLM_MODEL="qwen-plus"
```

兼容旧变量名：

```powershell
$env:AI_CUSTOMER_SERVICE_API_KEY="sk-xxx"
$env:AI_CUSTOMER_SERVICE_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:AI_CUSTOMER_SERVICE_MODEL="qwen-plus"
```

如果 `LOCAL_LIFEHUB_LLM_API_KEY`、`LOCAL_LIFEHUB_LLM_BASE_URL` 或 `LOCAL_LIFEHUB_LLM_MODEL` 缺失，应用仍可正常启动，但接口会返回明确错误：

```text
AI 客服未配置 LOCAL_LIFEHUB_LLM_API_KEY
AI 客服未配置 LOCAL_LIFEHUB_LLM_BASE_URL
AI 客服未配置 LOCAL_LIFEHUB_LLM_MODEL
```

流式接口额外使用 `AI_CUSTOMER_SERVICE_STREAM_TIMEOUT_MS` 控制 SSE 超时（默认 60000ms）。

## Redis 会话记忆

Redis Key：

```text
ai:customer-service:memory:{userId}:{sessionId}
```

示例：

```text
ai:customer-service:memory:101:demo-session-001
```

TTL：

```text
120 minutes
```

常量位置：

```text
RedisConstants.AI_CUSTOMER_SERVICE_MEMORY_KEY
RedisConstants.AI_CUSTOMER_SERVICE_MEMORY_TTL
```

会话窗口默认保留最近 20 条消息，可通过环境变量调整：

```powershell
$env:AI_CUSTOMER_SERVICE_MAX_MEMORY_MESSAGES="20"
```

## Function Calling 工具列表

工具类：

```text
com.hmdp.service.ai.LocalLifeHubAiTools
```

已实现工具：

1. `query_shop_info`
   - 参数：`shopId`、`shopName`、`typeId`、`typeName`
   - 能力：按店铺 id、店铺名称关键词、店铺类型 id 或类型名称查询商户。
   - 返回：店铺 id、名称、类型、商圈、地址、人均、评分、销量、评论数、营业时间。

2. `query_voucher_info`
   - 参数：`shopId`、`voucherId`
   - 能力：按店铺 id 或优惠券 id 查询可用优惠券。
   - 返回：优惠券 id、店铺、标题、副标题、规则、支付金额、抵扣金额、状态、秒杀库存和有效期。

两个工具查询不到数据时都会返回：

```text
未查询到相关数据
```

## 限流

AI 客服接口已接入现有 `@RateLimit`，`/chat` 和 `/chat/stream` 共享同一限流 key：

```text
key: rate-limit:ai:chat:{userId}
window: 60s
maxRequests: 20
```

## 本地验证

启动依赖：

```powershell
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker
```

设置环境变量：

```powershell
$env:LOCAL_LIFEHUB_LLM_API_KEY="sk-xxx"
$env:LOCAL_LIFEHUB_LLM_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:LOCAL_LIFEHUB_LLM_MODEL="qwen-plus"
```

启动应用：

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

先登录获取 token，再请求 AI 客服：

**同步：**
```bash
curl -X POST "http://127.0.0.1:8081/ai/customer-service/chat" \
  -H "authorization: <token>" \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"帮我查一下 103茶餐厅 的优惠券\",\"sessionId\":\"demo-session-001\"}"
```

**流式（SSE）：**
```bash
curl -N -X POST "http://127.0.0.1:8081/ai/customer-service/chat/stream" \
  -H "authorization: <token>" \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"帮我查一下 103茶餐厅 的优惠券\"}"
```

也可以验证配置缺失场景：不设置 `LOCAL_LIFEHUB_LLM_API_KEY`，接口应返回 `AI 客服未配置 LOCAL_LIFEHUB_LLM_API_KEY`，应用启动不应失败。

编译验证：

```powershell
mvn -q -DskipTests compile
```

## 后续扩展 RAG

后续可以把平台帮助文档、退款规则、秒杀规则、商户运营说明拆分成知识库文档，使用 LangChain4j 的 EmbeddingModel + ContentRetriever 扩展 RAG：

1. 文档切分后写入向量库，元数据记录业务域和更新时间。
2. AI 客服先走 Function Calling 查询实时业务数据。
3. 对规则类、流程类问题走 RAG 检索。
4. 最终回答中区分"实时系统数据"和"规则文档信息"，避免把历史文档当成库存、价格等实时数据。
