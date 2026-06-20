package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogServiceImplTest {

    @Mock
    private BlogMapper blogMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "blog-test"),
                Blog.class
        );
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);

        UserDTO user = new UserDTO();
        user.setId(20L);
        UserHolder.saveUser(user);

        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score("blog:liked:10", "20")).thenReturn(1D);
        when(blogMapper.update(isNull(), org.mockito.ArgumentMatchers.any(Wrapper.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void unlikeShouldOnlyDecrementPositiveLikeCount() {
        service.likeBlog(10L);

        ArgumentCaptor<Wrapper<Blog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(blogMapper).update(isNull(), captor.capture());

        UpdateWrapper<Blog> wrapper = (UpdateWrapper<Blog>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("liked").contains(">");
    }
}
