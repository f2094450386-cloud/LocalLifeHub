package com.hmdp.controller;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.Result;
import com.hmdp.service.IManualReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/voucher-order-task")
@AdminOnly
public class VoucherOrderTaskController {

    @Resource
    private IManualReviewService manualReviewService;

    @GetMapping("manual-review")
    public Result listManualReviewTasks(@RequestParam(value = "limit", required = false) Integer limit) {
        return manualReviewService.listVoucherOrderTasks(limit);
    }

    @PostMapping("manual-review/{id}/retry")
    public Result retryManualReviewTask(@PathVariable("id") Long taskId) {
        return manualReviewService.retryVoucherOrderTask(taskId);
    }

    @PostMapping("manual-review/{id}/release-redis")
    public Result releaseManualReviewTaskRedis(@PathVariable("id") Long taskId) {
        return manualReviewService.releaseVoucherOrderTaskRedis(taskId);
    }
}
