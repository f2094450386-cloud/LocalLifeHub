package com.hmdp.mq;

import com.hmdp.dto.OrderTimeoutMessage;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RocketMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMqConstants.VOUCHER_ORDER_TIMEOUT_TOPIC,
        consumerGroup = RocketMqConstants.VOUCHER_ORDER_TIMEOUT_CONSUMER_GROUP
)
public class OrderTimeoutConsumer implements RocketMQListener<OrderTimeoutMessage> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(OrderTimeoutMessage message) {
        try {
            boolean closed = voucherOrderService.closeUnpaidOrder(message.getOrderId());
            log.info("订单超时延迟消息处理完成，orderId={}, userId={}, voucherId={}, closed={}",
                    message.getOrderId(), message.getUserId(), message.getVoucherId(), closed);
        } catch (Exception e) {
            log.error("订单超时延迟消息处理失败，message={}", message, e);
            throw e;
        }
    }
}
