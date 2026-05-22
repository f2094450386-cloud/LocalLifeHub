package com.hmdp.controller;

import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiCustomerService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai/customer-service")
public class AiCustomerServiceController {

    @Resource
    private IAiCustomerService aiCustomerService;

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
}
