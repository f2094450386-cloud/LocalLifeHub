# LocalLifeHub 学习进度记录

本文件用于跨线程续学。每次继续学习当前项目前，先阅读根目录 `AGENTS.md` 和本文件。

## 当前状态

- 日期：2026-05-20
- 当前学习项目：`D:\JavaNotes\LocalLifeHub`
- 当前学习策略：参考 hmdp / Redis 实战篇的讲解方法，但以 LocalLifeHub 当前源码和文档为准。
- 当前目标：快速建立完整项目地图、学习主线、源码阅读顺序、面试表达路线。
- 工作方式：默认只读源码和文档，不默认手敲代码，不默认修改业务源码，不默认启动项目。

## 已确认项目定位

LocalLifeHub 是基于黑马点评 hmdp 底座二次开发的 Java Spring Boot 后端项目。它保留 hmdp 的用户、商户、探店、关注、优惠券等基础业务，并重点增强：

- Redis 商户缓存治理。
- Redis Lua 秒杀资格校验。
- RocketMQ 异步秒杀下单。
- `tb_voucher_order_task` 本地任务表补偿。
- 未支付订单超时关闭。
- `@RateLimit` 通用 AOP 限流。
- LangChain4j AI 客服、Redis 会话记忆、Function Calling 查询商户和优惠券。

## 当前项目地图

### 主源码目录

```text
D:\JavaNotes\LocalLifeHub\src\main\java\com\hmdp
```

核心分层：

- `controller`：HTTP 接口入口。
- `service` / `service.impl`：业务接口和实现。
- `mapper`：MyBatis-Plus 数据访问层。
- `entity`：数据库表实体。
- `dto`：接口请求、响应和消息对象。
- `interceptor`：登录态拦截器。
- `annotation` / `aspect`：限流注解和 AOP 实现。
- `mq`：RocketMQ 消费者、补偿任务、订单超时任务。
- `service/ai`：AI 客服工具函数和 Redis 会话记忆。
- `utils`：Redis key、RocketMQ topic、ID、锁、用户上下文等工具类。

### 前端目录

```text
D:\JavaNotes\LocalLifeHub\src\main\resources\nginx-1.18.0\html\hmdp
```

它是原 hmdp 静态前端资源，主要页面包括：

- `index.html`：首页，调用 `/shop-type/list`、`/blog/hot`。
- `login.html`：登录页，调用 `/user/code`、`/user/login`。
- `shop-list.html`：商户列表页，调用 `/shop-type/list`、`/shop/of/type`。
- `shop-detail.html`：商户详情和优惠券页，调用 `/shop/{id}`、`/voucher/list/{shopId}`、`/voucher-order/seckill/{id}`。
- `blog-detail.html`、`blog-edit.html`、`info.html`、`other-info.html`：探店、点赞、关注、个人页相关链路。
- `js/common.js`：Axios `baseURL=/api`，把 token 放入 `authorization` 请求头。

### 配置和启动

- 后端入口：`src/main/java/com/hmdp/HmDianPingApplication.java`
- 通用配置：`src/main/resources/application.yaml`
- 本地 profile：`src/main/resources/application-local.yml`
- Nginx 配置：`src/main/resources/nginx-1.18.0/conf/nginx.conf`
- Docker Compose：`docker-compose.yml`
- RocketMQ broker 配置：`docker/rocketmq/broker.conf`

本地依赖默认：

- MySQL：`127.0.0.1:3307`，`root / locallifehub_root`
- Redis：`127.0.0.1:6380`，密码 `locallifehub_redis`
- RocketMQ NameServer：`127.0.0.1:9876`
- RocketMQ Dashboard：`http://127.0.0.1:8088`
- 后端端口：`8081`
- Nginx 前端端口：`8080`

启动说明见：

```text
README.md
docs/local-run.md
```

### 数据库脚本

- 原始 hmdp 表和数据：`hmdp.sql`
- 增量迁移：`sql/`

已识别表：

- `tb_user`
- `tb_user_info`
- `tb_shop`
- `tb_shop_type`
- `tb_blog`
- `tb_blog_comments`
- `tb_follow`
- `tb_voucher`
- `tb_seckill_voucher`
- `tb_voucher_order`
- `tb_sign`
- `tb_voucher_order_task`

### 核心中间件数据

Redis key：

- `login:code:{phone}`：验证码。
- `login:token:{token}`：登录用户态。
- `cache:shop:{id}`：普通商户缓存。
- `cache:shop:hot:{id}`：热点商户逻辑过期缓存。
- `lock:shop:{id}`：商户缓存重建互斥锁。
- `seckill:stock:{voucherId}`：秒杀 Redis 预扣库存。
- `seckill:order:{voucherId}`：已抢券用户 Set。
- `rate-limit:{business}:{dimension}`：滑动窗口限流 ZSet。
- `ai:customer-service:memory:{userId}:{sessionId}`：AI 客服会话记忆。
- `blog:liked:{blogId}`、`feed:{userId}`、`shop:geo:{typeId}`、`sign:{userId}:{yyyyMM}`：hmdp 继承能力。

RocketMQ topic：

- `local-lifehub.voucher-order`：秒杀订单异步创建。
- `local-lifehub.voucher-order-timeout`：未支付订单超时关闭。

## 本次已阅读文件

参考学习规则：

- `D:\JavaNotes\hmdp-full\AGENTS.md`
- `D:\JavaNotes\hmdp-full\docs\learning-progress.md`
- `D:\JavaNotes\hmdp-master\AGENTS.md`

当前项目文档和配置：

- `README.md`
- `pom.xml`
- `docker-compose.yml`
- `docs/architecture.md`
- `docs/project-differences.md`
- `docs/current-api.md`
- `docs/local-run.md`
- `docs/cache-design.md`
- `docs/seckill-design.md`
- `docs/consistency.md`
- `docs/order-timeout.md`
- `docs/rate-limit.md`
- `docs/ai-customer-service.md`
- `docs/manual-review.md`
- `docs/resume-version.md`
- `docs/api-test.md`
- `src/main/resources/application.yaml`
- `src/main/resources/application-local.yml`
- `src/main/resources/nginx-1.18.0/conf/nginx.conf`
- `src/main/resources/nginx-1.18.0/html/hmdp/js/common.js`

当前项目源码：

- `HmDianPingApplication.java`
- `VoucherOrderServiceImpl.java`
- `VoucherOrderConsumer.java`
- `VoucherOrderTaskCompensator.java`
- `OrderTimeoutConsumer.java`
- `OrderTimeoutScanner.java`
- `VoucherOrderTaskServiceImpl.java`
- `ShopServiceImpl.java`
- `CacheClient.java`
- `RateLimitAspect.java`
- `AiCustomerServiceController.java`
- `AiCustomerServiceImpl.java`
- `RedisConstants.java`
- `RocketMqConstants.java`
- `seckill.lua`

## 已确认的 hmdp 差异

- 本项目当前秒杀主线不是 hmdp 的 Redis Stream 消费组，而是 RocketMQ。
- Redis Stream 只作为原 hmdp 对照或旧方案背景，不作为当前主讲链路。
- 本项目新增 `tb_voucher_order_task`，用于记录 Redis 预扣到 MQ 投递、消费、失败和人工处理状态。
- 本项目新增订单状态流转：`CREATED(1)`、`PAID(2)`、`CLOSED(4)`。
- 本项目新增生成列 `active_voucher_id`，用于允许已关闭订单不再占用一人一单资格。
- 本项目新增通用限流和 AI 客服，不属于普通 hmdp 核心课程内容。

## 建议学习路线

1. 项目运行链路：Docker Compose、Spring Boot、Nginx、前端静态页面。
2. 首页链路：`index.html -> common.js -> Nginx /api -> ShopTypeController / BlogController`。
3. 登录链路：验证码、token、Redis 登录态、拦截器、ThreadLocal。
4. 商户查询链路：分类、列表、详情、GEO、缓存治理。
5. 优惠券展示链路：店铺详情页加载券列表。
6. 秒杀入口链路：前端抢券、Controller、Lua、Redis key。
7. RocketMQ 异步下单链路：本地任务表、MQ 投递、消费者、MySQL 订单。
8. 订单超时关闭链路：延迟消息、兜底扫描、支付和关闭并发。
9. 探店社交链路：发笔记、点赞、关注、Feed。
10. 通用 AOP 限流链路。
11. AI 客服链路：请求、登录态、限流、LangChain4j、Redis 会话记忆、工具查库。
12. 面试表达和简历表达。

## 本次新增基础概念

- Nginx `/api` 反向代理。
- Spring Boot 单体项目分层。
- MyBatis-Plus Mapper / ServiceImpl。
- Redis Lua 原子校验。
- RocketMQ Topic / Producer / Consumer。
- 本地任务表 / Outbox 思路。
- 延迟消息和定时任务兜底。
- AOP 注解限流。
- LangChain4j Function Calling。

## 面试表达初稿

LocalLifeHub 是基于 hmdp 改造的本地生活服务后端项目。我保留了用户、商户、探店、关注和优惠券基础业务，在秒杀、缓存、限流和 AI 客服上做了工程化增强。秒杀入口使用 Redis Lua 原子完成库存预扣和一人一单校验，成功后写本地任务表并投递 RocketMQ，消费者异步扣减 MySQL 库存和创建订单；任务表记录 PENDING、SENT、CONSUMED、FAILED、MANUAL_REVIEW 等状态，定时补偿失败任务。未支付订单通过 RocketMQ 延迟消息和 Spring Task 扫描幂等关闭，并恢复 MySQL 和 Redis 库存。商户详情使用 Redis 缓存治理，覆盖穿透、击穿、雪崩和延迟双删。项目还用 AOP + Redis ZSet + Lua 做通用滑动窗口限流，并用 LangChain4j 实现可查询系统商户和优惠券数据的 AI 客服。

## 当前疑问和注意点

- `docs/architecture.md`、`docs/current-api.md`、`docs/dev-notes.md` 中部分描述仍停留在旧阶段，例如 Redis Stream 或“未引入 RocketMQ”，后续讲解时必须以源码和 README 为准。
- 当前工作区存在大量未提交改动，学习时不要误以为这些都是稳定发布版本。
- AI 客服需要外部 API Key，默认不启动验证。
- 当前没有真实支付网关，只有最小支付状态接口用于验证关单链路。
- 当前没有压测报告，简历不要写 QPS 或性能提升百分比。

## 下次从哪里继续

建议下次从“项目运行链路 + 首页第一条完整请求链路”开始：

```text
index.html
-> js/common.js
-> Nginx /api rewrite
-> ShopTypeController#queryTypeList
-> ShopTypeServiceImpl / ShopTypeMapper
-> tb_shop_type
-> Result
-> 首页分类展示
```

讲完首页基础链路后，再进入登录链路和商户缓存链路。

## 2026-05-21 学习记录

- 日期：2026-05-21
- 当前学习目标：开始讲解项目运行链路和首页第一条完整请求链路。
- 本次讲到哪里：从前端首页 `index.html` 的分类区域开始，追踪到 `/shop-type/list`，再经过 Nginx `/api` 反向代理、`ShopTypeController#queryTypeList`、`ShopTypeServiceImpl#getTypeList`、Redis 分类缓存和 `tb_shop_type` 查询。
- 涉及源码文件：
  - `src/main/resources/nginx-1.18.0/html/hmdp/index.html`
  - `src/main/resources/nginx-1.18.0/html/hmdp/js/common.js`
  - `src/main/resources/nginx-1.18.0/conf/nginx.conf`
  - `src/main/java/com/hmdp/controller/ShopTypeController.java`
  - `src/main/java/com/hmdp/service/IShopTypeService.java`
  - `src/main/java/com/hmdp/service/impl/ShopTypeServiceImpl.java`
  - `src/main/java/com/hmdp/mapper/ShopTypeMapper.java`
  - `src/main/java/com/hmdp/entity/ShopType.java`
  - `src/main/java/com/hmdp/dto/Result.java`
  - `src/main/java/com/hmdp/utils/RedisConstants.java`
- 涉及接口：`GET /shop-type/list`，前端实际请求为 `/api/shop-type/list`。
- 涉及数据库表：`tb_shop_type`，字段包括 `id`、`name`、`icon`、`sort`、`create_time`、`update_time`。
- 涉及 Redis key / MQ topic / 外部服务：Redis List key `cache:type`；本链路不涉及 RocketMQ 和 AI 外部服务。
- 已理解的完整业务链路：用户打开首页后，Vue `created()` 调用 `queryTypes()`；Axios 基于 `/api` 发起分类查询；Nginx 将 `/api/shop-type/list` 改写为 `/shop-type/list` 并转发到 8081；Controller 调用 Service；Service 先从 Redis List `cache:type` 读缓存，未命中再按 `sort` 升序查询 `tb_shop_type`，把结果序列化为 JSON 写入 Redis List，最后通过 `Result.ok(typeList)` 返回给前端；前端把 `data` 赋值给 `types` 并渲染分类图标。
- 新增基础概念：HTTP 请求、URL、Path、Header、JSON、Nginx 反向代理、Spring MVC Controller、Service、Mapper、MyBatis-Plus ORM、Redis List 缓存。
- 面试表达：分类列表属于读多写少的数据，接口先读 Redis List 缓存，未命中再查 MySQL，并按 `sort` 字段升序返回。前端统一请求 `/api`，由 Nginx 反向代理到 Spring Boot 后端，后端通过 Controller-Service-Mapper 分层完成业务和数据访问。
- 当前疑问：分类缓存 `cache:type` 当前没有设置 TTL，也没有看到分类更新接口触发删除缓存；如果后台支持分类变更，需要补充缓存失效策略。
- 下次从哪里继续：继续讲首页热门探店链路 `index.html -> /blog/hot -> BlogController -> BlogServiceImpl -> tb_blog / tb_user -> Result -> 首页瀑布流`，然后进入登录链路。

## 2026-05-22 学习记录

- 日期：2026-05-22
- 当前学习目标：继续讲解首页热门探店链路。
- 本次讲到哪里：从首页 `index.html` 的瀑布流区域开始，追踪到 `/blog/hot?current=1`，再经过 Nginx `/api` 反向代理、`BlogController#queryHotBlog`、`BlogServiceImpl#queryHotBlog`、`tb_blog` 分页排序查询、`tb_user` 作者信息补全和 Redis 点赞状态判断。
- 涉及源码文件：
  - `src/main/resources/nginx-1.18.0/html/hmdp/index.html`
  - `src/main/resources/nginx-1.18.0/html/hmdp/js/common.js`
  - `src/main/resources/nginx-1.18.0/conf/nginx.conf`
  - `src/main/java/com/hmdp/config/MvcConfig.java`
  - `src/main/java/com/hmdp/interceptor/RefreshTokenInterceptor.java`
  - `src/main/java/com/hmdp/interceptor/LoginInterceptor.java`
  - `src/main/java/com/hmdp/controller/BlogController.java`
  - `src/main/java/com/hmdp/service/IBlogService.java`
  - `src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`
  - `src/main/java/com/hmdp/entity/Blog.java`
  - `src/main/java/com/hmdp/entity/User.java`
  - `src/main/java/com/hmdp/mapper/BlogMapper.java`
  - `src/main/java/com/hmdp/mapper/UserMapper.java`
  - `src/main/java/com/hmdp/dto/Result.java`
  - `src/main/java/com/hmdp/utils/RedisConstants.java`
  - `src/main/java/com/hmdp/utils/SystemConstants.java`
- 涉及接口：`GET /blog/hot?current={page}`，前端实际请求为 `/api/blog/hot?current={page}`。
- 涉及数据库表：`tb_blog`，字段包括 `id`、`shop_id`、`user_id`、`title`、`images`、`content`、`liked`、`comments`、`create_time`、`update_time`；`tb_user`，字段包括 `id`、`phone`、`password`、`nick_name`、`icon`、`create_time`、`update_time`。
- 涉及 Redis key / MQ topic / 外部服务：Redis ZSet key `blog:liked:{blogId}` 用于判断当前登录用户是否点赞；本链路不涉及 RocketMQ 和 AI 外部服务。
- 已理解的完整业务链路：用户打开首页后，Vue `created()` 调用 `queryHotBlogsScroll()`；Axios 基于 `/api` 发起 `/blog/hot?current=1`；Nginx 将 `/api/blog/hot` 改写为 `/blog/hot` 并转发到 8081；该接口被登录拦截器放行，但刷新 token 拦截器仍会尝试从 `authorization` 头恢复用户；Controller 调用 Service；Service 按 `liked` 倒序分页查询 `tb_blog`，每条 blog 再根据 `user_id` 查询 `tb_user` 补充昵称和头像，并在用户已登录时查询 Redis ZSet `blog:liked:{blogId}` 设置 `isLike`；最后返回 `Result.ok(records)`；前端把每条 blog 的 `images` 按逗号切出首图，追加到 `blogs` 数组并渲染瀑布流。
- 新增基础概念：Query 参数、分页查询、MyBatis-Plus `Page`、非表字段 `@TableField(exist = false)`、Redis ZSet 点赞集合、登录放行接口与可选登录态。
- 面试表达：首页热门探店接口按点赞数倒序分页查询探店笔记，再补充作者昵称头像和当前用户点赞状态。接口本身允许游客访问，如果请求头带 token，则通过 Redis 登录态恢复用户，并用 `blog:liked:{blogId}` 判断是否已点赞，最终返回前端渲染瀑布流。
- 当前疑问：热门探店接口当前按 `liked` 排序直接查 MySQL，且补充作者信息存在 N+1 查询；数据量增大后可以考虑缓存热门榜、批量查用户或做异步榜单。
- 下次从哪里继续：进入登录链路 `login.html -> /user/code -> /user/login -> Redis token -> RefreshTokenInterceptor -> LoginInterceptor -> UserHolder`。
