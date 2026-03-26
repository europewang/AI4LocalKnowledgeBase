package com.ai4kb.backend.user.controller;

import com.ai4kb.backend.user.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user/auth")
@RequiredArgsConstructor
/**
 * 用户认证控制器。
 * 提供登录发放 JWT 的入口。
 */
public class AuthController {
    private final AuthService authService;

    /**
     * 登录并发放访问令牌。
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        try {
            AuthService.LoginResult result = authService.login(request.getUsername(), request.getPassword());
            long expiresAt = Instant.now().getEpochSecond() + result.getTtlSeconds();
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", result.getUserId());
            user.put("username", result.getUsername());
            user.put("role", result.getRole());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("token", result.getToken());
            body.put("expires_at", expiresAt);
            body.put("user", user);
            return body;
        } catch (IllegalArgumentException ex) {
            throw new LoginFailedException(ex.getMessage());
        }
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    private static class LoginFailedException extends RuntimeException {
        LoginFailedException(String message) {
            super(message);
        }
    }

    @Data
    /**
     * 登录请求体。
     */
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
