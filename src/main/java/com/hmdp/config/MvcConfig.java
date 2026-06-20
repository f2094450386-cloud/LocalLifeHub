package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import com.hmdp.interceptor.AdminAuthorizationInterceptor;
import com.hmdp.security.AdminAccessPolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * mvc配置
 *
 * @author CHEN
 * @date 2022/10/07
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private AdminAccessPolicy adminAccessPolicy;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登陆拦截器
        registry
                .addInterceptor(new LoginInterceptor())
                .excludePathPatterns("/user/code"
                        , "/user/login"
                        , "/blog/hot"
                        , "/shop/**"
                        , "/shop-type/**"
                        , "/voucher/**"
                )
                .order(2);
        registry
                .addInterceptor(new AdminAuthorizationInterceptor(adminAccessPolicy))
                .addPathPatterns("/**")
                .order(1);
        //Token续命拦截器
        registry
                .addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
    }
}
