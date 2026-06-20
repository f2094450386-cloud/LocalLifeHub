package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_RANDOM_TTL_SECONDS;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_WAIT_MILLIS;

@Slf4j
@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            4,
            4,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100),
            new ThreadPoolExecutor.AbortPolicy()
    );
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

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
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCK_SHOP_WAIT_MILLIS);
        while (true) {
            String lockToken = tryLock(lockKey);
            if (lockToken == null) {
                String cachedDuringWait = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(cachedDuringWait)) {
                    return JSONUtil.toBean(cachedDuringWait, type);
                }
                if (cachedDuringWait != null) {
                    return null;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    throw new IllegalStateException("等待缓存重建超时，key=" + key);
                }
                sleepQuietly(50);
                continue;
            }

            try {
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
                unlock(lockKey, lockToken);
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
        String lockToken = tryLock(lockKey);
        if (lockToken != null) {
            try {
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
                        unlock(lockKey, lockToken);
                    }
                });
            } catch (RejectedExecutionException e) {
                unlock(lockKey, lockToken);
                log.warn("cache rebuild queue is full, key={}", key);
            }
        }
        return data;
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, token, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag) ? token : null;
    }

    private void unlock(String key, String token) {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                token
        );
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
