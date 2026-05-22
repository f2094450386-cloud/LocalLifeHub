package com.hmdp.aspect;

import cn.hutool.core.util.StrUtil;
import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.Result;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Component
public class RateLimitAspect implements BeanPostProcessor {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
        RATE_LIMIT_SCRIPT.setScriptText(
                "local key = KEYS[1]\n" +
                        "local now = tonumber(ARGV[1])\n" +
                        "local window = tonumber(ARGV[2])\n" +
                        "local max = tonumber(ARGV[3])\n" +
                        "local member = ARGV[4]\n" +
                        "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n" +
                        "local count = redis.call('ZCARD', key)\n" +
                        "if count >= max then\n" +
                        "  return 0\n" +
                        "end\n" +
                        "redis.call('ZADD', key, now, member)\n" +
                        "redis.call('EXPIRE', key, math.ceil(window / 1000) + 1)\n" +
                        "return 1"
        );
    }

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (!hasRateLimitMethod(targetClass)) {
            return bean;
        }
        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> invokeWithRateLimit(invocation, targetClass));
        return proxyFactory.getProxy();
    }

    private Object invokeWithRateLimit(MethodInvocation invocation, Class<?> targetClass) throws Throwable {
        Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
        RateLimit rateLimit = AnnotationUtils.findAnnotation(method, RateLimit.class);
        if (rateLimit == null) {
            return invocation.proceed();
        }

        String bizKey = parseKey(method, invocation.getArguments(), rateLimit);
        String redisKey = RedisConstants.RATE_LIMIT_KEY + bizKey;
        long now = System.currentTimeMillis();
        long windowMillis = rateLimit.windowSeconds() * 1000L;
        String member = now + ":" + UUID.randomUUID();

        Long allowed;
        try {
            allowed = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(redisKey),
                    String.valueOf(now),
                    String.valueOf(windowMillis),
                    String.valueOf(rateLimit.maxRequests()),
                    member
            );
        } catch (Exception e) {
            log.error("rate limit check failed, key={}", redisKey, e);
            return Result.fail(rateLimit.message());
        }

        if (allowed == null || allowed == 0) {
            return Result.fail(rateLimit.message());
        }
        return invocation.proceed();
    }

    private boolean hasRateLimitMethod(Class<?> targetClass) {
        for (Method method : targetClass.getMethods()) {
            if (AnnotationUtils.findAnnotation(method, RateLimit.class) != null) {
                return true;
            }
        }
        return false;
    }

    private String parseKey(Method method, Object[] args, RateLimit rateLimit) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
            if (parameterNames != null && i < parameterNames.length) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        try {
            String value = parser.parseExpression(rateLimit.key()).getValue(context, String.class);
            return StrUtil.isBlank(value) ? fallbackKey(method) : value;
        } catch (Exception e) {
            log.warn("parse rate limit key failed, rawKey={}, method={}", rateLimit.key(), method.getName(), e);
            return StrUtil.isBlank(rateLimit.key()) ? fallbackKey(method) : rateLimit.key();
        }
    }

    private String fallbackKey(Method method) {
        return method.getDeclaringClass().getName() + ":" + method.getName();
    }
}
