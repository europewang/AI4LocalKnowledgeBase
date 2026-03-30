package com.ai4kb.backend.engine.controller;

import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import com.ai4kb.backend.engine.model.ToolCallDraft;
import com.ai4kb.backend.engine.service.EngineOrchestrator;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.ai4kb.backend.skill.service.SkillRegistryService;
import com.ai4kb.backend.skill.service.ToolFileStorageService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

class AgentControllerAuthContextTest {

    @Test
    void getToolCatalog_shouldUseAuthenticatedUserId() {
        EngineOrchestrator engineOrchestrator = Mockito.mock(EngineOrchestrator.class);
        ToolFileStorageService toolFileStorageService = Mockito.mock(ToolFileStorageService.class);
        SkillRegistryService skillRegistryService = Mockito.mock(SkillRegistryService.class);
        AgentController controller = new AgentController(engineOrchestrator, toolFileStorageService, skillRegistryService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(99L).username("u99").role("user").build());
        Mockito.when(skillRegistryService.getAvailableTools(99L)).thenReturn(List.of(ToolSpec.builder().name("t1").build()));

        List<ToolSpec> result = controller.getToolCatalog();

        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("t1", result.get(0).getName());
        AuthContextHolder.clear();
    }

    @Test
    void chatStream_shouldUseAuthenticatedUserId() {
        EngineOrchestrator engineOrchestrator = Mockito.mock(EngineOrchestrator.class);
        ToolFileStorageService toolFileStorageService = Mockito.mock(ToolFileStorageService.class);
        SkillRegistryService skillRegistryService = Mockito.mock(SkillRegistryService.class);
        AgentController controller = new AgentController(engineOrchestrator, toolFileStorageService, skillRegistryService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(77L).username("u77").role("user").build());
        Mockito.when(engineOrchestrator.processAdvanced(
                Mockito.eq("conv-1"),
                Mockito.eq(77L),
                Mockito.eq("q"),
                Mockito.isNull(),
                Mockito.eq(List.of(Map.of("stepNo", 1))),
                Mockito.eq("REPLAN"),
                Mockito.eq(1),
                Mockito.eq(true)
        )).thenReturn(Flux.just(ServerSentEvent.builder("ok").build()));
        AgentController.ChatRequest request = new AgentController.ChatRequest();
        request.setConversationId("conv-1");
        request.setQuery("q");
        request.setEditedSteps(List.of(Map.of("stepNo", 1)));
        request.setRerunMode("REPLAN");
        request.setRestartFromStep(1);
        request.setReplanOnly(true);

        List<ServerSentEvent<String>> events = controller.chatStream(request).collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals("ok", events.get(0).data());
        AuthContextHolder.clear();
    }

    @Test
    void createToolDraft_shouldUseAuthenticatedUserId() {
        EngineOrchestrator engineOrchestrator = Mockito.mock(EngineOrchestrator.class);
        ToolFileStorageService toolFileStorageService = Mockito.mock(ToolFileStorageService.class);
        SkillRegistryService skillRegistryService = Mockito.mock(SkillRegistryService.class);
        AgentController controller = new AgentController(engineOrchestrator, toolFileStorageService, skillRegistryService);
        AuthContextHolder.set(AuthenticatedUser.builder().userId(88L).username("u88").role("user").build());
        ToolCallDraft draft = ToolCallDraft.builder()
                .toolCallId("tc-1")
                .toolName("cad_text_extractor_indicator_verification")
                .draftArgs("{\"query\":\"指标校核\"}")
                .approvalStatus("PENDING")
                .build();
        Mockito.when(engineOrchestrator.createManualToolDraft(
                Mockito.eq("conv-2"),
                Mockito.eq(88L),
                Mockito.eq("cad_text_extractor_indicator_verification"),
                Mockito.eq("指标校核")
        )).thenReturn(draft);
        AgentController.ToolDraftRequest request = new AgentController.ToolDraftRequest();
        request.setConversationId("conv-2");
        request.setToolCode("cad_text_extractor_indicator_verification");
        request.setQuery("指标校核");

        ToolCallDraft result = controller.createToolDraft(request);

        Assertions.assertNotNull(result);
        Assertions.assertEquals("tc-1", result.getToolCallId());
        Assertions.assertEquals("cad_text_extractor_indicator_verification", result.getToolName());
        AuthContextHolder.clear();
    }
}
