# AI Customer Service Phase 1: Streaming + Frontend

## Scope

Add streaming response (SSE) and a browser-based chat UI to the existing AI customer service. Phase 1 does NOT touch RAG, audit logging, or sensitive-content guardrails — those are deferred to Phase 2.

## Current State

- `POST /ai/customer-service/chat` — synchronous, non-streaming, returns complete JSON response
- LangChain4j Agent with `OpenAiChatModel`, two tools (`query_shop_info`, `query_voucher_info`), Redis-backed chat memory
- No frontend; API-only

## Design

### Architecture

```
Browser (ai-cs.html)                    Spring Boot
  │                                        │
  │  POST /ai/customer-service/chat/stream │
  │  Content-Type: application/json        │
  │  Accept: text/event-stream             │
  │ ──────────────────────────────────────>│
  │                                        │ AiCustomerService.chatStream()
  │                                        │   → streamingChatModel
  │                                        │   → Agent TokenStream
  │                                        │
  │  SSE: data: {"token":"...","done":false}
  │ <──────────────────────────────────────│
  │  SSE: data: {"token":"...","done":true} │
  │ <──────────────────────────────────────│
  │                                        │
```

### Backend Changes

**AiCustomerServiceImpl.java**
- Add `streamingChatModel` field (`OpenAiStreamingChatModel`, same baseUrl/apiKey/model/temperature as blocking model)
- Add `streamingAgent` field (same `AiServices` builder but with `streamingChatModel`)
- New method `chatStream(sessionId, message)`: returns `TokenStream`
- Streaming agent interface uses `TokenStream chat(@MemoryId String memoryId, @UserMessage String message)` — tools and memory work identically

**AiCustomerServiceController.java**
- New endpoint `POST /ai/customer-service/chat/stream`
- Same `@RateLimit` guard (shares the same rate-limit key as /chat)
- Returns `SseEmitter`:
  1. Create `SseEmitter` with timeout from config
  2. Call `chatStream(memoryId, userMessage)`
  3. `tokenStream.onNext(token -> emitter.send(SSE event))`
  4. `tokenStream.onComplete(() -> emitter.complete())`
  5. `tokenStream.onError(error -> emitter.completeWithError(error))`
  6. SSEMitter timeout callback: clean up
- SSE event format: `data: {"token":"<text>","done":false}` with newline-separated tokens, final event `data: {"token":"","done":true}`

**application-local.yml**
- New config: `ai.customer-service.stream-timeout-ms` (default 60000)

### Frontend (ai-cs.html)

Static file at `src/main/resources/static/ai-cs.html`, served by Spring Boot at `/ai-cs.html`.

UI layout:
- Top bar: "邻享生活 AI 客服" title
- Middle: chat message list (scrollable, mobile-responsive)
- Bottom: text input + send button

Behavior:
- On page load: generate UUID sessionId, show welcome message
- On send: POST to `/ai/customer-service/chat/stream` with `{message, sessionId}`
- Read SSE stream via `fetch()` + `ReadableStream` reader (not EventSource — need POST with JSON body)
- Append tokens to message bubble in real-time, show "..." indicator while waiting
- On done: hide indicator
- On error: show "AI 客服暂时不可用"
- Messages stored in browser `sessionStorage` (per-tab, survive refresh)

CSS: embedded `<style>` block, simple clean design. No external dependencies (no npm, no CDN).

### Non-Goals (Phase 2)
- RAG knowledge base
- Tool call audit logging
- Sensitive-content guardrails
- Admin knowledge management UI

### Config Changes

```yaml
ai:
  customer-service:
    stream-timeout-ms: ${AI_CUSTOMER_SERVICE_STREAM_TIMEOUT_MS:60000}
```

### Testing

- Unit: `AiCustomerServiceImplTest` — mock streaming model, verify TokenStream flow
- Integration: start app, open `http://localhost:8081/ai-cs.html`, send message, verify streaming response
- Regression: existing `POST /ai/customer-service/chat` (non-streaming) must still work

### Files Changed

| File | Action |
|---|---|
| `static/ai-cs.html` | New — chat UI |
| `AiCustomerServiceImpl.java` | Add streaming agent + chatStream() |
| `AiCustomerServiceController.java` | Add /chat/stream endpoint |
| `IAiCustomerService.java` | Add chatStream() interface method |
| `application-local.yml` | Add stream-timeout-ms config |
