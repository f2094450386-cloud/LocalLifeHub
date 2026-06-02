package com.hmdp.mq;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderTaskService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RocketMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${seckill.order-task.compensate-limit:50}")
    private Integer compensateLimit;

    private static final DefaultRedisScript<Long> RELEASE_STOCK_SCRIPT;

    static {
        RELEASE_STOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_STOCK_SCRIPT.setLocation(new ClassPathResource("release_stock.lua"));
        RELEASE_STOCK_SCRIPT.setResultType(Long.class);
    }

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
            releaseRedisIfOrderAbsent(task);
            voucherOrderTaskService.markManualReview(task.getId(), "retry count reached max retry: " + maxRetry);
            log.warn("秒杀补偿任务进入人工处理，taskId={}, orderId={}, userId={}, voucherId={}, retryCount={}, maxRetry={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), retryCount, maxRetry);
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
                voucherOrderTaskService.markSent(task.getOrderId(), sendResult.getMsgId(), true);
                log.info("秒杀补偿投递成功，taskId={}, orderId={}, userId={}, voucherId={}, messageId={}",
                        task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), sendResult.getMsgId());
                return;
            }
            voucherOrderTaskService.markFailed(task.getOrderId(), "compensate MQ send status: " + sendResult.getSendStatus(), true);
            log.error("秒杀补偿投递失败，taskId={}, orderId={}, userId={}, voucherId={}, sendStatus={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), sendResult.getSendStatus());
        } catch (Exception e) {
            voucherOrderTaskService.markFailed(task.getOrderId(), e.getMessage(), true);
            log.error("秒杀补偿投递异常，taskId={}, orderId={}, userId={}, voucherId={}, retryCount={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId(), retryCount, e);
        }
    }

    private void releaseRedisIfOrderAbsent(VoucherOrderTask task) {
        if (voucherOrderService.getById(task.getOrderId()) != null) {
            log.info("补偿任务转人工但订单已存在，不释放 Redis 资格，taskId={}, orderId={}", task.getId(), task.getOrderId());
            return;
        }
        Long result = stringRedisTemplate.execute(
                RELEASE_STOCK_SCRIPT,
                Collections.emptyList(),
                task.getVoucherId().toString(),
                task.getUserId().toString(),
                task.getOrderId().toString()
        );
        if (result != null && result == 1) {
            log.warn("补偿任务转人工且订单不存在，已释放 Redis 资格，taskId={}, orderId={}, userId={}, voucherId={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId());
        } else {
            log.info("Redis 资格已释放，跳过重复操作，taskId={}, orderId={}", task.getId(), task.getOrderId());
        }
    }
}
