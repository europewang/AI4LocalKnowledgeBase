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

import java.util.concurrent.atomic.AtomicReference;

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
        AtomicReference<User> insertedUserRef = new AtomicReference<>();
        Mockito.when(userMapper.insert(Mockito.any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            insertedUserRef.set(user);
            user.setId(88L);
            return 1;
        });

        var result = controller.createNormal(request);

        Assertions.assertEquals("normal-user", result.get("username"));
        Assertions.assertEquals("user", result.get("role"));
        Assertions.assertEquals(3L, result.get("manager_user_id"));
        Assertions.assertEquals(3L, insertedUserRef.get().getManagerUserId());
        AuthContextHolder.clear();
    }

    @Test
    void createAdmin_shouldSuccessForSuperAdmin() {
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
        AuthContextHolder.set(AuthenticatedUser.builder().userId(1L).username("super").role("super_admin").build());
        UserAdminController.CreateUserRequest request = new UserAdminController.CreateUserRequest();
        request.setUsername("new-admin");
        request.setPassword("Secret@123");
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(null);
        Mockito.when(passwordCodecService.encode("Secret@123")).thenReturn("$2a$encoded");
        Mockito.when(userMapper.insert(Mockito.any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        });

        var result = controller.createAdmin(request);

        Assertions.assertEquals(99L, result.get("id"));
        Assertions.assertEquals("new-admin", result.get("username"));
        Assertions.assertEquals("admin", result.get("role"));
        AuthContextHolder.clear();
    }

    @Test
    void createNormal_shouldUseSpecifiedManagerForSuperAdmin() {
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
        AuthContextHolder.set(AuthenticatedUser.builder().userId(1L).username("super").role("super_admin").build());
        UserAdminController.CreateUserRequest request = new UserAdminController.CreateUserRequest();
        request.setUsername("normal-under-admin");
        request.setPassword("Secret@123");
        request.setManagerUserId(10L);
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(null);
        Mockito.when(passwordCodecService.encode("Secret@123")).thenReturn("$2a$encoded");
        User manager = new User();
        manager.setId(10L);
        manager.setRole("admin");
        Mockito.when(userMapper.selectById(10L)).thenReturn(manager);
        AtomicReference<User> insertedUserRef = new AtomicReference<>();
        Mockito.when(userMapper.insert(Mockito.any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            insertedUserRef.set(user);
            user.setId(100L);
            return 1;
        });

        var result = controller.createNormal(request);

        Assertions.assertEquals(100L, result.get("id"));
        Assertions.assertEquals("normal-under-admin", result.get("username"));
        Assertions.assertEquals("user", result.get("role"));
        Assertions.assertEquals(10L, result.get("manager_user_id"));
        Assertions.assertEquals(10L, insertedUserRef.get().getManagerUserId());
        AuthContextHolder.clear();
    }

    @Test
    void createNormal_shouldFailWhenSuperAdminNotProvideManagerId() {
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
        AuthContextHolder.set(AuthenticatedUser.builder().userId(1L).username("super").role("super_admin").build());
        UserAdminController.CreateUserRequest request = new UserAdminController.CreateUserRequest();
        request.setUsername("normal-user");
        request.setPassword("Secret@123");

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> controller.createNormal(request));

        Assertions.assertEquals("super_admin 创建普通用户必须传入 manager_user_id", ex.getMessage());
        AuthContextHolder.clear();
    }

    @Test
    void promoteToAdmin_shouldSuccessForSuperAdmin() {
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
        AuthContextHolder.set(AuthenticatedUser.builder().userId(1L).username("super").role("super_admin").build());
        User target = new User();
        target.setId(66L);
        target.setUsername("normal-a");
        target.setRole("user");
        target.setManagerUserId(3L);
        Mockito.when(userMapper.selectById(66L)).thenReturn(target);

        var result = controller.promoteToAdmin(66L);

        Assertions.assertEquals("ok", result.get("status"));
        Assertions.assertEquals("admin", result.get("role"));
        Assertions.assertNull(target.getManagerUserId());
        Mockito.verify(userMapper, Mockito.times(1)).updateById(target);
        AuthContextHolder.clear();
    }

    @Test
    void updateUser_shouldSuccessForAdminManagedUser() {
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
        User target = new User();
        target.setId(88L);
        target.setUsername("normal-user");
        target.setRole("user");
        target.setManagerUserId(3L);
        Mockito.when(userMapper.selectById(88L)).thenReturn(target);
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(null);
        Mockito.when(passwordCodecService.encode("NewPass@123")).thenReturn("$2a$new");
        UserAdminController.UpdateUserRequest request = new UserAdminController.UpdateUserRequest();
        request.setUsername("normal-user-renamed");
        request.setPassword("NewPass@123");

        var result = controller.updateUser(88L, request);

        Assertions.assertEquals("ok", result.get("status"));
        Assertions.assertEquals("normal-user-renamed", result.get("username"));
        Assertions.assertEquals("$2a$new", target.getPasswordHash());
        Mockito.verify(userMapper, Mockito.times(1)).updateById(target);
        AuthContextHolder.clear();
    }

    @Test
    void updateUser_shouldFailForAdminUnmanagedUser() {
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
        User target = new User();
        target.setId(99L);
        target.setUsername("other-user");
        target.setRole("user");
        target.setManagerUserId(5L);
        Mockito.when(userMapper.selectById(99L)).thenReturn(target);
        UserAdminController.UpdateUserRequest request = new UserAdminController.UpdateUserRequest();
        request.setUsername("other-user-new");

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> controller.updateUser(99L, request));

        Assertions.assertEquals("仅可分配自己及直属用户权限", ex.getMessage());
        Mockito.verify(userMapper, Mockito.never()).updateById(Mockito.any(User.class));
        AuthContextHolder.clear();
    }
}
