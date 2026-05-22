package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀订单本地任务表，用于记录 Redis 预扣后到 MySQL 落库前的消息状态。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher_order_task")
public class VoucherOrderTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long userId;

    private Long voucherId;

    private String status;

    private Integer retryCount;

    private Integer maxRetry;

    private String failReason;

    private String messageId;

    private LocalDateTime lastSendTime;

    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
