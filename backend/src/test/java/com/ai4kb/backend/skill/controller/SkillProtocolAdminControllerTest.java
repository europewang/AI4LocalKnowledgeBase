package com.ai4kb.backend.skill.controller;

import com.ai4kb.backend.skill.service.DynamicSkillAuditService;
import com.ai4kb.backend.skill.service.DynamicSkillRegistryService;
import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

class SkillProtocolAdminControllerTest {

    @Test
    void onlineSkill_shouldWorkForAdminRole() {
        DynamicSkillRegistryService dynamicSkillRegistryService = Mockito.mock(DynamicSkillRegistryService.class);
        DynamicSkillAuditService dynamicSkillAuditService = Mockito.mock(DynamicSkillAuditService.class);
        SkillProtocolAdminController controller = new SkillProtocolAdminController(dynamicSkillRegistryService, dynamicSkillAuditService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(9L).username("admin").role("admin").build());

        Map<String, Object> result = controller.onlineSkill("tool_a");

        Assertions.assertEquals("ok", result.get("status"));
        Assertions.assertEquals("ONLINE", result.get("new_status"));
        Mockito.verify(dynamicSkillRegistryService).onlineSkill("tool_a", 9L);
        AuthContextHolder.clear();
    }

    @Test
    void deleteSkill_shouldWorkForSuperAdminRole() {
        DynamicSkillRegistryService dynamicSkillRegistryService = Mockito.mock(DynamicSkillRegistryService.class);
        DynamicSkillAuditService dynamicSkillAuditService = Mockito.mock(DynamicSkillAuditService.class);
        SkillProtocolAdminController controller = new SkillProtocolAdminController(dynamicSkillRegistryService, dynamicSkillAuditService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(1L).username("root").role("super_admin").build());

        Map<String, Object> result = controller.deleteSkill("tool_b");

        Assertions.assertEquals(Boolean.TRUE, result.get("deleted"));
        Mockito.verify(dynamicSkillRegistryService).deleteSkill("tool_b");
        AuthContextHolder.clear();
    }

    @Test
    void deleteSkill_shouldRejectNonAdminRole() {
        DynamicSkillRegistryService dynamicSkillRegistryService = Mockito.mock(DynamicSkillRegistryService.class);
        DynamicSkillAuditService dynamicSkillAuditService = Mockito.mock(DynamicSkillAuditService.class);
        SkillProtocolAdminController controller = new SkillProtocolAdminController(dynamicSkillRegistryService, dynamicSkillAuditService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(2L).username("u").role("user").build());

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, () -> controller.deleteSkill("tool_c"));

        Assertions.assertTrue(ex.getMessage().contains("admin/super_admin"));
        AuthContextHolder.clear();
    }
}
