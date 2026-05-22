package com.hmdp.utils;

public class RocketMqConstants {
    /**
     * 秒杀订单异步创建 Topic。
     */
    public static final String VOUCHER_ORDER_TOPIC = "local-lifehub.voucher-order";

    /**
     * 秒杀订单消费者组。
     */
    public static final String VOUCHER_ORDER_CONSUMER_GROUP = "local-lifehub-voucher-order-consumer-group";

    /**
     * Unpaid order timeout check Topic.
     */
    public static final String VOUCHER_ORDER_TIMEOUT_TOPIC = "local-lifehub.voucher-order-timeout";

    /**
     * Unpaid order timeout check consumer group.
     */
    public static final String VOUCHER_ORDER_TIMEOUT_CONSUMER_GROUP = "local-lifehub-voucher-order-timeout-consumer-group";

    private RocketMqConstants() {
    }
}
