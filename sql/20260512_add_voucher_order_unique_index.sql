-- 秒杀订单幂等约束：同一用户对同一优惠券只能有一笔订单。
-- 如果本地库已有重复脏数据，请先清理重复记录后再执行该脚本。
ALTER TABLE `tb_voucher_order`
    ADD UNIQUE KEY `uk_voucher_order_user_voucher` (`user_id`, `voucher_id`);
