package com.ai4kb.backend.user.controller;

import com.ai4kb.backend.engine.entity.RouteSample;
import com.ai4kb.backend.engine.service.RouteSampleService;
import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import com.ai4kb.backend.user.auth.PasswordCodecService;
import com.ai4kb.backend.user.client.RagFlowClient;
import com.ai4kb.backend.user.mapper.PermissionMapper;
import com.ai4kb.backend.user.mapper.UserConversationMapper;
import com.ai4kb.backend.user.mapper.UserConversationMessageMapper;
import com.ai4kb.backend.user.mapper.UserMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

class AdminControllerRouteSampleTest {

    private UserAdminController buildController(RagFlowClient ragFlowClient,
                                                UserMapper userMapper,
                                                PermissionMapper permissionMapper,
                                                RouteSampleService routeSampleService,
                                                PasswordCodecService passwordCodecService) {
        UserConversationMapper userConversationMapper = Mockito.mock(UserConversationMapper.class);
        UserConversationMessageMapper userConversationMessageMapper = Mockito.mock(UserConversationMessageMapper.class);
        return new UserAdminController(
                ragFlowClient,
                routeSampleService,
                userMapper,
                permissionMapper,
                userConversationMapper,
                userConversationMessageMapper,
                passwordCodecService
        );
    }

    @Test
    void listRouteSamples_shouldDelegateToService() {
        RagFlowClient ragFlowClient = Mockito.mock(RagFlowClient.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        RouteSampleService routeSampleService = Mockito.mock(RouteSampleService.class);
        PasswordCodecService passwordCodecService = Mockito.mock(PasswordCodecService.class);
        UserAdminController controller = buildController(ragFlowClient, userMapper, permissionMapper, routeSampleService, passwordCodecService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(1L).username("superadmin").role("super_admin").build());
        RouteSample sample = new RouteSample();
        sample.setConversationId("c-admin-1");
        sample.setSource("PLANNER_ONLY");
        Mockito.when(routeSampleService.listSamples(50, 1L, "PLANNER_ONLY")).thenReturn(List.of(sample));

        List<RouteSample> result = controller.listRouteSamples(50, 1L, "PLANNER_ONLY");

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("c-admin-1", result.get(0).getConversationId());
        Mockito.verify(routeSampleService, Mockito.times(1)).listSamples(50, 1L, "PLANNER_ONLY");
        AuthContextHolder.clear();
    }

    @Test
    void listRouteSampleSources_shouldDelegateToService() {
        RagFlowClient ragFlowClient = Mockito.mock(RagFlowClient.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        RouteSampleService routeSampleService = Mockito.mock(RouteSampleService.class);
        PasswordCodecService passwordCodecService = Mockito.mock(PasswordCodecService.class);
        UserAdminController controller = buildController(ragFlowClient, userMapper, permissionMapper, routeSampleService, passwordCodecService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(1L).username("superadmin").role("super_admin").build());
        Mockito.when(routeSampleService.listSources()).thenReturn(List.of("PLANNER_ONLY", "LOCAL_DIRECT"));

        List<String> result = controller.listRouteSampleSources();

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("PLANNER_ONLY", result.get(0));
        Mockito.verify(routeSampleService, Mockito.times(1)).listSources();
        AuthContextHolder.clear();
    }

    @Test
    void listRouteSamples_shouldRejectAdminRole() {
        RagFlowClient ragFlowClient = Mockito.mock(RagFlowClient.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        RouteSampleService routeSampleService = Mockito.mock(RouteSampleService.class);
        PasswordCodecService passwordCodecService = Mockito.mock(PasswordCodecService.class);
        UserAdminController controller = buildController(ragFlowClient, userMapper, permissionMapper, routeSampleService, passwordCodecService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(2L).username("admin").role("admin").build());

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> controller.listRouteSamples(10, null, null));

        Assertions.assertEquals("仅 super_admin 可执行该操作", ex.getMessage());
        AuthContextHolder.clear();
    }
}
