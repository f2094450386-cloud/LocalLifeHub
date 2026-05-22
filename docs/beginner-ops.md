# LocalLifeHub 新手操作手册

## 1. PowerShell 窗口怎么关闭

如果窗口正在运行 Spring Boot，也就是你看到类似：

```text
Started HmDianPingApplication
```

并且光标不回到 `PS D:\JavaNotes\LocalLifeHub>`，说明应用仍在运行。

推荐关闭方式：

1. 在运行 Spring Boot 的窗口按：

```text
Ctrl + C
```

2. PowerShell 会询问是否终止批处理任务时，输入：

```text
Y
```

3. 等窗口回到命令提示符后再关闭窗口。

也可以直接点右上角关闭窗口，但不如 `Ctrl + C` 干净。直接关闭通常也会结束 Spring Boot 进程，但不方便确认资源是否释放。

注意：关闭 Spring Boot 窗口不会自动停止 Docker 里的 MySQL、Redis、RocketMQ。

## 2. Docker 服务怎么停止

只停止容器，不删除数据：

```powershell
cd D:\JavaNotes\LocalLifeHub
docker compose down
```

下次再启动：

```powershell
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker
```

停止并删除本地数据卷，慎用：

```powershell
docker compose down -v
```

这会清空 MySQL、Redis、RocketMQ 的本地数据，下次启动会重新导入初始化 SQL。

## 3. 怎么查看当前项目占用了哪些端口

LocalLifeHub 默认端口：

| 服务 | 默认端口 | 用途 |
| --- | --- | --- |
| Spring Boot | `8081` | 后端接口 |
| MySQL | `3307` | 本地数据库 |
| Redis | `6380` | 本地缓存 |
| RocketMQ NameServer | `9876` | MQ 注册中心 |
| RocketMQ Broker | `10909`、`10911`、`10912` | MQ Broker |
| RocketMQ Dashboard | `8088` | MQ 控制台 |

查看 Docker 端口：

```powershell
docker compose ps
```

查看某个端口被谁占用：

```powershell
netstat -ano | findstr :8081
netstat -ano | findstr :3307
netstat -ano | findstr :6380
netstat -ano | findstr :9876
netstat -ano | findstr :8088
```

输出最后一列是 PID，例如：

```text
TCP    0.0.0.0:8081    0.0.0.0:0    LISTENING    26956
```

根据 PID 查进程：

```powershell
tasklist /FI "PID eq 26956"
```

如果确认要关闭某个进程：

```powershell
taskkill /PID 26956 /F
```

优先使用 `Ctrl + C` 关闭 Spring Boot，只有无法正常关闭时再用 `taskkill`。

## 4. 怎么判断前端页面连接的是哪个后端项目

常见判断方法有四种。

### 方法一：看前端配置的 API 地址

在前端项目目录里搜索：

```powershell
Select-String -Path .\* -Pattern "8081","8080","baseURL","axios","localhost","127.0.0.1" -Recurse
```

如果看到：

```text
http://127.0.0.1:8081
```

说明它连的是 LocalLifeHub 默认后端。

如果看到：

```text
http://127.0.0.1:8080
```

可能连的是另一个 hmdp 后端。

### 方法二：看浏览器 Network

1. 打开前端页面。
2. 按 `F12` 打开开发者工具。
3. 切到 `Network`。
4. 刷新页面或点击按钮。
5. 点开请求，看 Request URL。

例如：

```text
http://127.0.0.1:8081/shop/1
```

就是 LocalLifeHub。

### 方法三：看后端日志

请求前端页面后，观察哪个 Spring Boot 窗口有接口日志输出。哪个窗口在打印 SQL 或请求日志，就说明前端连到了哪个后端。

### 方法四：访问项目特有接口

LocalLifeHub 有一些普通 hmdp 没有的接口：

```text
POST /ai/customer-service/chat
GET /voucher-order-task/manual-review
POST /shop/{id}/cache/preheat
```

如果访问这些接口有响应，说明连到的是 LocalLifeHub。

## 5. 两个 hmdp 项目怎么避免冲突

不要让两个后端同时占用同一个端口。

LocalLifeHub 默认：

```text
8081
```

另一个 hmdp 可以设置成：

```powershell
$env:SERVER_PORT="8080"
mvn spring-boot:run
```

或者 LocalLifeHub 临时换端口：

```powershell
$env:SERVER_PORT="18081"
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

前端连接哪个项目，取决于前端配置的后端地址。

## 6. 常用启动顺序

```powershell
cd D:\JavaNotes\LocalLifeHub

docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker

$env:LOCAL_LIFEHUB_LLM_API_KEY="你的API Key"
$env:LOCAL_LIFEHUB_LLM_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:LOCAL_LIFEHUB_LLM_MODEL="qwen-plus"

mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

如果只是编译，不需要启动 Docker：

```powershell
mvn -q -DskipTests compile
```

## 7. 常用验证入口

- 核心接口命令：`docs/api-test.md`
- 本地运行说明：`docs/local-run.md`
- AI 客服说明：`docs/ai-customer-service.md`
- 简历版描述：`docs/resume-version.md`
