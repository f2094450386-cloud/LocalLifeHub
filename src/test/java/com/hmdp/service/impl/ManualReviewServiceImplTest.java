package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherOrderTaskService;
import com.hmdp.utils.VoucherOrderTaskStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualReviewServiceImplTest {

    @Mock
    private IVoucherOrderTaskService voucherOrderTaskService;
    @Mock
    private IVoucherOrderService voucherOrderService;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private SendResult sendResult;

    private ManualReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ManualReviewServiceImpl();
        ReflectionTestUtils.setField(service, "voucherOrderTaskService", voucherOrderTaskService);
        ReflectionTestUtils.setField(service, "voucherOrderService", voucherOrderService);
        ReflectionTestUtils.setField(service, "rocketMQTemplate", rocketMQTemplate);
    }

    @Test
    void retryShouldKeepProcessingWhenMqResultIsUncertain() {
        VoucherOrderTask task = manualReviewTask();
        when(voucherOrderTaskService.getById(10L)).thenReturn(task);
        when(voucherOrderTaskService.claimManualRetry(10L)).thenReturn(true);
        when(rocketMQTemplate.syncSend(anyString(), any(VoucherOrderMessage.class)))
                .thenThrow(new RuntimeException("send timeout"));

        Result result = service.retryVoucherOrderTask(10L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(10L);
        verify(voucherOrderTaskService, never()).markManualReview(any(), anyString());
    }

    @Test
    void retryShouldKeepProcessingWhenMqReturnsNonOkStatus() {
        VoucherOrderTask task = manualReviewTask();
        when(voucherOrderTaskService.getById(10L)).thenReturn(task);
        when(voucherOrderTaskService.claimManualRetry(10L)).thenReturn(true);
        when(rocketMQTemplate.syncSend(anyString(), any(VoucherOrderMessage.class))).thenReturn(sendResult);
        when(sendResult.getSendStatus()).thenReturn(SendStatus.FLUSH_DISK_TIMEOUT);

        Result result = service.retryVoucherOrderTask(10L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(10L);
        verify(voucherOrderTaskService, never()).markManualReview(any(), anyString());
    }

    private VoucherOrderTask manualReviewTask() {
        VoucherOrderTask task = new VoucherOrderTask();
        task.setId(10L);
        task.setOrderId(100L);
        task.setUserId(200L);
        task.setVoucherId(300L);
        task.setStatus(VoucherOrderTaskStatus.MANUAL_REVIEW);
        return task;
    }
}
