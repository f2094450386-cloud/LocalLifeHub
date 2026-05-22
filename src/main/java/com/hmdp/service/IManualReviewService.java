package com.hmdp.service;

import com.hmdp.dto.Result;

public interface IManualReviewService {

    Result listVoucherOrderTasks(Integer limit);

    Result retryVoucherOrderTask(Long taskId);

    Result releaseVoucherOrderTaskRedis(Long taskId);
}
