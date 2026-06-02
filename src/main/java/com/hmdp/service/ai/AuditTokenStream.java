package com.hmdp.service.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;

import java.util.List;
import java.util.function.Consumer;

/**
 * Wraps a {@link TokenStream} to capture the streaming response for audit logging.
 *
 * <p>Receives an explicit {@link AiAuditService.AuditContext} so that [REQ] and [RESP]
 * share the same requestId without depending on ThreadLocal propagation (which is broken
 * across OkHttp async dispatcher threads used by LangChain4j 0.35.0).</p>
 *
 * <p><b>Tool calls during streaming are NOT captured.</b> LangChain4j executes
 * {@code @Tool} methods on OkHttp callback threads where no audit context is bound.
 * Full [TOOL] audit is only available on the sync {@code /chat} endpoint.</p>
 */
public class AuditTokenStream implements TokenStream {

    private final TokenStream delegate;
    private final AiAuditService auditService;
    private final AiAuditService.AuditContext ctx;
    private final StringBuilder responseBuilder = new StringBuilder();

    public AuditTokenStream(TokenStream delegate, AiAuditService auditService,
                            AiAuditService.AuditContext ctx) {
        this.delegate = delegate;
        this.auditService = auditService;
        this.ctx = ctx;
    }

    @Override
    public TokenStream onNext(Consumer<String> consumer) {
        delegate.onNext(token -> {
            responseBuilder.append(token);
            consumer.accept(token);
        });
        return this;
    }

    @Override
    public TokenStream onComplete(Consumer<Response<AiMessage>> consumer) {
        delegate.onComplete(response -> {
            auditService.logResponse(ctx, responseBuilder.toString());
            consumer.accept(response);
        });
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> consumer) {
        delegate.onError(error -> {
            auditService.logResponse(ctx, "stream error: " + error.getMessage());
            consumer.accept(error);
        });
        return this;
    }

    @Override
    public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
        delegate.onRetrieved(consumer);
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        delegate.ignoreErrors();
        return this;
    }

    @Override
    public void start() {
        delegate.start();
    }
}
