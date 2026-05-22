package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.mapper.VoucherOrderTaskMapper;
import com.hmdp.service.IVoucherOrderTaskService;
import com.hmdp.utils.VoucherOrderTaskStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class VoucherOrderTaskServiceImpl extends ServiceImpl<VoucherOrderTaskMapper, VoucherOrderTask>
        implements IVoucherOrderTaskService {

    private static final int FAIL_REASON_MAX_LENGTH = 512;

    @Value("${seckill.order-task.default-max-retry:5}")
    private Integer defaultMaxRetry;

    @Value("${seckill.order-task.retry-delay-seconds:60}")
    private Long retryDelaySeconds;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VoucherOrderTask createPendingTask(Long orderId, Long userId, Long voucherId) {
        VoucherOrderTask task = new VoucherOrderTask();
        task.setOrderId(orderId);
        task.setUserId(userId);
        task.setVoucherId(voucherId);
        task.setStatus(VoucherOrderTaskStatus.PENDING);
        task.setRetryCount(0);
        task.setMaxRetry(defaultMaxRetry);
        task.setNextRetryTime(LocalDateTime.now());
        save(task);
        return task;
    }

    @Override
    public VoucherOrderTask queryByOrderId(Long orderId) {
        return lambdaQuery()
                .eq(VoucherOrderTask::getOrderId, orderId)
                .one();
    }

    @Override
    public boolean markSent(Long orderId, String messageId) {
        return markSent(orderId, messageId, false);
    }

    @Override
    public boolean markSent(Long orderId, String messageId, boolean increaseRetry) {
        LambdaUpdateWrapper<VoucherOrderTask> wrapper = new LambdaUpdateWrapper<VoucherOrderTask>()
                .eq(VoucherOrderTask::getOrderId, orderId)
                .set(VoucherOrderTask::getStatus, VoucherOrderTaskStatus.SENT)
                .set(VoucherOrderTask::getMessageId, messageId)
                .set(VoucherOrderTask::getLastSendTime, LocalDateTime.now())
                .set(VoucherOrderTask::getFailReason, null)
                .set(VoucherOrderTask::getNextRetryTime, nextRetryTime());
        if (increaseRetry) {
            wrapper.setSql("retry_count = retry_count + 1");
        }
        return update(wrapper);
    }

    @Override
    public boolean markConsumed(Long orderId) {
        return update(new LambdaUpdateWrapper<VoucherOrderTask>()
                .eq(VoucherOrderTask::getOrderId, orderId)
                .set(VoucherOrderTask::getStatus, VoucherOrderTaskStatus.CONSUMED)
                .set(VoucherOrderTask::getFailReason, null));
    }

    @Override
    public boolean markFailed(Long orderId, String failReason, boolean increaseRetry) {
        LambdaUpdateWrapper<VoucherOrderTask> wrapper = new LambdaUpdateWrapper<VoucherOrderTask>()
                .eq(VoucherOrderTask::getOrderId, orderId)
                .set(VoucherOrderTask::getStatus, VoucherOrderTaskStatus.FAILED)
                .set(VoucherOrderTask::getFailReason, truncate(failReason))
                .set(VoucherOrderTask::getNextRetryTime, nextRetryTime());
        if (increaseRetry) {
            wrapper.setSql("retry_count = retry_count + 1");
        }
        return update(wrapper);
    }

    @Override
    public boolean markManualReview(Long taskId, String failReason) {
        return update(new LambdaUpdateWrapper<VoucherOrderTask>()
                .eq(VoucherOrderTask::getId, taskId)
                .set(VoucherOrderTask::getStatus, VoucherOrderTaskStatus.MANUAL_REVIEW)
                .set(VoucherOrderTask::getFailReason, truncate(failReason)));
    }

    @Override
    public boolean markResolved(Long taskId, String reason) {
        return update(new LambdaUpdateWrapper<VoucherOrderTask>()
                .eq(VoucherOrderTask::getId, taskId)
                .eq(VoucherOrderTask::getStatus, VoucherOrderTaskStatus.MANUAL_REVIEW)
                .set(VoucherOrderTask::getStatus, VoucherOrderTaskStatus.RESOLVED)
                .set(VoucherOrderTask::getFailReason, truncate(reason)));
    }

    @Override
    public List<VoucherOrderTask> listDueTasks(LocalDateTime now, int limit) {
        int queryLimit = Math.max(1, limit);
        return list(new LambdaQueryWrapper<VoucherOrderTask>()
                .in(VoucherOrderTask::getStatus, VoucherOrderTaskStatus.PENDING, VoucherOrderTaskStatus.FAILED, VoucherOrderTaskStatus.SENT)
                .and(wrapper -> wrapper.le(VoucherOrderTask::getNextRetryTime, now)
                        .or()
                        .isNull(VoucherOrderTask::getNextRetryTime))
                .orderByAsc(VoucherOrderTask::getUpdateTime)
                .last("LIMIT " + queryLimit));
    }

    private LocalDateTime nextRetryTime() {
        return LocalDateTime.now().plusSeconds(retryDelaySeconds);
    }

    private String truncate(String failReason) {
        if (failReason == null) {
            return null;
        }
        return failReason.length() > FAIL_REASON_MAX_LENGTH
                ? failReason.substring(0, FAIL_REASON_MAX_LENGTH)
                : failReason;
    }
}
