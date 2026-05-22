# LocalLifeHub 学习讲解规则

本文件用于后续按 hmdp / Redis 实战篇的学习方式讲解当前项目。目标是快速看懂 `LocalLifeHub` 的完整业务链路、源码实现、面试表达和简历表达；默认只读源码和文档，不默认手敲代码、不默认修改业务代码。

## 项目定位

`D:\JavaNotes\LocalLifeHub` 是基于黑马点评 hmdp 底座二次开发的 Java 后端项目。它保留 hmdp 的用户、商户、探店、关注、优惠券等基础业务，同时重点增强：

- 商户缓存治理：穿透、击穿、雪崩、逻辑过期、延迟双删。
- 秒杀链路：Redis Lua 资格校验、本地任务表、RocketMQ 异步落库。
- 一致性补偿：`tb_voucher_order_task` 状态流转、定时补偿、人工处理。
- 订单超时关闭：RocketMQ 延迟消息 + Spring Task 兜底扫描。
- 通用限流：`@RateLimit` + Redis ZSet + Lua 滑动窗口。
- AI 客服：LangChain4j、OpenAI-compatible API、Redis 会话记忆、Function Calling 查询系统数据。

不要把普通 hmdp 的 Redis Stream 秒杀实现直接套到本项目。当前源码中的秒杀主线已经是：

```text
前端抢券 -> /voucher-order/seckill/{id}
-> Redis Lua 预扣库存和一人一单校验
-> tb_voucher_order_task 本地任务表
-> RocketMQ local-lifehub.voucher-order
-> 消费者写 MySQL 订单
-> RocketMQ 延迟消息 / Spring Task 关闭未支付订单
```

## 固定资料读取顺序

每次继续学习前，先读：

```text
D:\JavaNotes\LocalLifeHub\AGENTS.md
D:\JavaNotes\LocalLifeHub\docs\learning-progress.md
```

再按本次目标读取：

```text
1. README.md
2. docs/project-differences.md
3. docs/current-api.md
4. 对应设计文档，例如 cache-design.md、seckill-design.md、consistency.md、order-timeout.md、rate-limit.md、ai-customer-service.md
5. 前端页面和 common.js
6. Controller
7. Service / service.impl
8. Mapper / XML
9. Entity / DTO
10. SQL、Redis key、MQ topic、Docker / Nginx / application 配置
```

如果文档和源码冲突，以当前源码为准，并在讲解里指出冲突。例如早期文档里出现 Redis Stream 旧表述时，要说明它是 hmdp 对比背景，不是当前 LocalLifeHub 秒杀主线。

## 目录职责

```text
src/main/java/com/hmdp
当前后端核心源码。包名仍沿用 hmdp。

src/main/resources/nginx-1.18.0/html/hmdp
原 hmdp 前端静态页面，用于追踪页面到接口的请求链路。

src/main/resources/application.yaml
通用配置，默认端口、MySQL、Redis、RocketMQ、AI 客服配置入口。

src/main/resources/application-local.yml
本地 Docker Compose profile 配置。

src/main/resources/seckill.lua
秒杀 Redis Lua 资格校验脚本。

src/main/resources/unlock.lua
手写 Redis 分布式锁释放脚本，主要用于理解 hmdp 继承能力。

hmdp.sql
原始 hmdp 表结构和基础数据。

sql/
LocalLifeHub 增量 SQL 迁移，重点是秒杀订单唯一索引和本地任务表。

docs/
当前项目设计说明、接口验证、运行说明、简历表达和续学进度。

docker-compose.yml、docker/
本地 MySQL、Redis、RocketMQ、Dashboard 环境。

target/
Maven 构建产物，学习源码时可暂时忽略。
```

## 每个功能的讲解链路

每讲一个功能，必须按这条链路展开：

```text
页面看到什么
-> 用户做了什么
-> 前端发了什么请求
-> URL / method / params / body / headers
-> Nginx 如何把 /api 转给后端
-> Controller 哪个方法接住
-> Service 做了什么业务判断
-> Mapper / MyBatis-Plus 如何访问数据库
-> 涉及哪些表、字段、SQL 或 ORM 查询
-> Redis / RocketMQ / AI / 文件存储在哪里参与
-> key / value / topic / queue / 文件路径如何设计
-> 返回什么 Result / DTO / JSON
-> 前端如何使用返回结果
-> 并发、事务、一致性、幂等、安全问题
-> 面试怎么讲，可能怎么追问
```

讲源码时，必须说明：

- 类负责什么。
- 方法被谁调用。
- 参数从哪里来。
- 返回值给谁用。
- 哪些地方访问 MySQL。
- 哪些地方访问 Redis、RocketMQ、AI 或文件系统。
- 为什么这样设计。
- 关键代码片段必须在回答中加详细中文注释；除非明确要求修改源码，注释不写进源码文件。

## 基础概念补齐

默认学习者只掌握基础 Java 和 MySQL。第一次出现以下概念时，要先解释概念，再结合源码讲：

- HTTP、URL、Query、Path、Header、Body、JSON。
- Cookie、Session、Token、JWT。
- Spring Boot、Spring MVC、Controller、Service、Mapper、DTO、Entity。
- MyBatis-Plus、ORM、事务、AOP、注解、拦截器、ThreadLocal。
- Redis、Lua、缓存穿透、击穿、雪崩、分布式锁、ZSet、Set、Bitmap、GEO。
- RocketMQ、Topic、Producer、Consumer、ACK、重试、死信、延迟消息。
- 幂等、最终一致、本地任务表、补偿、人工处理。
- Nginx、反向代理、Docker Compose。
- LangChain4j、Function Calling、会话记忆、OpenAI-compatible API。

## 中间件讲解模板

每讲 Redis、RocketMQ、AI、Nginx、Docker、定时任务等技术点，都补齐：

```text
业务问题是什么
为什么只用数据库或普通代码不够
为什么这个技术适合
用了什么数据结构或机制
key / value / topic / queue / 文件路径如何设计
数据保存多久
TTL / ACK / retry / pending / 死信 / 补偿怎么处理
并发风险是什么
失败风险是什么
Java 代码在哪里调用
真实生产还需要什么兜底
面试怎么说
```

## 学习路线

当前学习优先围绕简历内容推进，不平均展开所有 hmdp 继承模块。默认主线是：

1. 商户查询与 Redis 缓存治理：商户详情、空值缓存、随机 TTL、互斥锁、逻辑过期、延迟双删。
2. 优惠券秒杀入口：券表关系、Redis Lua 原子库存校验、一人一单判断、Redis 预扣库存。
3. RocketMQ 异步下单：本地任务表、消息投递、消费者、幂等、MySQL 条件更新和唯一索引兜底。
4. 未支付订单超时关闭：RocketMQ 延迟消息、Spring Task 兜底扫描、`CREATED -> PAID / CLOSED` 状态流转。
5. 通用 AOP 限流：`@RateLimit`、Redis ZSet + Lua 滑动窗口，验证码、秒杀、AI 客服接入点。
6. AI 客服：LangChain4j、Redis 会话记忆、Function Calling 查询商户和优惠券真实数据。
7. 登录和用户态：验证码、token、Redis Session、拦截器、ThreadLocal，为上述登录态接口补基础。
8. 项目运行链路：Docker Compose、Spring Boot、Nginx、前端静态页，按需要补齐页面到接口链路。
9. 探店、点赞、关注、Feed、GEO、签到等 hmdp 继承能力，只在简历或面试需要时展开。
10. 面试表达和简历表达。

面试表述边界：

- `stock > 0` 条件更新主要用于防止 MySQL 层库存超卖。
- `user_id + active_voucher_id` 唯一索引主要用于兜底同一用户同一张未关闭券只能有一笔订单。
- 当前订单模块不是完整支付系统，只实现了最小支付状态接口，用于验证超时关单和支付并发。
- 当前 AI 客服不是完整 RAG 知识库，重点是 LangChain4j、Redis 会话记忆和 Function Calling 查询真实业务数据。

## 进度记录

每次学习结束后更新：

```text
D:\JavaNotes\LocalLifeHub\docs\learning-progress.md
```

至少记录：

- 日期。
- 当前学习目标。
- 本次讲到哪里。
- 涉及源码文件。
- 涉及接口。
- 涉及数据库表。
- 涉及 Redis key / MQ topic / 外部服务。
- 已理解的完整业务链路。
- 新增基础概念。
- 面试表达。
- 当前疑问。
- 下次从哪里继续。

## 工作方式限制

除非明确要求：

- 不改业务源码。
- 不自动重构。
- 不自动提交 Git。
- 不默认启动项目。
- 不默认跑大量测试。
- 不跳过前端到后端的请求链路。
- 不跳过数据库表和字段。
- 不跳过 Redis / RocketMQ / AI 等中间件数据设计。
- 不用抽象总结代替源码讲解。
