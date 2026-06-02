package com.hmdp.controller;

import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiCustomerService;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/ai/customer-service")
public class AiCustomerServiceController {

    @Resource
    private IAiCustomerService aiCustomerService;

    @Value("${ai.customer-service.stream-timeout-ms:60000}")
    private Long streamTimeoutMs;

    @PostMapping("/chat")
    @RateLimit(
            key = "'ai:chat:' + T(com.hmdp.utils.UserHolder).getUser().getId()",
            windowSeconds = 60,
            maxRequests = 20,
            message = "AI客服请求过于频繁，请稍后再试"
    )
    public Result chat(@RequestBody AiChatRequest request) {
        return aiCustomerService.chat(request);
    }

    @PostMapping("/chat/stream")
    @RateLimit(
            key = "'ai:chat:' + T(com.hmdp.utils.UserHolder).getUser().getId()",
            windowSeconds = 60,
            maxRequests = 20,
            message = "AI客服请求过于频繁，请稍后再试"
    )
    public SseEmitter chatStream(@RequestBody AiChatRequest request, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);

        if (request == null) {
            try {
                emitter.send(SseEmitter.event()
                        .data("{\"token\":\"\",\"done\":true,\"error\":\"咨询内容不能为空\"}"));
                emitter.complete();
            } catch (IOException e) {
                log.error("SSE null request error", e);
            }
            return emitter;
        }

        String requestMsg = request.getMessage();
        if (requestMsg == null) {
            requestMsg = "";
        }
        String sessionId = request.getSessionId() != null ? request.getSessionId().trim() : UUID.randomUUID().toString();

        TokenStream tokenStream;
        try {
            tokenStream = aiCustomerService.chatStream(sessionId, requestMsg);
        } catch (Exception e) {
            log.error("AI stream validation failed, sessionId={}", sessionId, e);
            try {
                emitter.send(SseEmitter.event()
                        .data("{\"token\":\"\",\"done\":true,\"sessionId\":\"" + escapeJson(sessionId) + "\",\"error\":\"" + escapeJson(e.getMessage()) + "\"}"));
                emitter.complete();
            } catch (IOException ex) {
                log.error("SSE validation error send failed, sessionId={}", sessionId, ex);
            }
            return emitter;
        }

        tokenStream
                .onNext(token -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .data("{\"token\":\"" + escapeJson(token) + "\",\"done\":false}"));
                    } catch (IOException e) {
                        log.error("SSE send error, sessionId={}", sessionId, e);
                    }
                })
                .onComplete(ignored -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .data("{\"token\":\"\",\"done\":true,\"sessionId\":\"" + escapeJson(sessionId) + "\"}"));
                        emitter.complete();
                    } catch (IOException e) {
                        log.error("SSE complete error, sessionId={}", sessionId, e);
                    }
                })
                .onError(error -> {
                    log.error("AI stream error, sessionId={}", sessionId, error);
                    try {
                        emitter.send(SseEmitter.event()
                                .data("{\"token\":\"\",\"done\":true,\"sessionId\":\"" + escapeJson(sessionId) + "\",\"error\":\"AI客服暂时不可用，请稍后再试\"}"));
                        emitter.complete();
                    } catch (IOException e) {
                        log.error("SSE error handler failed, sessionId={}", sessionId, e);
                    }
                })
                .start();

        return emitter;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
