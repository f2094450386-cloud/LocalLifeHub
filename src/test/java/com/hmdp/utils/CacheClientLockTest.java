package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheClientLockTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void mutexShouldReleaseOnlyItsOwnToken() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq("lock:shop:1"),
                any(String.class),
                eq(RedisConstants.LOCK_SHOP_TTL),
                eq(TimeUnit.SECONDS)
        )).thenReturn(true);

        CacheClient cacheClient = new CacheClient(stringRedisTemplate);
        String value = cacheClient.queryWithMutex(
                "cache:shop:",
                "lock:shop:",
                1L,
                String.class,
                id -> "shop",
                1L,
                TimeUnit.MINUTES
        );

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(
                eq("lock:shop:1"),
                token.capture(),
                eq(RedisConstants.LOCK_SHOP_TTL),
                eq(TimeUnit.SECONDS)
        );
        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(Collections.singletonList("lock:shop:1")),
                eq(token.getValue())
        );
        assertThat(value).isEqualTo("shop");
        assertThat(token.getValue()).isNotBlank().isNotEqualTo("1");
    }
}
