package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.entity.DynamicSkillCallAudit;
import com.ai4kb.backend.skill.entity.DynamicSkillRegistry;
import com.ai4kb.backend.skill.model.ToolExecutionResult;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class SkillExecutionServiceHttpAuditTest {

    @Test
    void execute_shouldCallHttpProtocolAndWriteAudit() throws Exception {
        DynamicSkillRegistryService dynamicSkillRegistryService = Mockito.mock(DynamicSkillRegistryService.class);
        DynamicSkillProtocolClient dynamicSkillProtocolClient = Mockito.mock(DynamicSkillProtocolClient.class);
        DynamicSkillAuditService dynamicSkillAuditService = Mockito.mock(DynamicSkillAuditService.class);
        ToolFileStorageService toolFileStorageService = Mockito.mock(ToolFileStorageService.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        SkillExecutionService service = new SkillExecutionService(
                dynamicSkillRegistryService,
                dynamicSkillProtocolClient,
                dynamicSkillAuditService,
                toolFileStorageService,
                userMapper,
                new ObjectMapper()
        );
        DynamicSkillRegistry registry = new DynamicSkillRegistry();
        registry.setToolCode("cad_text_extractor_indicator_verification");
        registry.setToolName("cad_text_extractor 指标校核");
        registry.setInvokeUrl("http://127.0.0.1:8083/api/v1/skill/protocol/cad_text_extractor/invoke");
        registry.setStatus("ONLINE");
        Mockito.when(dynamicSkillRegistryService.findOnlineByToolCode("cad_text_extractor_indicator_verification"))
                .thenReturn(Optional.of(registry));
        Mockito.when(toolFileStorageService.listInputFiles("tc-1")).thenReturn(List.of());
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        DynamicSkillCallAudit audit = new DynamicSkillCallAudit();
        audit.setId(100L);
        Mockito.when(dynamicSkillAuditService.startAudit(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString()))
                .thenReturn(audit);
        Mockito.when(dynamicSkillProtocolClient.invoke(Mockito.eq(registry), Mockito.any()))
                .thenReturn(ToolExecutionResult.builder()
                        .success(true)
                        .summary("执行成功")
                        .structuredData(Map.of("logs", "ok"))
                        .generatedFiles(List.of())
                        .build());
        Map<String, Object> payload = service.execute("c-1", "tc-1", "cad_text_extractor_indicator_verification", 1L, "{\"checker\":\"张三\"}");
        Assertions.assertEquals(true, payload.get("success"));
        Mockito.verify(dynamicSkillProtocolClient).invoke(Mockito.eq(registry), Mockito.any());
        Mockito.verify(dynamicSkillAuditService).finishSuccess(Mockito.eq(100L), Mockito.anyString(), Mockito.anyLong());
    }
}
