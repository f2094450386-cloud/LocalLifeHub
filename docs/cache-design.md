# 商户查询缓存设计

## 接入范围

商户详情接口：

```text
GET /shop/{id}
```

当前默认使用 `CacheClient.queryWithMutex`：

1. 先查 Redis `cache:shop:{id}`。
2. 命中正常 JSON，直接返回。
3. 命中空字符串，说明数据库不存在该商户，直接返回空，防止缓存穿透。
4. 未命中时抢 Redis 互斥锁 `lock:shop:{id}`。
5. 抢锁成功后再次检查缓存，仍未命中才查 MySQL 并重建缓存。
6. 抢锁失败的线程短暂等待后重试，避免大量请求同时打到 MySQL。

热点商户逻辑过期预热接口：

```text
POST /shop/{id}/cache/preheat
```

该接口会把商户详情写入 `cache:shop:{id}`，数据结构为 `data + expireTime`，后续可通过 `CacheClient.queryWithLogicalExpire` 读取并异步重建热点缓存。

## 缓存穿透

问题：用户请求不存在的商户 id，Redis 没有缓存，每次都访问 MySQL。

处理：数据库查询为空时，写入空字符串缓存：

```text
key: cache:shop:{id}
value: ""
ttl: CACHE_NULL_TTL
```

后续请求命中空值后直接返回，不再访问 MySQL。空值 TTL 较短，避免后续真实新增同 id 数据时长时间不可见。

## 缓存击穿

问题：热点商户缓存过期瞬间，大量请求同时访问 MySQL。

当前详情接口默认方案：互斥锁重建。

- 只有拿到 `lock:shop:{id}` 的线程查询 MySQL 并写回 Redis。
- 其他线程等待后重试读取缓存。
- 写缓存使用随机 TTL，降低多个 key 同时过期的概率。

`CacheClient` 还保留了逻辑过期方案：`queryWithLogicalExpire`。该方案适合通过预热接口提前写入的热点商户：

- Redis 中存储 `data + expireTime`。
- 未逻辑过期时直接返回。
- 已逻辑过期时先返回旧数据，再由一个后台线程异步重建缓存。

逻辑过期牺牲短时间新鲜度换取高可用和低延迟，适合热点读，不适合强一致读。

## 缓存雪崩

问题：大量缓存同一时间失效，请求集中打到 MySQL。

处理：`CacheClient.set` 会在基础 TTL 上增加随机秒数：

```text
realTtl = baseTtl + random(0, CACHE_RANDOM_TTL_SECONDS)
```

商户详情基础 TTL 为 `CACHE_SHOP_TTL`，随机 TTL 上限为 `CACHE_RANDOM_TTL_SECONDS`，都在 `RedisConstants` 中统一维护。

## 缓存一致性

商户更新接口：

```text
PUT /shop
```

处理顺序：

1. 先更新 MySQL。
2. 事务提交后删除 `cache:shop:{id}`。
3. 再延迟 `CACHE_SHOP_DELAY_DELETE_SECONDS` 做第二次删除。

选择“更新数据库后删缓存”是因为缓存是派生数据，MySQL 是最终事实。事务提交后删除缓存，避免事务尚未提交时其他线程重建出旧缓存。延迟双删用于降低并发读写下旧值回写 Redis 的概率。

边界：数据库和 Redis 无法做到强一致，本方案保证最终一致。极端情况下，例如应用在提交后、删缓存前宕机，仍可能短暂读到旧缓存，后续可以通过订阅 binlog、消息重试或定时校验进一步增强。

## Redis Key 和 TTL

统一维护在 `RedisConstants`：

- `CACHE_SHOP_KEY = "cache:shop:"`
- `LOCK_SHOP_KEY = "lock:shop:"`
- `CACHE_SHOP_TTL`
- `CACHE_NULL_TTL`
- `CACHE_RANDOM_TTL_SECONDS`
- `CACHE_SHOP_LOGICAL_TTL`
- `CACHE_SHOP_DELAY_DELETE_SECONDS`

## 热点缓存实际接入

`POST /shop/{id}/cache/preheat` 会把热点商户写入 `cache:shop:hot:{id}`，数据结构是 `data + expireTime`。

`GET /shop/{id}` 查询时会先读热点逻辑过期缓存；如果未命中，再回退到普通 `cache:shop:{id}` 的互斥锁重建方案。商户更新后会同时删除普通缓存和热点缓存，并做延迟双删。
