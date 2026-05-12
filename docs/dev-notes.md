# 开发说明

## 配置文件

主配置文件位于 `src/main/resources/application.yaml`。当前使用环境变量覆盖本地连接信息，避免把数据库或 Redis 密码写入仓库。

## 环境变量

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | Spring Boot 服务端口 |
| `MYSQL_HOST` | `127.0.0.1` | MySQL 主机 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_DATABASE` | `hmdp` | MySQL 数据库名 |
| `MYSQL_USERNAME` | `root` | MySQL 用户名 |
| `MYSQL_PASSWORD` | 空 | MySQL 密码 |
| `REDIS_HOST` | `localhost` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码；本地无密码时可不设置 |

PowerShell 示例：

```powershell
$env:MYSQL_HOST="127.0.0.1"
$env:MYSQL_PORT="3306"
$env:MYSQL_DATABASE="hmdp"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="your_mysql_password"
$env:REDIS_HOST="localhost"
$env:REDIS_PORT="6379"
$env:REDIS_PASSWORD=""
```

## 数据库

当前仍使用原始 `hmdp.sql`。本轮不修改 SQL、不新增表。首次启动前需要手动创建并导入该脚本。

## Redis Stream

秒杀订单消费者启动后会读取 `stream.orders`，消费组为 `g1`，消费者为 `c1`。首次启动前执行：

```text
XGROUP CREATE stream.orders g1 $ MKSTREAM
```

如果 Redis 已存在该 Stream 或消费组，重复执行会报 `BUSYGROUP`，可以忽略或先检查消费组。

## 编译验证

```bash
mvn -q -DskipTests compile
```
