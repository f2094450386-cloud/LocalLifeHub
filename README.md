# 邻享生活 LocalLifeHub

邻享生活 LocalLifeHub 是一个基于黑马点评 hmdp 底座增量改造的本地生活服务平台后端项目。项目保留原有用户、商户、探店、关注、优惠券等基础业务，在此基础上重点补充 Redis 缓存治理、优惠券秒杀高并发链路、RocketMQ 异步下单、本地任务表补偿、未支付订单超时关闭、通用 AOP 限流和 LangChain4j AI 客服。

本项目定位是 Java 后端能力展示项目，所有简历可写功能都要求在代码、SQL 或文档中有对应实现。

## 技术栈

- Java 8 + Spring Boot 2.7
- MyBatis-Plus + MySQL 8
- Redis + Lua + Redisson
- RocketMQ 4.9
- Spring AOP + 自定义注解
- LangChain4j + 阿里云百炼 OpenAI-compatible API
- Maven
- Docker Compose

## 核心亮点

- 商户缓存治理：缓存穿透、缓存击穿、随机 TTL、逻辑过期、更新后删缓存和延迟双删。
- 秒杀下单：Redis Lua 原子预扣库存和一人一单校验，RocketMQ 异步创建订单。
- 一致性补偿：`tb_voucher_order_task` 本地任务表记录 MQ 投递、消费和失败状态，定时补偿并支持人工处理。
- 未支付订单超时关闭：RocketMQ 延迟消息 + Spring Task 兜底扫描，幂等关闭订单并恢复库存。
- 通用限流：`@RateLimit` 注解，Redis ZSet + Lua 实现滑动窗口限流。
- AI 客服：LangChain4j 接入 OpenAI-compatible 模型，Redis 保存会话记忆，Function Calling 查询商户和优惠券数据。

## 本地环境

默认 Docker Compose 服务：

| 服务 | 地址 | 账号/密码 |
| --- | --- | --- |
| MySQL | `127.0.0.1:3307` | `root / locallifehub_root` |
| Redis | `127.0.0.1:6380` | `locallifehub_redis` |
| RocketMQ NameServer | `127.0.0.1:9876` | 无 |
| RocketMQ Broker | `127.0.0.1:10911` | 无 |
| RocketMQ Dashboard | `http://127.0.0.1:8088` | 无 |

启动依赖：

```powershell
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker
```

如果是已有 MySQL volume，初始化脚本不会重复执行。需要手动补迁移：

```powershell
Get-Content -Raw .\sql\20260512_add_voucher_order_task.sql | docker exec -i locallifehub-mysql mysql -uroot -plocallifehub_root hmdp
Get-Content -Raw .\sql\20260512_adjust_voucher_order_active_unique_index.sql | docker exec -i locallifehub-mysql mysql -uroot -plocallifehub_root hmdp
```

详细学习文档在本地 `docs` 目录，不随仓库发布。

## 启动方式

编译：

```powershell
mvn -q -DskipTests compile
```

启动本地 profile：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

AI 客服需要额外配置大模型 API Key。推荐方式：复制 `.env.example` 为 `.env`，填入真实 Key，然后通过 IDE EnvFile 插件或手动 export 加载。

```powershell
# 模型选择: deepseek、qwen 或 minimax
$env:AI_ACTIVE="deepseek"

# DeepSeek
$env:DEEPSEEK_API_KEY="sk-your-key"
$env:DEEPSEEK_BASE_URL="https://api.deepseek.com"
$env:DEEPSEEK_MODEL="deepseek-chat"

# 千问（切换时设置 AI_ACTIVE=qwen）
$env:QWEN_API_KEY="sk-your-key"
$env:QWEN_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:QWEN_MODEL="qwen-plus"

# MiniMax（切换时设置 AI_ACTIVE=minimax）
$env:MINIMAX_API_KEY="sk-your-key"
$env:MINIMAX_BASE_URL="https://api.minimax.chat/v1"
$env:MINIMAX_MODEL="MiniMax-M1"
```

Spring Boot 不会自动读取 `.env` 文件。IntelliJ IDEA 用户推荐装 EnvFile 插件指向 `.env`；命令行用户启动前手动 export 以上变量。修改 `AI_ACTIVE` 切换模型后需要重启 Spring Boot，普通环境变量不支持运行时热更新。

## 接口验证

核心接口 curl 示例（登录/验证码、商户查询、秒杀下单、订单关闭、AI 客服、限流验证）见本地 `docs` 目录，不随仓库发布。

## 设计文档

详细学习文档在本地 `docs` 目录（缓存治理、秒杀链路、一致性补偿、超时关闭、限流、AI 客服、简历版本等），不随仓库发布。

## 常见问题

### PowerShell 启动参数报 Unknown lifecycle phase

PowerShell 需要给 Maven `-D` 参数加引号：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

### MySQL 缺少 tb_voucher_order_task

说明本地 MySQL 数据卷是在新增迁移脚本前初始化的。手动执行：

```powershell
Get-Content -Raw .\sql\20260512_add_voucher_order_task.sql | docker exec -i locallifehub-mysql mysql -uroot -plocallifehub_root hmdp
```

### 中文响应在 Windows PowerShell 显示乱码

建议切换 UTF-8：

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
```

如果仍乱码，可以用 `curl.exe -o response.json` 保存响应，再用 `Get-Content -Encoding UTF8 response.json` 查看。

### AI 客服返回未配置

检查当前激活的模型和对应环境变量：

```powershell
$env:AI_ACTIVE
$env:DEEPSEEK_API_KEY   # AI_ACTIVE=deepseek 时需要
$env:QWEN_API_KEY       # AI_ACTIVE=qwen 时需要
```

不要把 API Key 提交到仓库或写入配置文件。

### RocketMQ Dashboard 打不开

默认地址是 `http://127.0.0.1:8088`。如果端口冲突，可以设置：

```powershell
$env:ROCKETMQ_DASHBOARD_PORT="18088"
docker compose up -d rocketmq-dashboard
```

### 当前项目边界

- 没有真实支付网关，只实现了最小支付状态接口用于验证订单关闭。
- 没有压测报告，因此 README 和简历文档不写 QPS 或性能提升百分比。
- AI 客服已实现 Function Calling 和 Redis 会话记忆，RAG 知识库仍是后续扩展方向。

## 原始来源

本项目底座来源于黑马点评 hmdp 学习项目：

- https://github.com/cs001020/hmdp

当前仓库在该底座上进行学习型增量改造，不大段复制其他业务代码。
