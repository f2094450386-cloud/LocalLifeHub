# CHANGE.md

## Project
项目名：邻享生活 LocalLifeHub。
目标：基于黑马点评 hmdp 改造成一个本地生活服务平台，核心展示 Java 后端能力：Redis 缓存、秒杀高并发、RocketMQ 异步解耦、AOP 限流、缓存一致性、AI 客服。

## Working rules
- 每次改动前先阅读现有目录结构、pom.xml、application.yml、数据库 SQL、Controller/Service/Mapper 分层。
- 不要一次性重写整个项目，优先做最小侵入式增量修改。
- 每轮任务结束必须说明：
  1. 修改了哪些文件；
  2. 新增了哪些接口；
  3. 如何启动和验证；
  4. 还有哪些未完成项。
- 修改 Java 代码后，至少运行 `mvn -q -DskipTests compile`。如果项目暂时无法编译，要说明具体原因和下一步修复建议。
- 不要硬编码密钥、手机号、Token、阿里云 API Key。统一使用环境变量或 application-local.yml，并给出示例配置。
- 不要虚构功能。简历中能写的功能必须在代码、接口、SQL、文档中有对应实现。
- 参考开源项目只学习设计思路，不要大段复制 README 或业务代码。

## Tech stack
- Java + Spring Boot + MyBatis-Plus + MySQL
- Redis + Lua + Redisson
- RocketMQ
- AOP + 自定义注解
- LangChain4j + 阿里云百炼 OpenAI-compatible API
- Maven
- Docker Compose 可选，用于 MySQL、Redis、RocketMQ 本地环境

## Code style
- 保留 Controller、Service、Mapper、DTO、Entity、Config、Util 分层。
- 复杂业务必须加中文注释，尤其是 Redis Key、Lua 脚本、MQ Topic、消费幂等、补偿逻辑。
- Redis Key 统一放到常量类中。
- 新增表必须提供 SQL 迁移脚本。
- 新增接口尽量提供 curl 示例或 Postman 说明。