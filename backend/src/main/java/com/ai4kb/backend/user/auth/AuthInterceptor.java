package com.ai4kb.backend.user.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (isPublicPath(path)) {
            return true;
        }
        String authorization = request.getHeader(AUTH_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少或非法 Authorization 头");
            return false;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "token 为空");
            return false;
        }
        AuthenticatedUser user;
        try {
            user = jwtTokenService.parseToken(token);
        } catch (IllegalArgumentException ex) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
            return false;
        }
        if (path.startsWith("/api/admin") && !isAdminRole(user.getRole())) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "当前账号无管理端访问权限");
            return false;
        }
        AuthContextHolder.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContextHolder.clear();
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/api/user/auth/login") || path.startsWith("/error");
    }

    private boolean isAdminRole(String role) {
        return "admin".equalsIgnoreCase(role) || "super_admin".equalsIgnoreCase(role);
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", status, "message", message)));
    }
}
