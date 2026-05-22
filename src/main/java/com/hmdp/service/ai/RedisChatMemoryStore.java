package com.hmdp.service.ai;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.AI_CUSTOMER_SERVICE_MEMORY_KEY;
import static com.hmdp.utils.RedisConstants.AI_CUSTOMER_SERVICE_MEMORY_TTL;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = stringRedisTemplate.opsForValue().get(buildKey(memoryId));
        if (StrUtil.isBlank(json)) {
            return Collections.emptyList();
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // Redis key shape: ai:customer-service:memory:{userId}:{sessionId}
        String key = buildKey(memoryId);
        String json = ChatMessageSerializer.messagesToJson(messages);
        stringRedisTemplate.opsForValue().set(key, json, AI_CUSTOMER_SERVICE_MEMORY_TTL, TimeUnit.MINUTES);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(buildKey(memoryId));
    }

    private String buildKey(Object memoryId) {
        return AI_CUSTOMER_SERVICE_MEMORY_KEY + memoryId;
    }
}
