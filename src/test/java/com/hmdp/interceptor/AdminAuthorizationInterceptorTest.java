package com.hmdp.interceptor;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.UserDTO;
import com.hmdp.security.AdminAccessPolicy;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthorizationInterceptorTest {

    private final AdminAuthorizationInterceptor interceptor =
            new AdminAuthorizationInterceptor(new AdminAccessPolicy("1"));

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldRejectAnonymousAdminRequest() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(),
                response,
                adminHandler()
        );

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldRejectNonAdminUser() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(),
                response,
                adminHandler()
        );

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void shouldAllowConfiguredAdminUser() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                adminHandler()
        );

        assertThat(allowed).isTrue();
    }

    @Test
    void shouldIgnoreHandlerWithoutAdminAnnotation() throws Exception {
        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                publicHandler()
        );

        assertThat(allowed).isTrue();
    }

    private HandlerMethod adminHandler() throws NoSuchMethodException {
        Method method = TestController.class.getDeclaredMethod("admin");
        return new HandlerMethod(new TestController(), method);
    }

    private HandlerMethod publicHandler() throws NoSuchMethodException {
        Method method = TestController.class.getDeclaredMethod("publicEndpoint");
        return new HandlerMethod(new TestController(), method);
    }

    private static class TestController {
        @AdminOnly
        public void admin() {
        }

        public void publicEndpoint() {
        }
    }
}
