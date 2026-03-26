package com.ai4kb.backend.user.service;

import com.ai4kb.backend.user.auth.JwtTokenService;
import com.ai4kb.backend.user.auth.PasswordCodecService;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.UserMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

    @Test
    void login_shouldSuccessWhenPasswordHashMatched() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PasswordCodecService passwordCodecService = Mockito.mock(PasswordCodecService.class);
        JwtTokenService jwtTokenService = Mockito.mock(JwtTokenService.class);
        AuthService authService = new AuthService(userMapper, passwordCodecService, jwtTokenService);
        ReflectionTestUtils.setField(authService, "legacyDefaultPassword", "ChangeMe123!");
        User user = new User();
        user.setId(11L);
        user.setUsername("admin");
        user.setRole("admin");
        user.setPasswordHash("$2a$mock");
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(user);
        Mockito.when(passwordCodecService.matches("Secret@123", "$2a$mock")).thenReturn(true);
        Mockito.when(jwtTokenService.generateToken(11L, "admin", "admin")).thenReturn("jwt-token");
        Mockito.when(jwtTokenService.getTtlSeconds()).thenReturn(3600L);

        AuthService.LoginResult result = authService.login("admin", "Secret@123");

        Assertions.assertEquals("jwt-token", result.getToken());
        Assertions.assertEquals(11L, result.getUserId());
        Assertions.assertEquals("admin", result.getRole());
        Mockito.verify(userMapper, Mockito.never()).updateById(Mockito.any());
    }

    @Test
    void login_shouldUpgradeLegacyPasswordWhenHashMissingAndDefaultPasswordMatched() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PasswordCodecService passwordCodecService = Mockito.mock(PasswordCodecService.class);
        JwtTokenService jwtTokenService = Mockito.mock(JwtTokenService.class);
        AuthService authService = new AuthService(userMapper, passwordCodecService, jwtTokenService);
        ReflectionTestUtils.setField(authService, "legacyDefaultPassword", "ChangeMe123!");
        User user = new User();
        user.setId(21L);
        user.setUsername("legacy");
        user.setRole("user");
        user.setPasswordHash(null);
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(user);
        Mockito.when(passwordCodecService.matches(Mockito.anyString(), Mockito.any())).thenReturn(false);
        Mockito.when(passwordCodecService.encode("ChangeMe123!")).thenReturn("$2a$upgraded");
        Mockito.when(jwtTokenService.generateToken(21L, "legacy", "user")).thenReturn("jwt-legacy");
        Mockito.when(jwtTokenService.getTtlSeconds()).thenReturn(3600L);

        AuthService.LoginResult result = authService.login("legacy", "ChangeMe123!");

        Assertions.assertEquals("jwt-legacy", result.getToken());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        Mockito.verify(userMapper, Mockito.times(1)).updateById(captor.capture());
        Assertions.assertEquals("$2a$upgraded", captor.getValue().getPasswordHash());
    }

    @Test
    void login_shouldFailWhenPasswordInvalid() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PasswordCodecService passwordCodecService = Mockito.mock(PasswordCodecService.class);
        JwtTokenService jwtTokenService = Mockito.mock(JwtTokenService.class);
        AuthService authService = new AuthService(userMapper, passwordCodecService, jwtTokenService);
        ReflectionTestUtils.setField(authService, "legacyDefaultPassword", "ChangeMe123!");
        User user = new User();
        user.setId(31L);
        user.setUsername("u1");
        user.setRole("user");
        user.setPasswordHash("$2a$mock");
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(user);
        Mockito.when(passwordCodecService.matches("bad-pass", "$2a$mock")).thenReturn(false);

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> authService.login("u1", "bad-pass"));

        Assertions.assertEquals("账号或密码错误", ex.getMessage());
    }
}
