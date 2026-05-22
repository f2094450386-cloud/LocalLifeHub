package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.VoucherOrderTask;

import java.time.LocalDateTime;
import java.util.List;

public interface IVoucherOrderTaskService extends IService<VoucherOrderTask> {

    VoucherOrderTask createPendingTask(Long orderId, Long userId, Long voucherId);

    VoucherOrderTask queryByOrderId(Long orderId);

    boolean markSent(Long orderId, String messageId);

    boolean markSent(Long orderId, String messageId, boolean increaseRetry);

    boolean markConsumed(Long orderId);

    boolean markFailed(Long orderId, String failReason, boolean increaseRetry);

    boolean markManualReview(Long taskId, String failReason);

    boolean markResolved(Long taskId, String reason);

    List<VoucherOrderTask> listDueTasks(LocalDateTime now, int limit);
}
