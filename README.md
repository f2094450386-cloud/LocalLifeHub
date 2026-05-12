# 邻享生活 LocalLifeHub

邻享生活 LocalLifeHub 是一个基于黑马点评 hmdp 底座逐步改造的本地生活服务平台，用来展示 Java 后端工程能力。当前阶段保留原版 hmdp 的核心业务结构和接口，先完成项目导入、命名整理和文档基线。

## 当前状态

- 后端框架：Spring Boot 2.7.4、MyBatis-Plus、MySQL、Redis、Redisson。
- 代码包名：仍保留 `com.hmdp`，暂不做大规模包名替换，避免引入无意义风险。
- 前端资源：保留原 hmdp `init` 分支中的 `src/main/resources/nginx-1.18.0`。
- 数据库脚本：保留原始 `hmdp.sql`，当前不新增表、不修改原始 SQL 内容。

## 原始来源

本项目底座来源于开源仓库：

- https://github.com/cs001020/hmdp
- 后端完整底座参考 `master` 分支。
- 前端静态资源参考 `init` 分支。

原仓库是黑马点评 Redis 实战项目，本仓库仅在此基础上做学习型、增量式改造，不大段复制其他业务代码。

## 已继承模块

当前已继承用户登录、商户查询、商户类型、文件上传、探店笔记、关注关系、点赞、优惠券、秒杀订单和 Redis 缓存相关能力。模块说明见 `docs/architecture.md`，接口分组见 `docs/current-api.md`。

## 本地启动

1. 准备 JDK 8+、Maven、MySQL、Redis。
2. 在 MySQL 中执行根目录 `hmdp.sql`。
3. 按 `docs/dev-notes.md` 配置环境变量，至少确认 MySQL 和 Redis 连接信息。
4. 首次使用秒杀订单 Redis Stream 前，在 Redis 执行：

```text
XGROUP CREATE stream.orders g1 $ MKSTREAM
```

5. 编译或启动：

```bash
mvn -q -DskipTests compile
mvn spring-boot:run
```

## 改造路线

后续改造会按 `docs/roadmap.md` 分阶段推进，重点包括 Redis + Lua 秒杀、RocketMQ 异步下单、订单超时关闭、缓存治理、AOP 限流、AI 客服和接口测试文档。
