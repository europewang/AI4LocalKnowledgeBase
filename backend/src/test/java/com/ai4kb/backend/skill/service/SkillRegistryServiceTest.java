package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.entity.DynamicSkillRegistry;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.ai4kb.backend.user.mapper.PermissionMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

class SkillRegistryServiceTest {

    @Test
    void matchTool_shouldDetectCadSkill_whenQueryContainsIndicatorKeyword() {
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        DynamicSkillRegistryService dynamicSkillRegistryService = Mockito.mock(DynamicSkillRegistryService.class);
        DynamicSkillRegistry onlineSkill = new DynamicSkillRegistry();
        onlineSkill.setToolCode("cad_text_extractor_indicator_verification");
        onlineSkill.setDescription("指标校核：上传 CAD 图纸后输出校核结果文件");
        onlineSkill.setTriggerKeywords("指标校核,指标核验,指标检查");
        onlineSkill.setInputMode("FILE_AND_PARAMS");
        onlineSkill.setOutputMode("MIXED");
        onlineSkill.setUploadRequired(1);
        onlineSkill.setAcceptedFileTypes(".dxf");
        onlineSkill.setMaxFiles(200);
        onlineSkill.setStatus("ONLINE");
        Mockito.when(dynamicSkillRegistryService.listOnlineSkills()).thenReturn(List.of(onlineSkill));
        Mockito.when(dynamicSkillRegistryService.toToolSpec(Mockito.any())).thenCallRealMethod();
        SkillRegistryService service = new SkillRegistryService(permissionMapper, dynamicSkillRegistryService);

        Optional<ToolSpec> matched = service.matchTool("帮我进行指标校核", 1L);
        Assertions.assertTrue(matched.isPresent());
        Assertions.assertEquals("cad_text_extractor_indicator_verification", matched.get().getName());
        Assertions.assertTrue(matched.get().isUploadRequired());
        Assertions.assertEquals("FILE_AND_PARAMS", matched.get().getInputMode());
    }

    @Test
    void getAvailableTools_shouldNotContainOfflineSkill() {
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        DynamicSkillRegistryService dynamicSkillRegistryService = Mockito.mock(DynamicSkillRegistryService.class);
        Mockito.when(dynamicSkillRegistryService.listOnlineSkills()).thenReturn(List.of());
        SkillRegistryService service = new SkillRegistryService(permissionMapper, dynamicSkillRegistryService);
        List<ToolSpec> tools = service.getAvailableTools(1L);
        Assertions.assertTrue(tools.isEmpty());
    }

    @Test
    void findAvailableToolByCode_shouldMatchExactCode() {
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        DynamicSkillRegistryService dynamicSkillRegistryService = Mockito.mock(DynamicSkillRegistryService.class);
        DynamicSkillRegistry onlineSkill = new DynamicSkillRegistry();
        onlineSkill.setToolCode("cad_text_extractor_indicator_verification");
        onlineSkill.setToolName("指标校核");
        onlineSkill.setDescription("描述");
        onlineSkill.setInputMode("FILE_AND_PARAMS");
        onlineSkill.setOutputMode("MIXED");
        onlineSkill.setUploadRequired(1);
        onlineSkill.setAcceptedFileTypes(".dxf");
        onlineSkill.setMaxFiles(10);
        onlineSkill.setStatus("ONLINE");
        Mockito.when(dynamicSkillRegistryService.listOnlineSkills()).thenReturn(List.of(onlineSkill));
        Mockito.when(dynamicSkillRegistryService.toToolSpec(Mockito.any())).thenCallRealMethod();
        SkillRegistryService service = new SkillRegistryService(permissionMapper, dynamicSkillRegistryService);

        Optional<ToolSpec> found = service.findAvailableToolByCode(1L, "cad_text_extractor_indicator_verification");

        Assertions.assertTrue(found.isPresent());
        Assertions.assertEquals("cad_text_extractor_indicator_verification", found.get().getName());
    }
}
