package com.hmdp.service;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import dev.langchain4j.service.TokenStream;

public interface IAiCustomerService {

    Result chat(AiChatRequest request);

    TokenStream chatStream(String sessionId, String message);
}
