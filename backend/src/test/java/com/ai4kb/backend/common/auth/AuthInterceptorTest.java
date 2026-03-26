package com.ai4kb.backend.user.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthInterceptorTest {

    @Test
    void preHandle_shouldAllowLoginPathWithoutToken() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JwtTokenService jwtTokenService = new JwtTokenService(objectMapper, "test-secret", 3600);
        AuthInterceptor interceptor = new AuthInterceptor(jwtTokenService, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        Assertions.assertTrue(allowed);
    }

    @Test
    void preHandle_shouldRejectWhenMissingToken() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JwtTokenService jwtTokenService = new JwtTokenService(objectMapper, "test-secret", 3600);
        AuthInterceptor interceptor = new AuthInterceptor(jwtTokenService, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agent/tool/catalog");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        Assertions.assertFalse(allowed);
        Assertions.assertEquals(401, response.getStatus());
    }

    @Test
    void preHandle_shouldRejectAdminPathForNonAdminRole() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JwtTokenService jwtTokenService = new JwtTokenService(objectMapper, "test-secret", 3600);
        AuthInterceptor interceptor = new AuthInterceptor(jwtTokenService, objectMapper);
        String token = jwtTokenService.generateToken(2L, "zhangsan", "user");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        Assertions.assertFalse(allowed);
        Assertions.assertEquals(403, response.getStatus());
    }

    @Test
    void preHandle_shouldPassAndBindContext() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JwtTokenService jwtTokenService = new JwtTokenService(objectMapper, "test-secret", 3600);
        AuthInterceptor interceptor = new AuthInterceptor(jwtTokenService, objectMapper);
        String token = jwtTokenService.generateToken(9L, "admin1", "admin");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agent/tool/catalog");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        Assertions.assertTrue(allowed);
        Assertions.assertNotNull(AuthContextHolder.get());
        Assertions.assertEquals(9L, AuthContextHolder.get().getUserId());
        interceptor.afterCompletion(request, response, new Object(), null);
        Assertions.assertNull(AuthContextHolder.get());
    }
}
