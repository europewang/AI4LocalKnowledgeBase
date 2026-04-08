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
import java.util.Map;

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
        RouteSampleService.RouteSamplePageResult pageResult =
                new RouteSampleService.RouteSamplePageResult(List.of(sample), 1L, 1, 20, false);
        Mockito.when(routeSampleService.pageSamples(Mockito.any())).thenReturn(pageResult);

        Map<String, Object> result = controller.listRouteSamples(
                1,
                20,
                1L,
                null,
                "PLANNER_ONLY",
                "SKILL",
                "2026-04-01",
                "2026-04-08",
                "面积"
        );

        Assertions.assertEquals(1L, result.get("total"));
        Assertions.assertTrue(result.containsKey("items"));
        Mockito.verify(routeSampleService, Mockito.times(1)).pageSamples(Mockito.any());
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

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> controller.listRouteSamples(
                1, 20, null, null, null, null, null, null, null));

        Assertions.assertEquals("仅 super_admin 可执行该操作", ex.getMessage());
        AuthContextHolder.clear();
    }
}
