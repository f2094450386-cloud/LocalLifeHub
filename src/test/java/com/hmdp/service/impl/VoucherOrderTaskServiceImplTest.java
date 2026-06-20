package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.mapper.VoucherOrderTaskMapper;
import com.hmdp.utils.VoucherOrderTaskStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderTaskServiceImplTest {

    @Mock
    private VoucherOrderTaskMapper mapper;

    private VoucherOrderTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                VoucherOrderTask.class
        );
        service = new VoucherOrderTaskServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "retryDelaySeconds", 60L);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    @Test
    void compensationShouldClaimTaskWithCompareAndSet() {
        boolean claimed = service.claimForCompensation(10L, VoucherOrderTaskStatus.SENT);

        assertThat(claimed).isTrue();
        verify(mapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void consumerShouldClaimTaskBeforeCreatingOrder() {
        boolean claimed = service.claimForConsumption(100L);

        assertThat(claimed).isTrue();
        verify(mapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void releaseShouldClaimTaskBeforeTouchingRedis() {
        boolean claimed = service.claimForRelease(10L, VoucherOrderTaskStatus.FAILED);

        assertThat(claimed).isTrue();
        verify(mapper).update(isNull(), any(Wrapper.class));
    }
}
