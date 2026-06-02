# 当前接口分组

本文档记录当前 LocalLifeHub 源码中已经暴露的主要 HTTP 接口。路径来自 `src/main/java/com/hmdp/controller`，具体业务实现以后续源码讲解为准。

## `/user`

- `POST /user/code`：发送手机验证码，已接入 `@RateLimit`，同一手机号 60 秒 1 次。
- `POST /user/login`：手机号验证码登录。
- `POST /user/logout`：登出，占位实现，当前返回“功能未完成”。
- `GET /user/me`：获取当前登录用户。
- `GET /user/info/{id}`：获取用户详情。
- `GET /user/{id}`：按 ID 查询用户摘要。
- `POST /user/sign`：用户签到。
- `GET /user/sign/count`：统计连续签到天数。

## `/shop`

- `GET /shop/{id}`：按 ID 查询商户详情，先查热点逻辑过期缓存，再回退普通缓存互斥重建。
- `POST /shop`：新增商户。
- `PUT /shop`：更新商户，事务提交后删除普通缓存和热点缓存，并做延迟双删。
- `POST /shop/{id}/cache/preheat`：预热热点商户逻辑过期缓存。
- `GET /shop/of/type`：按商户类型分页查询；带 `x/y` 时使用 Redis GEO 附近商户查询。
- `GET /shop/of/name`：按名称关键词分页查询商户。

## `/shop-type`

- `GET /shop-type/list`：查询商户类型列表。

## `/upload`

- `POST /upload/blog`：上传探店图片。
- `GET /upload/blog/delete`：删除探店图片。

## `/blog`

- `POST /blog`：发布探店笔记。
- `PUT /blog/like/{id}`：点赞或取消点赞探店笔记。
- `GET /blog/of/me`：分页查询当前用户的探店笔记。
- `GET /blog/hot`：分页查询热门探店笔记。
- `GET /blog/{id}`：查询探店笔记详情。
- `GET /blog/likes/{id}`：查询笔记点赞用户。
- `GET /blog/of/user`：按用户 ID 分页查询探店笔记。
- `GET /blog/of/follow`：查询关注用户的笔记流。

## `/follow`

- `PUT /follow/{id}/{isFollow}`：关注或取关用户。
- `GET /follow/or/not/{id}`：查询是否已关注。
- `GET /follow/common/{id}`：查询共同关注。

## `/voucher`

- `POST /voucher`：新增普通优惠券。
- `POST /voucher/seckill`：新增秒杀优惠券，并初始化 Redis 秒杀库存。
- `GET /voucher/list/{shopId}`：查询店铺优惠券列表。

## `/voucher-order`

- `POST /voucher-order/seckill/{id}`：秒杀下单，当前主线是 Redis Lua 资格校验、本地任务表、RocketMQ 异步落库。
- `POST /voucher-order/pay/{id}`：最小支付状态接口，只允许订单所属用户把 `CREATED` 订单更新为 `PAID`。

## `/voucher-order-task`

- `GET /voucher-order-task/manual-review?limit=50`：查询进入人工处理状态的秒杀任务。
- `POST /voucher-order-task/manual-review/{id}/retry`：人工重投 MQ。
- `POST /voucher-order-task/manual-review/{id}/release-redis`：人工释放 Redis 秒杀资格，释放前会检查 MySQL 订单是否存在。

## `/ai/customer-service`

- `POST /ai/customer-service/chat`：AI 客服同步对话接口，返回完整 JSON 回答。
- `POST /ai/customer-service/chat/stream`：AI 客服流式对话接口（SSE），逐 token 推送回答，与 `/chat` 共享 `@RateLimit` 限流 key。
- 前端页面：`http://<host>/ai-cs.html`（Nginx 代理静态文件），位于 `nginx-1.18.0/html/hmdp/ai-cs.html`。

## `/blog-comments`

- 当前只有 `/blog-comments` 控制器基础路径，尚未实现具体评论接口。
