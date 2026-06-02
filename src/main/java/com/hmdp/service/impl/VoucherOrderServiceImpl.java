package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.OrderTimeoutMessage;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderTaskService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.TransactionUtils;
import com.hmdp.utils.RocketMqConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.VoucherOrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private IVoucherOrderTaskService voucherOrderTaskService;
    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderServiceProxy;

    @Value("${seckill.order-timeout.delay-level:11}")
    private Integer orderTimeoutDelayLevel;

    @Value("${seckill.order-timeout.message-timeout-ms:3000}")
    private Long orderTimeoutMessageTimeoutMs;

    @Value("${seckill.order-timeout.minutes:15}")
    private Long orderTimeoutMinutes;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> RELEASE_STOCK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        RELEASE_STOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_STOCK_SCRIPT.setLocation(new ClassPathResource("release_stock.lua"));
        RELEASE_STOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀入口：Redis + Lua 先原子预扣资格，再写本地任务表，最后投递 RocketMQ 异步落库。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        Long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        int code = result == null ? 1 : result.intValue();
        if (code != 0) {
            return Result.fail(code == 1 ? "库存不足" : "禁止重复下单");
        }

        VoucherOrderTask task;
        try {
            task = voucherOrderTaskService.createPendingTask(orderId, userId, voucherId);
        } catch (Exception e) {
            releaseRedisSeckill(orderId, voucherId, userId);
            log.error("秒杀订单任务写入失败，orderId={}, userId={}, voucherId={}", orderId, userId, voucherId, e);
            return Result.fail("订单排队失败，请稍后重试");
        }

        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setTaskId(task.getId());
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setVoucherId(voucherId);

        try {
            SendResult sendResult = rocketMQTemplate.syncSend(RocketMqConstants.VOUCHER_ORDER_TOPIC, message);
            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                voucherOrderTaskService.markFailed(orderId, "MQ send status: " + sendResult.getSendStatus(), false);
                releaseRedisSeckill(orderId, voucherId, userId);
                log.error("秒杀订单消息发送失败，orderId={}, userId={}, voucherId={}, sendStatus={}",
                        orderId, userId, voucherId, sendResult.getSendStatus());
                return Result.fail("订单排队失败，请稍后重试");
            }
            try {
                voucherOrderTaskService.markSent(orderId, sendResult.getMsgId());
            } catch (Exception e) {
                log.error("标记已发送失败，消息已投递，依赖 Compensator 兜底，orderId={}", orderId, e);
            }
        } catch (Exception e) {
            // syncSend 异常不确定 broker 是否收到消息，不释放 Redis；
            // 交给 VoucherOrderTaskCompensator 重投，超过上限且订单不存在时再释放
            voucherOrderTaskService.markFailed(orderId, e.getMessage(), false);
            log.error("秒杀订单消息发送异常，orderId={}, userId={}, voucherId={}", orderId, userId, voucherId, e);
            return Result.fail("订单排队失败，请稍后重试");
        }

        return Result.ok(orderId);
    }

    @Override
    @NotNull
    @Transactional(rollbackFor = Exception.class)
    public Result getResult(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        createVoucherOrder(voucherOrder);
        return Result.ok(orderId);
    }

    /**
     * 创建秒杀订单。已关闭订单不再占用一人一单资格，DB 层由 active_voucher_id 唯一索引兜底。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long orderId = voucherOrder.getId();
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        Long duplicateCount = lambdaQuery()
                .eq(VoucherOrder::getId, orderId)
                .or(wrapper -> wrapper
                        .eq(VoucherOrder::getUserId, userId)
                        .eq(VoucherOrder::getVoucherId, voucherId)
                        .ne(VoucherOrder::getStatus, VoucherOrderStatus.CLOSED))
                .count();
        if (duplicateCount > 0) {
            log.info("秒杀订单已存在，跳过重复消费，orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
            return;
        }

        boolean stockUpdated = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherId)
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock = stock - 1")
        );
        if (!stockUpdated) {
            throw new IllegalStateException("MySQL 秒杀库存不足，orderId=" + orderId + ", voucherId=" + voucherId);
        }

        boolean saved = save(voucherOrder);
        if (!saved) {
            throw new IllegalStateException("秒杀订单保存失败，orderId=" + orderId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleVoucherOrderMessage(VoucherOrderMessage message) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(message.getOrderId());
        voucherOrder.setUserId(message.getUserId());
        voucherOrder.setVoucherId(message.getVoucherId());
        createVoucherOrder(voucherOrder);
        voucherOrderTaskService.markConsumed(message.getOrderId());
        TransactionUtils.afterCommit(() -> sendOrderTimeoutMessageSilently(voucherOrder));
    }

    /**
     * 幂等关闭未支付订单。只有 CREATED -> CLOSED 成功的一次会恢复 MySQL/Redis 库存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeUnpaidOrder(Long orderId) {
        VoucherOrder voucherOrder = getById(orderId);
        if (voucherOrder == null) {
            log.warn("未支付订单关闭跳过，订单不存在，orderId={}", orderId);
            return false;
        }
        if (Integer.valueOf(VoucherOrderStatus.CLOSED).equals(voucherOrder.getStatus())) {
            releaseRedisSeckill(orderId, voucherOrder.getVoucherId(), voucherOrder.getUserId());
            log.info("未支付订单已关闭，补偿执行 Redis 幂等释放，orderId={}, userId={}, voucherId={}",
                    orderId, voucherOrder.getUserId(), voucherOrder.getVoucherId());
            return false;
        }
        if (!Integer.valueOf(VoucherOrderStatus.CREATED).equals(voucherOrder.getStatus())) {
            log.info("未支付订单关闭跳过，订单状态已变化，orderId={}, status={}", orderId, voucherOrder.getStatus());
            return false;
        }

        boolean closed = update(new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, VoucherOrderStatus.CREATED)
                .set(VoucherOrder::getStatus, VoucherOrderStatus.CLOSED));
        if (!closed) {
            return false;
        }

        boolean stockRestored = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                        .setSql("stock = stock + 1")
        );
        if (!stockRestored) {
            throw new IllegalStateException("恢复秒杀券库存失败，orderId=" + orderId + ", voucherId=" + voucherOrder.getVoucherId());
        }

        TransactionUtils.afterCommit(() -> {
            releaseRedisSeckill(orderId, voucherOrder.getVoucherId(), voucherOrder.getUserId());
            log.info("未支付订单已关闭并恢复库存，orderId={}, userId={}, voucherId={}",
                    orderId, voucherOrder.getUserId(), voucherOrder.getVoucherId());
        });
        return true;
    }

    @Override
    public void closeExpiredUnpaidOrders(int limit) {
        int queryLimit = Math.max(1, limit);
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(orderTimeoutMinutes);
        List<VoucherOrder> orders = lambdaQuery()
                .eq(VoucherOrder::getStatus, VoucherOrderStatus.CREATED)
                .le(VoucherOrder::getCreateTime, expireTime)
                .orderByAsc(VoucherOrder::getCreateTime)
                .last("LIMIT " + queryLimit)
                .list();
        for (VoucherOrder order : orders) {
            voucherOrderServiceProxy.closeUnpaidOrder(order.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result payOrder(Long orderId) {
        VoucherOrder voucherOrder = getById(orderId);
        if (voucherOrder == null) {
            return Result.fail("订单不存在");
        }
        Long currentUserId = UserHolder.getUser().getId();
        if (!currentUserId.equals(voucherOrder.getUserId())) {
            return Result.fail("只能支付自己的订单");
        }
        if (Integer.valueOf(VoucherOrderStatus.PAID).equals(voucherOrder.getStatus())) {
            return Result.ok(orderId);
        }
        if (!Integer.valueOf(VoucherOrderStatus.CREATED).equals(voucherOrder.getStatus())) {
            return Result.fail("订单状态不允许支付");
        }

        boolean paid = update(new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getUserId, currentUserId)
                .eq(VoucherOrder::getStatus, VoucherOrderStatus.CREATED)
                .set(VoucherOrder::getStatus, VoucherOrderStatus.PAID)
                .set(VoucherOrder::getPayTime, LocalDateTime.now()));
        if (!paid) {
            return Result.fail("订单支付状态更新失败，请重试");
        }
        log.info("秒杀订单支付成功，orderId={}, userId={}, voucherId={}",
                orderId, currentUserId, voucherOrder.getVoucherId());
        return Result.ok(orderId);
    }

    private void sendOrderTimeoutMessage(VoucherOrder voucherOrder) {
        OrderTimeoutMessage timeoutMessage = new OrderTimeoutMessage();
        timeoutMessage.setOrderId(voucherOrder.getId());
        timeoutMessage.setUserId(voucherOrder.getUserId());
        timeoutMessage.setVoucherId(voucherOrder.getVoucherId());
        SendResult sendResult = rocketMQTemplate.syncSend(
                RocketMqConstants.VOUCHER_ORDER_TIMEOUT_TOPIC,
                MessageBuilder.withPayload(timeoutMessage).build(),
                orderTimeoutMessageTimeoutMs,
                orderTimeoutDelayLevel
        );
        if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("发送订单超时检查延迟消息失败，orderId=" + voucherOrder.getId()
                    + ", sendStatus=" + sendResult.getSendStatus());
        }
        log.info("订单超时检查延迟消息已发送，orderId={}, userId={}, voucherId={}, delayLevel={}, messageId={}",
                voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId(),
                orderTimeoutDelayLevel, sendResult.getMsgId());
    }

    private void sendOrderTimeoutMessageSilently(VoucherOrder voucherOrder) {
        try {
            sendOrderTimeoutMessage(voucherOrder);
        } catch (Exception e) {
            log.error("发送订单超时延迟消息失败，依赖 OrderTimeoutScanner 兜底扫描，orderId={}", voucherOrder.getId(), e);
        }
    }

    /**
     * 原子释放 Redis 预扣库存和一人一单标记，按 orderId 幂等。
     */
    private void releaseRedisSeckill(Long orderId, Long voucherId, Long userId) {
        stringRedisTemplate.execute(
                RELEASE_STOCK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
    }
}
