# 本地运行说明

本文档用于在本机启动 LocalLifeHub 当前开发环境。当前秒杀链路使用 MySQL、Redis 和 RocketMQ；Redis Stream 是原 hmdp 实现，当前代码已不再依赖。

## 版本选择

RocketMQ 使用 `apache/rocketmq:4.9.7`，Dashboard 使用 `apacherocketmq/rocketmq-dashboard:1.0.0`。选择 4.9.x 是因为它在单机开发环境中更成熟、启动参数简单，适合作为后续异步下单改造前的本地基础设施。

Compose 中 RocketMQ NameServer 和 Broker 以 `root` 用户运行。原因是官方镜像默认用户在 Windows + Docker Desktop 的 named volume 场景下可能无法写入 `/home/rocketmq/logs` 或 `/home/rocketmq/store`，导致 Broker 反复退出；该设置仅用于本地开发。

## 默认地址和密码

| 服务 | 地址 | 默认账号/密码 |
| --- | --- | --- |
| MySQL | `127.0.0.1:3307` | `root / locallifehub_root` |
| Redis | `127.0.0.1:6380` | 密码 `locallifehub_redis` |
| RocketMQ NameServer | `127.0.0.1:9876` | 无 |
| RocketMQ Broker | `127.0.0.1:10911` | 无 |
| RocketMQ Dashboard | `http://127.0.0.1:8088` | 无 |

这些默认值只用于本地开发。可以通过环境变量覆盖，例如 `MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、`ROCKETMQ_DASHBOARD_PORT`。

## 启动 Docker Compose

在项目根目录执行：

```bash
docker compose up -d
```

查看服务状态：

```bash
docker compose ps
```

查看日志：

```bash
docker compose logs -f mysql
docker compose logs -f redis
docker compose logs -f rocketmq-broker
```

停止服务：

```bash
docker compose down
```

如果需要清空本地数据重新初始化：

```bash
docker compose down -v
docker compose up -d
```

## 导入 hmdp.sql

`docker-compose.yml` 已将根目录 `hmdp.sql`、`sql/20260512_add_voucher_order_unique_index.sql`、`sql/20260512_add_voucher_order_task.sql` 和 `sql/20260512_adjust_voucher_order_active_unique_index.sql` 挂载到 MySQL 初始化目录。首次创建 `mysql-data` 卷时，MySQL 会自动导入原始表结构、秒杀订单任务表，并把订单唯一索引调整为只约束未关闭订单。

如果容器已经启动过，初始化脚本不会重复执行。可以手动导入原始数据：

```bash
docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp < hmdp.sql
```

PowerShell 也可以使用：

```powershell
Get-Content .\hmdp.sql | docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp
```

验证表是否存在：

```bash
docker compose exec mysql mysql -uroot -plocallifehub_root -e "SHOW TABLES FROM hmdp;"
```

## Redis Stream 消费组

原 hmdp 秒杀订单逻辑依赖 Redis Stream：`stream.orders`，消费组为 `g1`。当前 LocalLifeHub 已改为 RocketMQ 异步创建订单，不再要求创建该消费组。

如果需要回退或对比原 Redis Stream 实现，可以执行：

```bash
docker compose exec redis redis-cli -a locallifehub_redis XGROUP CREATE stream.orders g1 '$' MKSTREAM
```

如果返回 `BUSYGROUP Consumer Group name already exists`，说明消费组已经存在，可以忽略。

验证：

```bash
docker compose exec redis redis-cli -a locallifehub_redis XINFO GROUPS stream.orders
```

## 启动 Spring Boot

使用本地 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

或先编译：

```bash
mvn -q -DskipTests compile
```

`application-local.yml` 的默认连接信息与 `docker-compose.yml` 保持一致：

- MySQL 密码：`locallifehub_root`
- Redis 密码：`locallifehub_redis`
- RocketMQ NameServer：`127.0.0.1:9876`

如果当前库是在新增迁移脚本前初始化的，还需要执行秒杀订单幂等唯一索引：

```bash
docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp < sql/20260512_add_voucher_order_unique_index.sql
```

PowerShell：

```powershell
Get-Content .\sql\20260512_add_voucher_order_unique_index.sql | docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp
```

新增秒杀订单任务表迁移脚本：

```bash
docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp < sql/20260512_add_voucher_order_task.sql
```

PowerShell：

```powershell
Get-Content .\sql\20260512_add_voucher_order_task.sql | docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp
```

如果已经执行过旧的一人一单唯一索引脚本，还需要执行 active 唯一索引调整脚本，允许未支付订单关闭后重新抢同一券：

```bash
docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp < sql/20260512_adjust_voucher_order_active_unique_index.sql
```

PowerShell：

```powershell
Get-Content .\sql\20260512_adjust_voucher_order_active_unique_index.sql | docker compose exec -T mysql mysql -uroot -plocallifehub_root hmdp
```

如果本地 Docker 默认使用 MySQL `3307`、Redis `6380`，启动前可显式设置：

```powershell
$env:MYSQL_PORT="3307"
$env:REDIS_PORT="6380"
$env:ROCKETMQ_NAME_SERVER="127.0.0.1:9876"
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

RocketMQ 秒杀订单 Topic 保持为 `local-lifehub.voucher-order`。未支付订单超时检查 Topic 为 `local-lifehub.voucher-order-timeout`。本地 Broker 在 `docker/rocketmq/broker.conf` 中配置了 15 分钟延迟等级，修改后需要重启 `rocketmq-broker`。

## 前端资源

原 hmdp 前端资源保留在：

```text
src/main/resources/nginx-1.18.0
```

本轮没有删除或改造该目录。需要前端页面时，可按原 hmdp 的 Nginx 目录启动方式使用。

## 常见报错

### MySQL 端口被占用

现象：`Bind for 0.0.0.0:3307 failed`。

处理：停止本机已有 MySQL，或改端口启动：

```powershell
$env:MYSQL_PORT="13307"
docker compose up -d mysql
```

同时启动 Spring Boot 前设置：

```powershell
$env:MYSQL_PORT="13307"
```

### MySQL 登录失败

现象：`Access denied for user 'root'`。

处理：确认 `application-local.yml` 中默认密码是 `locallifehub_root`。如果曾经用其他密码初始化过数据卷，需要使用旧密码，或执行 `docker compose down -v` 后重新初始化。

### Redis NOAUTH 或 WRONGPASS

现象：`NOAUTH Authentication required` 或 `WRONGPASS invalid username-password pair`。

处理：确认 Spring Boot 使用 `local` profile，并且 `REDIS_PASSWORD` 与 Compose 默认值一致。默认密码是 `locallifehub_redis`。

如果本机已经有 Redis 占用 `6380`，可以改用其他宿主端口，例如：

```powershell
$env:REDIS_PORT="6381"
docker compose up -d redis
```

启动 Spring Boot 前也要使用同一个端口：

```powershell
$env:REDIS_PORT="6381"
```

### Redis Stream NOGROUP

现象：如果运行旧版 Redis Stream 秒杀实现，启动后可能出现：

```text
NOGROUP No such key 'stream.orders' or consumer group 'g1' in XREADGROUP with GROUP option
```

处理：执行创建消费组命令：

```bash
docker compose exec redis redis-cli -a locallifehub_redis XGROUP CREATE stream.orders g1 '$' MKSTREAM
```

### RocketMQ broker 启动失败

先看日志：

```bash
docker compose logs -f rocketmq-broker
```

常见原因是端口 `10909`、`10911`、`10912` 被占用。可通过 `ROCKETMQ_BROKER_PORT` 等环境变量改端口。当前秒杀下单链路已经接入 RocketMQ，运行接口前需要确保 NameServer 和 Broker 可用；`mvn compile` 不依赖 RocketMQ 运行状态。

### RocketMQ Dashboard 打不开

默认地址是 `http://127.0.0.1:8088`。如果 8088 被占用，可以改端口：

```powershell
$env:ROCKETMQ_DASHBOARD_PORT="18088"
docker compose up -d rocketmq-dashboard
```

然后访问 `http://127.0.0.1:18088`。
