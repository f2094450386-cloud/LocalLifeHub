package com.hmdp.interceptor;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.UserDTO;
import com.hmdp.security.AdminAccessPolicy;
import com.hmdp.utils.UserHolder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class AdminAuthorizationInterceptor implements HandlerInterceptor {

    private final AdminAccessPolicy adminAccessPolicy;

    public AdminAuthorizationInterceptor(AdminAccessPolicy adminAccessPolicy) {
        this.adminAccessPolicy = adminAccessPolicy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        boolean adminOnly = AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), AdminOnly.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), AdminOnly.class);
        if (!adminOnly) {
            return true;
        }

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!adminAccessPolicy.isAdmin(user.getId())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
