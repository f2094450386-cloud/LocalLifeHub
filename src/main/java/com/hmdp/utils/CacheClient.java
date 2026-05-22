package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_RANDOM_TTL_SECONDS;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;

    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), randomTtl(time, unit), TimeUnit.SECONDS);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * Empty-string cache prevents cache penetration for ids that do not exist in DB.
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R data = dbFallback.apply(id);
        if (data == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, data, time, unit);
        return data;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        String lockKey = lockKeyPrefix + id;
        boolean locked = false;
        try {
            locked = tryLock(lockKey);
            if (!locked) {
                sleepQuietly(50);
                return queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }

            String cachedAgain = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(cachedAgain)) {
                return JSONUtil.toBean(cachedAgain, type);
            }
            if (cachedAgain != null) {
                return null;
            }

            R data = dbFallback.apply(id);
            if (data == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            set(key, data, time, unit);
            return data;
        } finally {
            if (locked) {
                unlock(lockKey);
            }
        }
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        return queryWithLogicalExpire(keyPrefix, LOCK_SHOP_KEY, id, type, dbFallback, time, unit);
    }

    /**
     * Logical expire is intended for preheated hot keys. Expired data is returned first,
     * then one background thread rebuilds the cache under a Redis mutex.
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R data = BeanUtil.toBean(jsonObject, type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return data;
        }

        String lockKey = lockKeyPrefix + id;
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R freshData = dbFallback.apply(id);
                    if (freshData == null) {
                        stringRedisTemplate.delete(key);
                    } else {
                        setWithLogicalExpire(key, freshData, time, unit);
                    }
                } catch (Exception e) {
                    log.error("rebuild logical cache failed, key={}", key, e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return data;
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private long randomTtl(Long time, TimeUnit unit) {
        long baseSeconds = unit.toSeconds(time);
        long randomSeconds = CACHE_RANDOM_TTL_SECONDS <= 0
                ? 0
                : ThreadLocalRandom.current().nextLong(CACHE_RANDOM_TTL_SECONDS + 1);
        return baseSeconds + randomSeconds;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting cache mutex", e);
        }
    }
}
