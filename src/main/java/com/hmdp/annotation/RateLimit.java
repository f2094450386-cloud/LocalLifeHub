package com.hmdp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    String key();

    int windowSeconds() default 1;

    int maxRequests() default 10;

    String message() default "请求过于频繁，请稍后再试";
}
