package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiChatResponse;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IAiCustomerService;
import com.hmdp.service.ai.KnowledgeBaseService;
import com.hmdp.service.ai.LocalLifeHubAiTools;
import com.hmdp.service.ai.RedisChatMemoryStore;
import com.hmdp.utils.UserHolder;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class AiCustomerServiceImpl implements IAiCustomerService {

    @Value("${ai.customer-service.base-url:}")
    private String baseUrl;

    @Value("${ai.customer-service.api-key:}")
    private String apiKey;

    @Value("${ai.customer-service.model:}")
    private String model;

    @Value("${ai.customer-service.timeout-ms:10000}")
    private Integer timeoutMs;

    @Value("${ai.customer-service.max-memory-messages:20}")
    private Integer maxMemoryMessages;

    @Value("${ai.customer-service.stream-timeout-ms:60000}")
    private Integer streamTimeoutMs;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private LocalLifeHubAiTools localLifeHubAiTools;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    private volatile CustomerServiceAgent customerServiceAgent;
    private volatile StreamingCustomerServiceAgent streamingCustomerServiceAgent;

    @Override
    public Result chat(AiChatRequest request) {
        if (request == null || StrUtil.isBlank(request.getMessage())) {
            return Result.fail("咨询内容不能为空");
        }
        String configError = validateConfig();
        if (configError != null) {
            return Result.fail(configError);
        }

        String sessionId = resolveSessionId(request.getSessionId());
        String memoryId = buildMemoryId(sessionId);
        try {
            String answer = getOrCreateAgent().chat(memoryId, buildUserMessage(request.getMessage().trim()));
            if (StrUtil.isBlank(answer)) {
                answer = "未查询到相关数据";
            }
            return Result.ok(new AiChatResponse(sessionId, answer));
        } catch (Exception e) {
            log.error("AI customer service request exception, sessionId={}", sessionId, e);
            return Result.fail("AI 客服暂时不可用，请稍后再试");
        }
    }

    @Override
    public TokenStream chatStream(String sessionId, String message) {
        if (StrUtil.isBlank(message)) {
            throw new IllegalArgumentException("咨询内容不能为空");
        }
        String configError = validateConfig();
        if (configError != null) {
            throw new IllegalStateException(configError);
        }
        String memoryId = buildMemoryId(resolveSessionId(sessionId));
        return getOrCreateStreamingAgent().chat(memoryId, buildUserMessage(message.trim()));
    }

    private String buildUserMessage(String message) {
        String knowledgeContext = knowledgeBaseService.search(message);
        String referenceContext = localLifeHubAiTools.buildReferenceContext(message);

        boolean hasKnowledge = !StrUtil.isBlank(knowledgeContext);
        boolean hasReference = !StrUtil.isBlank(referenceContext);

        if (!hasKnowledge && !hasReference) {
            return message;
        }

        StringBuilder sb = new StringBuilder();
        if (hasKnowledge) {
            sb.append("以下是平台规则知识库中与用户问题相关的条目，回答规则类问题时只能基于这些内容，不要编造规则：\n");
            sb.append(knowledgeContext);
        }
        if (hasReference) {
            sb.append("以下是系统预查询到的候选商户和优惠券数据。回答时只能基于这些数据或工具返回数据；");
            sb.append("如果用户问优惠券且 vouchers 为空，请回答未查询到相关数据。\n");
            sb.append(referenceContext);
            sb.append("\n");
        }
        sb.append("用户问题：\n").append(message);
        return sb.toString();
    }

    private CustomerServiceAgent getOrCreateAgent() {
        CustomerServiceAgent agent = customerServiceAgent;
        if (agent != null) {
            return agent;
        }
        synchronized (this) {
            if (customerServiceAgent == null) {
                OpenAiChatModel chatModel = OpenAiChatModel.builder()
                        .baseUrl(normalizeBaseUrl(baseUrl))
                        .apiKey(apiKey)
                        .modelName(model)
                        .temperature(0.2)
                        .timeout(Duration.ofMillis(timeoutMs == null ? 10000 : timeoutMs))
                        .maxRetries(1)
                        .build();

                customerServiceAgent = AiServices.builder(CustomerServiceAgent.class)
                        .chatLanguageModel(chatModel)
                        .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                                .id(memoryId)
                                .maxMessages(maxMemoryMessages == null ? 20 : maxMemoryMessages)
                                .chatMemoryStore(redisChatMemoryStore)
                                .build())
                        .tools(localLifeHubAiTools)
                        .build();
            }
            return customerServiceAgent;
        }
    }

    private StreamingCustomerServiceAgent getOrCreateStreamingAgent() {
        StreamingCustomerServiceAgent agent = streamingCustomerServiceAgent;
        if (agent != null) {
            return agent;
        }
        synchronized (this) {
            if (streamingCustomerServiceAgent == null) {
                OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                        .baseUrl(normalizeBaseUrl(baseUrl))
                        .apiKey(apiKey)
                        .modelName(model)
                        .temperature(0.2)
                        .timeout(Duration.ofMillis(timeoutMs == null ? 10000 : timeoutMs))
                        .build();

                streamingCustomerServiceAgent = AiServices.builder(StreamingCustomerServiceAgent.class)
                        .streamingChatLanguageModel(streamingChatModel)
                        .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                                .id(memoryId)
                                .maxMessages(maxMemoryMessages == null ? 20 : maxMemoryMessages)
                                .chatMemoryStore(redisChatMemoryStore)
                                .build())
                        .tools(localLifeHubAiTools)
                        .build();
            }
            return streamingCustomerServiceAgent;
        }
    }

    private String validateConfig() {
        if (StrUtil.isBlank(apiKey)) {
            return "AI 客服未配置 LOCAL_LIFEHUB_LLM_API_KEY";
        }
        if (StrUtil.isBlank(baseUrl)) {
            return "AI 客服未配置 LOCAL_LIFEHUB_LLM_BASE_URL";
        }
        if (StrUtil.isBlank(model)) {
            return "AI 客服未配置 LOCAL_LIFEHUB_LLM_MODEL";
        }
        return null;
    }

    private String resolveSessionId(String sessionId) {
        if (StrUtil.isNotBlank(sessionId)) {
            return sessionId.trim();
        }
        return UUID.randomUUID().toString();
    }

    private String buildMemoryId(String sessionId) {
        UserDTO user = UserHolder.getUser();
        Long userId = user == null ? 0L : user.getId();
        return userId + ":" + sessionId;
    }

    private String normalizeBaseUrl(String configuredBaseUrl) {
        String value = configuredBaseUrl.trim();
        String suffix = "/chat/completions";
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }

    private interface CustomerServiceAgent {

        @SystemMessage({
                "你是邻享生活 LocalLifeHub 的本地生活 AI 客服。",
                "用户询问商户或优惠券信息时，必须先调用工具查询系统数据，再基于工具返回结果回答。",
                "用户询问平台规则、退款规则、秒杀规则、商户入驻等问题时，优先使用消息中提供的规则知识库内容回答。",
                "如果用户消息中包含系统预查询到的候选商户和优惠券数据，可以直接使用该数据回答。",
                "你没有订单查询工具，无法查询任何用户的订单信息。遇到订单相关问题时，必须说明当前不支持订单查询，建议联系人工客服。",
                "遇到退款、支付、投诉等超出系统能力的问题，说明当前仅能提供规则说明，建议联系人工客服处理。",
                "不要编造任何不存在的店铺、地址、营业时间、评分、优惠券、库存、使用规则或订单状态。",
                "不要假装能查询用户个人信息或其他用户的订单、购买记录。",
                "如果工具返回未查询到相关数据，且规则知识库也没有相关内容，必须明确回答未查询到相关数据。",
                "回答使用简洁中文，优先给出和用户问题最相关的信息。"
        })
        String chat(@MemoryId String memoryId, @UserMessage String message);
    }

    private interface StreamingCustomerServiceAgent {

        @SystemMessage({
                "你是邻享生活 LocalLifeHub 的本地生活 AI 客服。",
                "用户询问商户或优惠券信息时，必须先调用工具查询系统数据，再基于工具返回结果回答。",
                "用户询问平台规则、退款规则、秒杀规则、商户入驻等问题时，优先使用消息中提供的规则知识库内容回答。",
                "如果用户消息中包含系统预查询到的候选商户和优惠券数据，可以直接使用该数据回答。",
                "你没有订单查询工具，无法查询任何用户的订单信息。遇到订单相关问题时，必须说明当前不支持订单查询，建议联系人工客服。",
                "遇到退款、支付、投诉等超出系统能力的问题，说明当前仅能提供规则说明，建议联系人工客服处理。",
                "不要编造任何不存在的店铺、地址、营业时间、评分、优惠券、库存、使用规则或订单状态。",
                "不要假装能查询用户个人信息或其他用户的订单、购买记录。",
                "如果工具返回未查询到相关数据，且规则知识库也没有相关内容，必须明确回答未查询到相关数据。",
                "回答使用简洁中文，优先给出和用户问题最相关的信息。"
        })
        TokenStream chat(@MemoryId String memoryId, @UserMessage String message);
    }
}
