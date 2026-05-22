-- Voucher order local task table. It records the state between Redis pre-deduct, MQ delivery, and MySQL order creation.
CREATE TABLE IF NOT EXISTS `tb_voucher_order_task` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'task primary key',
  `order_id` bigint(20) NOT NULL COMMENT 'voucher order id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'user id',
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT 'seckill voucher id',
  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING,SENT,CONSUMED,FAILED,MANUAL_REVIEW',
  `retry_count` int(11) NOT NULL DEFAULT 0 COMMENT 'retry count',
  `max_retry` int(11) NOT NULL DEFAULT 5 COMMENT 'max retry count',
  `fail_reason` varchar(512) NULL DEFAULT NULL COMMENT 'latest failure reason',
  `message_id` varchar(128) NULL DEFAULT NULL COMMENT 'RocketMQ message id',
  `last_send_time` timestamp NULL DEFAULT NULL COMMENT 'last MQ send time',
  `next_retry_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'next retry time',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_voucher_order_task_order_id` (`order_id`) USING BTREE,
  KEY `idx_voucher_order_task_status_next_retry_time` (`status`, `next_retry_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'voucher order local task' ROW_FORMAT = Compact;
