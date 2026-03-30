package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.model.ConversationState;
import com.ai4kb.backend.engine.model.ToolCallDraft;
import com.ai4kb.backend.rag.processor.ChatProcessor;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.ai4kb.backend.skill.service.SkillExecutionService;
import com.ai4kb.backend.skill.service.SkillRegistryService;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;

class EngineOrchestratorManualDraftTest {

    @Test
    void createManualToolDraft_shouldCreateAndPersistDraft() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillRegistryService skillRegistryService = Mockito.mock(SkillRegistryService.class);
        SkillExecutionService skillExecutionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                new ObjectMapper(),
                skillRegistryService,
                skillExecutionService,
                chatProcessor,
                userMapper
        );
        ToolSpec spec = ToolSpec.builder()
                .name("cad_text_extractor_indicator_verification")
                .description("desc")
                .build();
        Mockito.when(skillRegistryService.findAvailableToolByCode(7L, "cad_text_extractor_indicator_verification"))
                .thenReturn(Optional.of(spec));
        Mockito.when(skillRegistryService.buildDraftPayload(Mockito.eq(spec), Mockito.anyString()))
                .thenReturn(Map.of(
                        "tool_name", spec.getName(),
                        "draft_args", Map.of("query", "指标校核"),
                        "parameters_schema", Map.of("type", "object")
                ));
        Mockito.when(conversationService.getState("conv-manual")).thenReturn(null);

        ToolCallDraft draft = orchestrator.createManualToolDraft(
                "conv-manual",
                7L,
                "cad_text_extractor_indicator_verification",
                "指标校核"
        );

        Assertions.assertNotNull(draft);
        Assertions.assertEquals("cad_text_extractor_indicator_verification", draft.getToolName());
        Assertions.assertEquals("PENDING", draft.getApprovalStatus());
        Assertions.assertTrue(draft.getToolCallId().startsWith("tc-"));
        ArgumentCaptor<ConversationState> stateCaptor = ArgumentCaptor.forClass(ConversationState.class);
        Mockito.verify(conversationService).saveState(stateCaptor.capture());
        ConversationState savedState = stateCaptor.getValue();
        Assertions.assertEquals("WAITING_APPROVAL", savedState.getStatus());
        Assertions.assertEquals(draft.getToolCallId(), savedState.getPendingToolCallId());
        Assertions.assertFalse(savedState.getMemoryWindow().isEmpty());
        Mockito.verify(conversationService).saveDraft(Mockito.any(ToolCallDraft.class));
        Mockito.verify(skillRegistryService).buildDraftPayload(spec, "指标校核");
    }

    @Test
    void createManualToolDraft_shouldRejectUnknownTool() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillRegistryService skillRegistryService = Mockito.mock(SkillRegistryService.class);
        SkillExecutionService skillExecutionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                new ObjectMapper(),
                skillRegistryService,
                skillExecutionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(skillRegistryService.findAvailableToolByCode(7L, "not_exists"))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.createManualToolDraft("conv-manual", 7L, "not_exists", "")
        );

        Assertions.assertTrue(ex.getMessage().contains("无权限"));
        Mockito.verify(conversationService, Mockito.never()).saveState(Mockito.any());
    }
}
