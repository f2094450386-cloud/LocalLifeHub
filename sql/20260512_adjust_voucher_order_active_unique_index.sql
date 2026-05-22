-- Allow users to place a new order after a previous unpaid order is CLOSED.
-- MySQL 8 generated column is used because MySQL has no partial unique index.
ALTER TABLE `tb_voucher_order`
    DROP INDEX `uk_voucher_order_user_voucher`;

ALTER TABLE `tb_voucher_order`
    ADD COLUMN `active_voucher_id` bigint(20) UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN `status` <> 4 THEN `voucher_id` ELSE NULL END
    ) STORED COMMENT 'voucher id for active orders only';

ALTER TABLE `tb_voucher_order`
    ADD UNIQUE KEY `uk_voucher_order_user_active_voucher` (`user_id`, `active_voucher_id`);
