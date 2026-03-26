package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.executor.impl.CadIndicatorVerificationSkillExecutor;
import com.ai4kb.backend.skill.executor.impl.SendEmailMockSkillExecutor;
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
        SkillRegistryService service = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );

        Optional<ToolSpec> matched = service.matchTool("帮我进行指标校核", 1L);
        Assertions.assertTrue(matched.isPresent());
        Assertions.assertEquals("cad_text_extractor_indicator_verification", matched.get().getName());
        Assertions.assertTrue(matched.get().isUploadRequired());
        Assertions.assertEquals("FILE_AND_PARAMS", matched.get().getInputMode());
    }
}
