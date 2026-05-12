# 当前接口分组

本文档记录当前从 hmdp 继承的接口分组。包名和路径仍沿用原项目，后续改造时再逐步补充请求参数、响应示例和 curl 用例。

## `/user`

- `POST /user/code`：发送手机验证码。
- `POST /user/login`：手机号验证码登录。
- `POST /user/logout`：登出，占位实现，当前返回未完成功能。
- `GET /user/me`：获取当前登录用户。
- `GET /user/info/{id}`：获取用户详情。
- `GET /user/{id}`：按 ID 查询用户摘要。
- `POST /user/sign`：用户签到。
- `GET /user/sign/count`：统计连续签到天数。

## `/shop`

- `GET /shop/{id}`：按 ID 查询商户详情，经过商户缓存逻辑。
- `POST /shop`：新增商户。
- `PUT /shop`：更新商户。
- `GET /shop/of/type`：按商户类型分页查询，可带地理坐标。
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
- `POST /voucher/seckill`：新增秒杀优惠券。
- `GET /voucher/list/{shopId}`：查询店铺优惠券列表。

## `/voucher-order`

- `POST /voucher-order/seckill/{id}`：秒杀下单，当前使用 Redis + Lua + Redis Stream。

## `/blog-comments`

- 当前只有 `/blog-comments` 控制器基础路径，尚未实现具体评论接口。
