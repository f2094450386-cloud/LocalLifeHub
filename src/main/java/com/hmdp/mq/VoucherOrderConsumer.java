package com.hmdp.mq;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderTaskService;
import com.hmdp.utils.RocketMqConstants;
import com.hmdp.utils.VoucherOrderTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMqConstants.VOUCHER_ORDER_TOPIC,
        consumerGroup = RocketMqConstants.VOUCHER_ORDER_CONSUMER_GROUP
)
public class VoucherOrderConsumer implements RocketMQListener<VoucherOrderMessage> {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private IVoucherOrderTaskService voucherOrderTaskService;

    @Override
    public void onMessage(VoucherOrderMessage message) {
        VoucherOrderTask task = voucherOrderTaskService.queryByOrderId(message.getOrderId());
        if (task == null) {
            log.error("秒杀订单任务不存在，无法消费消息，message={}", message);
            throw new IllegalStateException("voucher order task not found, orderId=" + message.getOrderId());
        }
        if (VoucherOrderTaskStatus.CONSUMED.equals(task.getStatus())) {
            log.info("秒杀订单任务已消费，跳过重复消息，taskId={}, orderId={}", task.getId(), task.getOrderId());
            return;
        }
        if (VoucherOrderTaskStatus.MANUAL_REVIEW.equals(task.getStatus())) {
            log.warn("秒杀订单任务已转人工，跳过自动消费，taskId={}, orderId={}, userId={}, voucherId={}",
                    task.getId(), task.getOrderId(), task.getUserId(), task.getVoucherId());
            return;
        }
        if (VoucherOrderTaskStatus.RESOLVED.equals(task.getStatus())
                || VoucherOrderTaskStatus.RELEASING.equals(task.getStatus())) {
            log.warn("秒杀订单资格已释放或正在释放，跳过消息，taskId={}, orderId={}, status={}",
                    task.getId(), task.getOrderId(), task.getStatus());
            return;
        }

        try {
            voucherOrderService.handleVoucherOrderMessage(message);
        } catch (Exception e) {
            voucherOrderTaskService.markFailed(message.getOrderId(), e.getMessage(), true);
            log.error("消费秒杀订单消息失败，taskId={}, orderId={}, userId={}, voucherId={}, retryCount={}, message={}",
                    task.getId(), message.getOrderId(), message.getUserId(), message.getVoucherId(), task.getRetryCount(), message, e);
            throw e;
        }
    }
}
