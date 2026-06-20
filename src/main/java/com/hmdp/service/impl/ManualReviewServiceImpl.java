package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.service.IManualReviewService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderTaskService;
import com.hmdp.utils.RocketMqConstants;
import com.hmdp.utils.VoucherOrderTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Service
public class ManualReviewServiceImpl implements IManualReviewService {

    @Resource
    private IVoucherOrderTaskService voucherOrderTaskService;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Override
    public Result listVoucherOrderTasks(Integer limit) {
        int queryLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<VoucherOrderTask> tasks = voucherOrderTaskService.lambdaQuery()
                .eq(VoucherOrderTask::getStatus, VoucherOrderTaskStatus.MANUAL_REVIEW)
                .orderByAsc(VoucherOrderTask::getUpdateTime)
                .last("LIMIT " + queryLimit)
                .list();
        return Result.ok(tasks, (long) tasks.size());
    }

    @Override
    public Result retryVoucherOrderTask(Long taskId) {
        VoucherOrderTask task = voucherOrderTaskService.getById(taskId);
        if (task == null) {
            return Result.fail("人工处理任务不存在");
        }
        if (!VoucherOrderTaskStatus.MANUAL_REVIEW.equals(task.getStatus())) {
            return Result.fail("只有 MANUAL_REVIEW 任务允许人工重投");
        }
        if (!voucherOrderTaskService.claimManualRetry(taskId)) {
            return Result.fail("任务状态已变化，请刷新后重试");
        }

        try {
            VoucherOrderMessage message = new VoucherOrderMessage();
            message.setTaskId(task.getId());
            message.setOrderId(task.getOrderId());
            message.setUserId(task.getUserId());
            message.setVoucherId(task.getVoucherId());
            SendResult sendResult = rocketMQTemplate.syncSend(RocketMqConstants.VOUCHER_ORDER_TOPIC, message);
            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                log.warn("人工重投发送结果待确认，保留 PROCESSING 等待消费或补偿，taskId={}, orderId={}, sendStatus={}",
                        taskId, task.getOrderId(), sendResult.getSendStatus());
                return Result.ok(taskId);
            }

            voucherOrderTaskService.markSent(task.getOrderId(), sendResult.getMsgId(), false);
            log.warn("人工重投秒杀订单任务，taskId={}, orderId={}, userId={}, voucherId={}, messageId={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), sendResult.getMsgId());
            return Result.ok(sendResult.getMsgId());
        } catch (Exception e) {
            log.warn("人工重投发送结果不确定，保留 PROCESSING 等待消费或补偿，taskId={}, orderId={}, error={}",
                    taskId, task.getOrderId(), e.getMessage());
            return Result.ok(taskId);
        }
    }

    @Override
    public Result releaseVoucherOrderTaskRedis(Long taskId) {
        VoucherOrderTask task = voucherOrderTaskService.getById(taskId);
        if (task == null) {
            return Result.fail("人工处理任务不存在");
        }
        if (VoucherOrderTaskStatus.RESOLVED.equals(task.getStatus())) {
            return Result.ok(taskId);
        }
        if (!VoucherOrderTaskStatus.MANUAL_REVIEW.equals(task.getStatus())) {
            return Result.fail("只有 MANUAL_REVIEW 任务允许人工释放 Redis 资格");
        }
        if (!voucherOrderTaskService.claimForRelease(taskId, VoucherOrderTaskStatus.MANUAL_REVIEW)) {
            return Result.fail("任务状态已变化，请刷新后重试");
        }
        if (voucherOrderService.getById(task.getOrderId()) != null) {
            voucherOrderTaskService.markConsumed(task.getOrderId());
            return Result.fail("订单已存在，不能释放 Redis 资格");
        }

        try {
            Long releaseResult = voucherOrderService.releaseRedisReservation(
                    task.getOrderId(),
                    task.getVoucherId(),
                    task.getUserId()
            );
            voucherOrderTaskService.markResolved(taskId, "manual released redis qualification, result " + releaseResult);
            log.warn("人工释放秒杀 Redis 资格，taskId={}, orderId={}, userId={}, voucherId={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId());
            return Result.ok(taskId);
        } catch (Exception e) {
            voucherOrderTaskService.markManualReview(taskId, "manual release failed: " + e.getMessage());
            log.error("人工释放秒杀 Redis 资格失败，taskId={}, orderId={}", taskId, task.getOrderId(), e);
            return Result.fail("Redis 资格释放失败");
        }
    }
}
