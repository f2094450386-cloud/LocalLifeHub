# 黑马点评底座导入记录

## 来源

- 来源仓库：https://github.com/cs001020/hmdp
- 直接 `git clone` 失败原因：当前环境连接 `github.com:443` 被重置/超时。
- 实际导入方式：下载 GitHub codeload 临时压缩包到系统临时目录后解压导入。

## 使用分支

- 后端底座：`master`，包含完整 Java 后端代码、`pom.xml`、`hmdp.sql`、`README.md`。
- 前端静态资源：`init`，仅补入 `src/main/resources/nginx-1.18.0`，因为原 README 说明前端资源位于该分支。

## 当前项目启动依赖

- JDK 8+。本次已通过 `mvn -q -DskipTests compile` 编译验证。
- Maven。
- MySQL，需要先执行根目录 `hmdp.sql` 创建 `hmdp` 库表及初始化数据。
- Redis，用于登录 token、缓存、分布式锁、秒杀库存与 Redis Stream 订单消息。
- Nginx 前端资源位于 `src/main/resources/nginx-1.18.0`，按原项目方式启动即可访问前端页面。

## 本地配置

`src/main/resources/application.yaml` 已改为环境变量占位，避免硬编码本地密码：

```powershell
$env:MYSQL_HOST="127.0.0.1"
$env:MYSQL_PORT="3306"
$env:MYSQL_DATABASE="hmdp"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="your_mysql_password"
$env:REDIS_HOST="localhost"
$env:REDIS_PORT="6379"
$env:REDIS_PASSWORD="your_redis_password"
```

如果本地 Redis 无密码，可以不设置 `REDIS_PASSWORD`。

## 已知启动问题

完整 `master` 分支启动后会读取 Redis Stream：`stream.orders`，消费者组为 `g1`，消费者名为 `c1`。如果消费组不存在，会出现类似错误：

```text
NOGROUP No such key 'stream.orders' or consumer group 'g1' in XREADGROUP with GROUP option
```

首次启动前可在 Redis 执行：

```text
XGROUP CREATE stream.orders g1 $ MKSTREAM
```

本次导入还做了少量编译/运行前置修复：

- 删除 `SimpleRedisLock` 中不可用且未使用的 JDK 内部包导入。
- 修复部分中文注释编码导致的代码被注释吞掉问题。
- 修复 `application.yaml` 中 `mybatis-plus`、`logging` 层级。
- 修复 `seckill.lua` 中 `xadd stream.orders` 被注释吞掉的问题。
