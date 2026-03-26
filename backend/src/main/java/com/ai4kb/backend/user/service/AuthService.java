package com.ai4kb.backend.user.service;

import com.ai4kb.backend.user.auth.JwtTokenService;
import com.ai4kb.backend.user.auth.PasswordCodecService;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
/**
 * 认证服务。
 * 负责登录校验、兼容旧数据密码升级与 token 发放。
 */
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordCodecService passwordCodecService;
    private final JwtTokenService jwtTokenService;
    @Value("${ai4kb.auth.legacy-default-password:ChangeMe123!}")
    private String legacyDefaultPassword;

    /**
     * 执行账号密码登录并返回令牌结果。
     */
    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名或密码不能为空");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        boolean matched = passwordCodecService.matches(password, user.getPasswordHash());
        if (!matched && isLegacyPasswordAccepted(user, password)) {
            user.setPasswordHash(passwordCodecService.encode(password));
            userMapper.updateById(user);
            matched = true;
        }
        if (!matched) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        String token = jwtTokenService.generateToken(user.getId(), user.getUsername(), user.getRole());
        return LoginResult.builder()
                .token(token)
                .ttlSeconds(jwtTokenService.getTtlSeconds())
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();
    }

    /**
     * 兼容旧库无密码哈希数据。
     * 仅当账号未配置 password_hash 时允许使用迁移默认密码登录，并自动升级为哈希存储。
     */
    private boolean isLegacyPasswordAccepted(User user, String inputPassword) {
        if (user.getPasswordHash() != null && !user.getPasswordHash().isBlank()) {
            return false;
        }
        return legacyDefaultPassword.equals(inputPassword);
    }

    @Data
    @Builder
    public static class LoginResult {
        private String token;
        private long ttlSeconds;
        private Long userId;
        private String username;
        private String role;
    }
}
