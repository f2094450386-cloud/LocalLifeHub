package com.hmdp.dto;

import lombok.Data;

@Data
public class VoucherOrderStatusDTO {
    private Long orderId;
    private String state;
    private Integer orderStatus;
    private String taskStatus;
    private String message;
}
