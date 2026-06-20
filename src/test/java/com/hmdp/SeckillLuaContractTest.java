package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SeckillLuaContractTest {

    @Test
    void seckillScriptShouldValidateActivityWindowBeforeDecrementingStock() throws IOException {
        String script = readResource("seckill.lua");

        assertThat(script).contains("seckill:begin:", "seckill:end:", "redis.call('TIME')");
        assertThat(script.indexOf("redis.call('TIME')"))
                .isLessThan(script.indexOf("redis.call('incrby', stockKey, -1)"));
    }

    @Test
    void releaseScriptShouldOnlyRestoreStockWhenQualificationWasRemoved() throws IOException {
        String script = readResource("release_stock.lua");

        assertThat(script).contains("local removed = redis.call('srem', orderKey, userId)");
        assertThat(script.indexOf("local removed = redis.call('srem', orderKey, userId)"))
                .isLessThan(script.indexOf("redis.call('incrby', stockKey, 1)"));
        assertThat(script).contains("ARGV[4]");
    }

    private String readResource(String name) throws IOException {
        byte[] bytes = StreamUtils.copyToByteArray(
                getClass().getClassLoader().getResourceAsStream(name)
        );
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
