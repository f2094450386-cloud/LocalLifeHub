package com.hmdp.service.ai;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AI conversation audit logger.
 *
 * <h3>Coverage</h3>
 * <ul>
 *   <li><b>/chat (sync):</b> full trace — [REQ] original message, [TOOL] name/params/result,
 *       [RESP] final answer — all with the same requestId.</li>
 *   <li><b>/chat/stream (SSE):</b> partial trace — [REQ] original message and [RESP] accumulated
 *       answer share the same requestId. Tool calls are <em>not</em> captured because LangChain4j
 *       0.35.0 executes them on OkHttp async dispatcher threads where ThreadLocal context is
 *       not available. This is a known boundary; see {@link AuditTokenStream}.</li>
 * </ul>
 *
 * <h3>Threading model</h3>
 * Sync path uses {@link #bindContext(AuditContext)} / {@link #unbindContext()} to make the
 * context available to {@link #logToolCall} via {@link ThreadLocal}. Streaming path passes the
 * {@link AuditContext} explicitly to {@link AuditTokenStream} and does not bind it.
 */
@Component
public class AiAuditService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    private final ThreadLocal<AuditContext> boundCtx = new ThreadLocal<>();

    /** Create context and log [REQ]. Caller decides whether to bind for tool audit. */
    public AuditContext beginRequest(String sessionId, String originalMessage) {
        AuditContext ctx = new AuditContext(sessionId, originalMessage);
        auditLog.info("[REQ] rid={} uid={} sid={} msg={}",
                ctx.requestId, ctx.userId, ctx.sessionId,
                truncate(ctx.originalMessage, 300));
        return ctx;
    }

    /** Bind context to current thread so tools can find it. Only for sync path. */
    public void bindContext(AuditContext ctx) {
        boundCtx.set(ctx);
    }

    /** Unbind after sync request completes. */
    public void unbindContext() {
        boundCtx.remove();
    }

    /**
     * Log a tool call. Only logs when context is bound to the current thread
     * (sync /chat path). Silently skipped for streaming async threads.
     */
    public void logToolCall(String toolName, String params, String result) {
        AuditContext ctx = boundCtx.get();
        if (ctx == null) {
            return;
        }
        auditLog.info("[TOOL] rid={} uid={} sid={} tool={} params={} result={}",
                ctx.requestId, ctx.userId, ctx.sessionId,
                toolName, params, truncate(result, 200));
    }

    /** Log final response with explicit context. */
    public void logResponse(AuditContext ctx, String answer) {
        auditLog.info("[RESP] rid={} uid={} sid={} answer={}",
                ctx.requestId, ctx.userId, ctx.sessionId,
                truncate(answer, 500));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }

    /** Immutable audit context carrying correlation id and original user input. */
    public static class AuditContext {
        public final String requestId;
        public final String sessionId;
        public final String originalMessage;
        public final Long userId;

        AuditContext(String sessionId, String originalMessage) {
            this.requestId = UUID.randomUUID().toString().substring(0, 8);
            this.sessionId = sessionId;
            this.originalMessage = originalMessage;
            UserDTO user = UserHolder.getUser();
            this.userId = user != null ? user.getId() : null;
        }
    }
}
