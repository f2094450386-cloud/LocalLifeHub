package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * Spring Task fallback for unpaid orders when RocketMQ delayed messages are unavailable or delayed.
 */
@Slf4j
@Component
public class OrderTimeoutScanner {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Value("${seckill.order-timeout.scan-limit:100}")
    private Integer scanLimit;

    @Scheduled(fixedDelayString = "${seckill.order-timeout.scan-interval-ms:60000}")
    public void scanExpiredOrders() {
        log.info("开始扫描超时未支付秒杀订单，limit={}", scanLimit);
        voucherOrderService.closeExpiredUnpaidOrders(scanLimit);
        voucherOrderService.reconcileClosedOrderRedis(scanLimit);
    }
}
