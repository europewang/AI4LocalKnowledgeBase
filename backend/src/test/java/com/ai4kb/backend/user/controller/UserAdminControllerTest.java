package com.ai4kb.backend.user.controller;

import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import com.ai4kb.backend.user.auth.PasswordCodecService;
import com.ai4kb.backend.user.client.RagFlowClient;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.PermissionMapper;
import com.ai4kb.backend.user.mapper.UserConversationMapper;
import com.ai4kb.backend.user.mapper.UserConversationMessageMapper;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.ai4kb.backend.engine.service.RouteSampleService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserAdminControllerTest {

    @Test
    void createAdmin_shouldFailForNonSuperAdmin() {
        RagFlowClient ragFlowClient = Mockito.mock(RagFlowClient.class);
        RouteSampleService routeSampleService = Mockito.mock(RouteSampleService.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        UserConversationMapper userConversationMapper = Mockito.mock(UserConversationMapper.class);
        UserConversationMessageMapper userConversationMessageMapper = Mockito.mock(UserConversationMessageMapper.class);
        PasswordCodecService passwordCodecService = Mockito.mock(PasswordCodecService.class);
        UserAdminController controller = new UserAdminController(
                ragFlowClient,
                routeSampleService,
                userMapper,
                permissionMapper,
                userConversationMapper,
                userConversationMessageMapper,
                passwordCodecService
        );
        AuthContextHolder.set(AuthenticatedUser.builder().userId(2L).username("admin").role("admin").build());
        UserAdminController.CreateUserRequest request = new UserAdminController.CreateUserRequest();
        request.setUsername("new-admin");
        request.setPassword("Secret@123");

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> controller.createAdmin(request));

        Assertions.assertEquals("仅 super_admin 可创建管理员", ex.getMessage());
        AuthContextHolder.clear();
    }

    @Test
    void createNormal_shouldSuccessForAdmin() {
        RagFlowClient ragFlowClient = Mockito.mock(RagFlowClient.class);
        RouteSampleService routeSampleService = Mockito.mock(RouteSampleService.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        UserConversationMapper userConversationMapper = Mockito.mock(UserConversationMapper.class);
        UserConversationMessageMapper userConversationMessageMapper = Mockito.mock(UserConversationMessageMapper.class);
        PasswordCodecService passwordCodecService = Mockito.mock(PasswordCodecService.class);
        UserAdminController controller = new UserAdminController(
                ragFlowClient,
                routeSampleService,
                userMapper,
                permissionMapper,
                userConversationMapper,
                userConversationMessageMapper,
                passwordCodecService
        );
        AuthContextHolder.set(AuthenticatedUser.builder().userId(3L).username("admin").role("admin").build());
        UserAdminController.CreateUserRequest request = new UserAdminController.CreateUserRequest();
        request.setUsername("normal-user");
        request.setPassword("Secret@123");
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(null);
        Mockito.when(passwordCodecService.encode("Secret@123")).thenReturn("$2a$encoded");
        Mockito.when(userMapper.insert(Mockito.any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(88L);
            return 1;
        });

        var result = controller.createNormal(request);

        Assertions.assertEquals("normal-user", result.get("username"));
        Assertions.assertEquals("user", result.get("role"));
        AuthContextHolder.clear();
    }
}
