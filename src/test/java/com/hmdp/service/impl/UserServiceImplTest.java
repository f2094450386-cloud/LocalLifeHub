package com.hmdp.service.impl;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void loginShouldConsumeVerificationCodeAtomically() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13686869696");
        form.setCode("123456");
        when(stringRedisTemplate.execute(
                isA(RedisScript.class),
                eq(java.util.Collections.singletonList(LOGIN_CODE_KEY + form.getPhone())),
                eq(form.getCode())
        )).thenReturn(0L);

        Result result = userService.login(form, null);

        assertThat(result.getSuccess()).isFalse();
        verify(stringRedisTemplate).execute(
                isA(RedisScript.class),
                eq(java.util.Collections.singletonList(LOGIN_CODE_KEY + form.getPhone())),
                eq(form.getCode())
        );
    }
}
