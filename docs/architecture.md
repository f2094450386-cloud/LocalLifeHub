# 当前架构说明

## 项目定位

邻享生活 LocalLifeHub 当前以黑马点评 hmdp 为后端底座，保持原有 Spring Boot 单体结构。现阶段目标是保留可运行基线，后续再按模块渐进式改造。

## 分层结构

- `controller`：HTTP 接口入口，当前暴露用户、商户、探店、优惠券等 REST 接口。
- `service` / `service.impl`：业务接口与实现，承载登录、缓存查询、秒杀下单等核心流程。
- `mapper`：MyBatis-Plus 数据访问层。
- `entity`：数据库实体，对应 `hmdp.sql` 中的原始表。
- `dto`：接口返回、登录表单、用户摘要等数据传输对象。
- `config`：MVC、MyBatis-Plus、Redisson 和全局异常配置。
- `utils`：Redis Key 常量、分布式锁、ID 生成器、用户上下文、缓存客户端等工具。

## 已继承模块

### 用户登录

`/user` 分组继承手机号验证码登录、登录态查询、用户信息查询和签到统计。登录 token、验证码、签到位图等能力依赖 Redis。

### 商户查询

`/shop` 分组提供按 ID 查询、按类型分页查询、按名称搜索、新增和更新商户。当前 `ShopServiceImpl` 已包含 Redis 缓存查询相关实现。

### 商户类型

`/shop-type/list` 查询商户分类列表，适合作为首页分类导航数据源。

### 上传

`/upload` 分组提供探店图片上传与删除，仍沿用原 hmdp 本地文件路径配置。

### 探店笔记

`/blog` 分组提供发布笔记、热门笔记、个人笔记、按用户查询、关注流查询和笔记详情查询。

### 关注

`/follow` 分组提供关注/取关、关注状态查询、共同关注查询。共同关注使用 Redis Set 思路。

### 点赞

探店笔记点赞在 `/blog/like/{id}`，点赞用户列表在 `/blog/likes/{id}`，使用 Redis Sorted Set 维护点赞记录和排序。

### 优惠券

`/voucher` 分组支持新增普通券、新增秒杀券、查询店铺优惠券列表。秒杀券数据仍落在原始 `tb_voucher` 与 `tb_seckill_voucher`。

### 秒杀订单

`/voucher-order/seckill/{id}` 触发秒杀下单。当前保留原 hmdp 的 Redis + Lua 预扣库存、一人一单校验、Redis Stream 异步消费和数据库落单逻辑。

### Redis 缓存

当前代码已包含缓存穿透、互斥锁、逻辑过期、Redis 分布式锁、Redis ID 生成器、签到 Bitmap、点赞 ZSet、关注 Set、秒杀库存 Key 等 Redis 使用场景。

## 当前边界

本轮未引入 RocketMQ、LangChain4j、AOP 限流，也未新增数据库表或修改秒杀核心逻辑。`com.hmdp` 包名暂时保留，后续如要重命名包，应单独安排一次可回滚的重构。
