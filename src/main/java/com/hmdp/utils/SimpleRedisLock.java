package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 简单的 Redis 分布式锁。
 *
 * @author CHEN
 * @date 2022/10/09
 */
public class SimpleRedisLock implements ILock {
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    private static final String KET_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁，并设置过期时间避免服务异常导致死锁。
        Boolean isSuccess = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(KET_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isSuccess);
    }

    @Override
    public void unLock() {
        // 使用 Lua 脚本保证“判断锁标识”和“释放锁”的原子性。
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KET_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
