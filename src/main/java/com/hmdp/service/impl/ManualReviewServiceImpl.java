package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.service.IManualReviewService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderTaskService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RocketMqConstants;
import com.hmdp.utils.VoucherOrderTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setTaskId(task.getId());
        message.setOrderId(task.getOrderId());
        message.setUserId(task.getUserId());
        message.setVoucherId(task.getVoucherId());
        SendResult sendResult = rocketMQTemplate.syncSend(RocketMqConstants.VOUCHER_ORDER_TOPIC, message);
        if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
            voucherOrderTaskService.markFailed(task.getOrderId(), "manual retry MQ send status: " + sendResult.getSendStatus(), true);
            return Result.fail("人工重投失败：" + sendResult.getSendStatus());
        }

        voucherOrderTaskService.markSent(task.getOrderId(), sendResult.getMsgId(), true);
        log.warn("人工重投秒杀订单任务，taskId={}, orderId={}, userId={}, voucherId={}, messageId={}",
                task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), sendResult.getMsgId());
        return Result.ok(sendResult.getMsgId());
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
        if (voucherOrderService.getById(task.getOrderId()) != null) {
            return Result.fail("订单已存在，不能释放 Redis 资格");
        }

        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + task.getVoucherId());
        stringRedisTemplate.opsForSet().remove(RedisConstants.SECKILL_ORDER_KEY + task.getVoucherId(), task.getUserId().toString());
        voucherOrderTaskService.markResolved(taskId, "manual released redis qualification");
        log.warn("人工释放秒杀 Redis 资格，taskId={}, orderId={}, userId={}, voucherId={}",
                task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId());
        return Result.ok(taskId);
    }
}
