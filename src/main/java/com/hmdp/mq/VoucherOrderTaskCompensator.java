package com.hmdp.mq;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderTaskService;
import com.hmdp.utils.RocketMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 扫描本地订单任务表，补偿 Redis 已预扣但消息未可靠消费的秒杀任务。
 */
@Slf4j
@Component
public class VoucherOrderTaskCompensator {

    @Resource
    private IVoucherOrderTaskService voucherOrderTaskService;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Value("${seckill.order-task.compensate-limit:50}")
    private Integer compensateLimit;

    @Scheduled(fixedDelayString = "${seckill.order-task.compensate-interval-ms:30000}")
    public void compensate() {
        List<VoucherOrderTask> tasks = voucherOrderTaskService.listDueTasks(LocalDateTime.now(), compensateLimit);
        for (VoucherOrderTask task : tasks) {
            compensateOne(task);
        }
    }

    private void compensateOne(VoucherOrderTask task) {
        Integer retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        Integer maxRetry = task.getMaxRetry() == null ? 5 : task.getMaxRetry();
        log.info("扫描秒杀补偿任务，taskId={}, orderId={}, userId={}, voucherId={}, status={}, retryCount={}",
                task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), task.getStatus(), retryCount);

        if (retryCount >= maxRetry) {
            releaseAfterClaim(task, maxRetry);
            return;
        }
        if (!voucherOrderTaskService.claimForCompensation(task.getId(), task.getStatus())) {
            log.debug("秒杀补偿任务已被其他实例抢占，taskId={}, orderId={}", task.getId(), task.getOrderId());
            return;
        }

        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setTaskId(task.getId());
        message.setOrderId(task.getOrderId());
        message.setUserId(task.getUserId());
        message.setVoucherId(task.getVoucherId());

        try {
            SendResult sendResult = rocketMQTemplate.syncSend(RocketMqConstants.VOUCHER_ORDER_TOPIC, message);
            if (sendResult.getSendStatus() == SendStatus.SEND_OK) {
                voucherOrderTaskService.markSent(task.getOrderId(), sendResult.getMsgId(), false);
                log.info("秒杀补偿投递成功，taskId={}, orderId={}, userId={}, voucherId={}, messageId={}",
                        task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), sendResult.getMsgId());
                return;
            }
            voucherOrderTaskService.markFailed(task.getOrderId(), "compensate MQ send status: " + sendResult.getSendStatus(), false);
            log.error("秒杀补偿投递失败，taskId={}, orderId={}, userId={}, voucherId={}, sendStatus={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), sendResult.getSendStatus());
        } catch (Exception e) {
            voucherOrderTaskService.markFailed(task.getOrderId(), e.getMessage(), false);
            log.error("秒杀补偿投递异常，taskId={}, orderId={}, userId={}, voucherId={}, retryCount={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), retryCount, e);
        }
    }

    private void releaseAfterClaim(VoucherOrderTask task, Integer maxRetry) {
        if (!voucherOrderTaskService.claimForRelease(task.getId(), task.getStatus())) {
            log.debug("秒杀释放任务已被其他实例处理，taskId={}, orderId={}", task.getId(), task.getOrderId());
            return;
        }
        try {
            if (voucherOrderService.getById(task.getOrderId()) != null) {
                voucherOrderTaskService.markConsumed(task.getOrderId());
                log.info("补偿任务达到上限但订单已存在，标记为已消费，taskId={}, orderId={}",
                        task.getId(), task.getOrderId());
                return;
            }
            Long result = voucherOrderService.releaseRedisReservation(
                    task.getOrderId(),
                    task.getVoucherId(),
                    task.getUserId()
            );
            voucherOrderTaskService.markResolved(
                    task.getId(),
                    "retry count reached max retry " + maxRetry + ", redis release result " + result
            );
            log.warn("补偿任务达到上限且订单不存在，已释放 Redis 资格，taskId={}, orderId={}, result={}",
                    task.getId(), task.getOrderId(), result);
        } catch (Exception e) {
            voucherOrderTaskService.markManualReview(task.getId(), "auto release failed: " + e.getMessage());
            log.error("补偿任务自动释放失败，进入人工处理，taskId={}, orderId={}",
                    task.getId(), task.getOrderId(), e);
        }
    }
}
