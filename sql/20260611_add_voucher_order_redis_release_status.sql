ALTER TABLE `tb_voucher_order`
    ADD COLUMN `redis_released` tinyint(1) NOT NULL DEFAULT 0
        COMMENT 'whether Redis seckill reservation was released after close'
        AFTER `refund_time`,
    ADD KEY `idx_voucher_order_closed_redis_release`
        (`status`, `redis_released`, `update_time`);
