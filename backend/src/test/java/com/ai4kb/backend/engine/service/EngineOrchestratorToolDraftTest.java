package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.entity.RouteSample;
import com.ai4kb.backend.engine.model.ConversationState;
import com.ai4kb.backend.engine.model.Message;
import com.ai4kb.backend.engine.model.ToolCallDraft;
import com.ai4kb.backend.engine.model.openai.OpenAiChatRequest;
import com.ai4kb.backend.engine.model.openai.OpenAiChatResponse;
import com.ai4kb.backend.rag.processor.ChatProcessor;
import com.ai4kb.backend.skill.executor.impl.CadIndicatorVerificationSkillExecutor;
import com.ai4kb.backend.skill.executor.impl.SendEmailMockSkillExecutor;
import com.ai4kb.backend.skill.service.SkillExecutionService;
import com.ai4kb.backend.skill.service.SkillRegistryService;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.ai4kb.backend.user.mapper.PermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class EngineOrchestratorToolDraftTest {

    @Test
    void process_shouldReturnToolDraft_whenQueryContainsIndicatorVerificationText() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());

        List<ServerSentEvent<String>> events = orchestrator.process("c1", 1L, "帮我进行指标校核")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_draft", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(llmClient, Mockito.atLeastOnce()).chatStream(Mockito.any());
        ArgumentCaptor<ToolCallDraft> captor = ArgumentCaptor.forClass(ToolCallDraft.class);
        Mockito.verify(conversationService).saveDraft(captor.capture());
        ToolCallDraft draft = captor.getValue();
        Assertions.assertEquals("cad_text_extractor_indicator_verification", draft.getToolName());
        Assertions.assertNotNull(draft.getToolSpec());
        Assertions.assertEquals(true, draft.getToolSpec().get("upload_required"));
    }

    @Test
    void process_shouldUsePlannerToolRoute_whenPlannerReturnsToolDecision() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("TOOL", 0.9, "cad_text_extractor_indicator_verification")));

        List<ServerSentEvent<String>> events = orchestrator.process("c1-1", 1L, "请帮我检查这份CAD图里的指标是否合规")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_draft", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.never()).process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void process_shouldUsePlannerRagRoute_whenPlannerReturnsRagDecision() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("RAG", 0.86, null)));
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "什么是半面积", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积是按规则折算的面积。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c1-2", 1L, "什么是半面积")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("message", events.get(0).event());
        Assertions.assertTrue(events.get(0).data().contains("\"source\":\"RAG\""));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldEmitClarify_whenPlannerToolDecisionConfidenceIsLow() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("TOOL", 0.2, "cad_text_extractor_indicator_verification")));
        List<ServerSentEvent<String>> events = orchestrator.process("c1-3", 1L, "解释半面积的定义")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("clarify", events.get(0).event());
        Map<?, ?> payload = parseMap(events.get(0).data());
        Assertions.assertEquals("TOOL", payload.get("route"));
        Assertions.assertNotNull(payload.get("question"));
        Assertions.assertTrue(((List<?>) payload.get("suggestions")).size() > 0);
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.never()).process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void process_shouldEmitClarify_whenPlannerRagDecisionConfidenceIsLow() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("RAG", 0.3, null)));

        List<ServerSentEvent<String>> events = orchestrator.process("c1-4", 1L, "什么是半面积")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("clarify", events.get(0).event());
        Map<?, ?> payload = parseMap(events.get(0).data());
        Assertions.assertEquals("RAG", payload.get("route"));
        Assertions.assertNotNull(payload.get("question"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.never()).process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void process_shouldUseRaceAndPickRag_whenPlannerRagConfidenceIsMedium() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(
                        reactor.core.publisher.Flux.just(buildPlannerResponse("RAG", 0.6, null)),
                        reactor.core.publisher.Flux.just(buildLlmMessageResponse("这是通用回答"))
                );
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "什么是半面积", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积是按规则折算的面积。\",\"reference\":[{\"document_name\":\"规范A\",\"content\":\"半面积说明\",\"similarity\":0.9}]}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c1-race-1", 1L, "什么是半面积")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("message", events.get(0).event());
        Map<?, ?> payload = parseMap(events.get(0).data());
        Assertions.assertTrue(String.valueOf(payload.get("answer")).contains("半面积"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldUseRaceAndPickChat_whenRagUnavailable() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(
                        reactor.core.publisher.Flux.just(buildPlannerResponse("CHAT", 0.62, null)),
                        reactor.core.publisher.Flux.just(buildLlmMessageResponse("这是通用对话结果"))
                );
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "你怎么看这个问题", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"Sorry! No relevant content was found in the knowledge base!\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c1-race-2", 1L, "你怎么看这个问题")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("token", events.get(0).event());
        Assertions.assertTrue(events.get(0).data().contains("通用对话结果"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldUseLocalRouterDirect_whenEnabledAndGeneralDialogue() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "localRouterAutoThreshold", 0.90d);
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildLlmMessageResponse("你好，我是AI助手。")));

        List<ServerSentEvent<String>> events = orchestrator.process("c1-local-1", 1L, "你好，你是谁")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("token", events.get(0).event());
        ArgumentCaptor<OpenAiChatRequest> captor = ArgumentCaptor.forClass(OpenAiChatRequest.class);
        Mockito.verify(llmClient, Mockito.atLeastOnce()).chatStream(captor.capture());
        boolean plannerCallUsed = captor.getAllValues().stream()
                .map(OpenAiChatRequest::getTools)
                .filter(tools -> tools != null && !tools.isEmpty())
                .flatMap(List::stream)
                .anyMatch(tool -> tool.getFunction() != null && "route_decision".equals(tool.getFunction().getName()));
        Assertions.assertFalse(plannerCallUsed);
    }

    @Test
    void process_shouldReviewByPlanner_whenEnabledAndLocalToolDecisionIsHighRisk() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("TOOL", 0.88, "cad_text_extractor_indicator_verification")));

        List<ServerSentEvent<String>> events = orchestrator.process("c1-local-2", 1L, "请帮我进行指标校核")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_draft", events.get(0).event());
        ArgumentCaptor<OpenAiChatRequest> captor = ArgumentCaptor.forClass(OpenAiChatRequest.class);
        Mockito.verify(llmClient, Mockito.atLeastOnce()).chatStream(captor.capture());
        boolean plannerCallUsed = captor.getAllValues().stream()
                .map(OpenAiChatRequest::getTools)
                .filter(tools -> tools != null && !tools.isEmpty())
                .flatMap(List::stream)
                .anyMatch(tool -> tool.getFunction() != null && "route_decision".equals(tool.getFunction().getName()));
        Assertions.assertTrue(plannerCallUsed);
    }

    @Test
    void process_shouldRouteIndicatorPhrasesDifferently_whenLocalRouterEnabled() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "localRouterAutoThreshold", 0.90d);
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "什么是指标校核", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"指标校核是对指标合规性的校验过程。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> explainEvents = orchestrator.process("c1-local-case-1", 1L, "什么是指标校核")
                .collectList()
                .block();
        List<ServerSentEvent<String>> executeEvents = orchestrator.process("c1-local-case-2", 1L, "请使用指标校核")
                .collectList()
                .block();
        List<ServerSentEvent<String>> helpEvents = orchestrator.process("c1-local-case-3", 1L, "请帮我指标校核")
                .collectList()
                .block();

        Assertions.assertNotNull(explainEvents);
        Assertions.assertFalse(explainEvents.isEmpty());
        Assertions.assertEquals("message", explainEvents.get(0).event());
        Assertions.assertTrue(explainEvents.get(0).data().contains("【相关技能】指标校核：上传 CAD 图纸后输出校核结果文件"));
        Assertions.assertEquals("done", explainEvents.get(explainEvents.size() - 1).event());

        Assertions.assertNotNull(executeEvents);
        Assertions.assertFalse(executeEvents.isEmpty());
        Assertions.assertEquals("tool_draft", executeEvents.get(0).event());
        Assertions.assertEquals("done", executeEvents.get(executeEvents.size() - 1).event());

        Assertions.assertNotNull(helpEvents);
        Assertions.assertFalse(helpEvents.isEmpty());
        Assertions.assertEquals("tool_draft", helpEvents.get(0).event());
        Assertions.assertEquals("done", helpEvents.get(helpEvents.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.times(1)).process("admin", "什么是指标校核", true);
    }

    @Test
    void process_shouldRouteKnowledgeQueriesToRag_whenLocalRouterEnabled() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "localRouterAutoThreshold", 0.90d);
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "什么是大模型", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"大模型是参数规模较大的预训练模型。\",\"reference\":null}",
                        "[DONE]"
                ));
        Mockito.when(chatProcessor.process("admin", "如何进行半面积计算", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积计算需按规范折算系数处理。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> llmQueryEvents = orchestrator.process("c1-local-case-4", 1L, "什么是大模型")
                .collectList()
                .block();
        List<ServerSentEvent<String>> areaQueryEvents = orchestrator.process("c1-local-case-5", 1L, "如何进行半面积计算")
                .collectList()
                .block();

        Assertions.assertNotNull(llmQueryEvents);
        Assertions.assertFalse(llmQueryEvents.isEmpty());
        Assertions.assertEquals("message", llmQueryEvents.get(0).event());
        Assertions.assertEquals("done", llmQueryEvents.get(llmQueryEvents.size() - 1).event());

        Assertions.assertNotNull(areaQueryEvents);
        Assertions.assertFalse(areaQueryEvents.isEmpty());
        Assertions.assertEquals("message", areaQueryEvents.get(0).event());
        Assertions.assertEquals("done", areaQueryEvents.get(areaQueryEvents.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.times(1)).process("admin", "什么是大模型", true);
        Mockito.verify(chatProcessor, Mockito.times(1)).process("admin", "如何进行半面积计算", true);
    }

    @Test
    void process_shouldPersistRouteSample_whenRouteSampleServiceInjected() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        RouteSampleService routeSampleService = Mockito.mock(RouteSampleService.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "routeSampleService", routeSampleService);
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("TOOL", 0.9, "cad_text_extractor_indicator_verification")));

        List<ServerSentEvent<String>> events = orchestrator.process("c1-route-sample", 1L, "请帮我检查这份CAD图里的指标是否合规")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        ArgumentCaptor<RouteSample> sampleCaptor = ArgumentCaptor.forClass(RouteSample.class);
        Mockito.verify(routeSampleService, Mockito.atLeastOnce()).saveSample(sampleCaptor.capture());
        RouteSample sample = sampleCaptor.getValue();
        Assertions.assertEquals("c1-route-sample", sample.getConversationId());
        Assertions.assertEquals("PLANNER_ONLY", sample.getSource());
        Assertions.assertEquals("TOOL", sample.getChosenRoute());
    }

    @Test
    void processToolApproval_shouldEmitToolResultBeforeDone() throws Exception {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ConversationState state = ConversationState.builder()
                .conversationId("c1")
                .userId(1L)
                .status("WAITING_APPROVAL")
                .memoryWindow(new ArrayList<>(List.of(
                        Message.builder().role("user").content("帮我进行指标校核").build()
                )))
                .build();
        ToolCallDraft draft = ToolCallDraft.builder()
                .toolCallId("tc-1")
                .toolName("cad_text_extractor_indicator_verification")
                .draftArgs("{\"input_dir\":\"input\"}")
                .approvalStatus("PENDING")
                .build();
        Mockito.when(conversationService.getState("c1")).thenReturn(state);
        Mockito.when(conversationService.getDraft("tc-1")).thenReturn(draft);
        Mockito.when(executionService.execute("c1", "tc-1", "cad_text_extractor_indicator_verification", 1L, "{\"input_dir\":\"input\"}"))
                .thenReturn(Map.of("success", true, "summary", "ok"));
        Mockito.when(chatProcessor.process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean()))
                .thenReturn(reactor.core.publisher.Flux.just("[DONE]"));
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());

        List<ServerSentEvent<String>> events = orchestrator.processToolApproval("c1", "tc-1", null)
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_result", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldUseRag_whenNoSkillMatched() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());
        Mockito.when(chatProcessor.process("admin", "什么是半面积", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积是按规则折算的面积。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c2", 1L, "什么是半面积")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("message", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(llmClient, Mockito.atLeastOnce()).chatStream(Mockito.any());
    }

    @Test
    void process_shouldFallbackToLlm_whenRagReturnsFailurePayload() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "半面积定义", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"Chat failed: upstream timeout\",\"reference\":null}",
                        "[DONE]"
                ));
        OpenAiChatResponse llmResp = new OpenAiChatResponse();
        OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
        OpenAiChatResponse.Delta delta = new OpenAiChatResponse.Delta();
        delta.setContent("我是AI助手。");
        choice.setDelta(delta);
        llmResp.setChoices(List.of(choice));
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(llmResp));

        List<ServerSentEvent<String>> events = orchestrator.process("c3", 1L, "半面积定义")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("token", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(llmClient, Mockito.atLeast(2)).chatStream(Mockito.any());
    }

    @Test
    void process_shouldUseLocalFallback_whenGeneralDialogueAndLlmUnavailable() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());

        List<ServerSentEvent<String>> events = orchestrator.process("c4", 1L, "你好，你是谁")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("token", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.never()).process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void process_shouldReturnToolDraft_whenLlmAutonomouslySelectsIndicatorSkill() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        OpenAiChatResponse selectorResp = new OpenAiChatResponse();
        OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
        OpenAiChatResponse.Message message = new OpenAiChatResponse.Message();
        OpenAiChatResponse.ToolCall toolCall = new OpenAiChatResponse.ToolCall();
        OpenAiChatResponse.Function function = new OpenAiChatResponse.Function();
        function.setName("cad_text_extractor_indicator_verification");
        toolCall.setFunction(function);
        message.setToolCalls(List.of(toolCall));
        choice.setMessage(message);
        selectorResp.setChoices(List.of(choice));
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(selectorResp));

        List<ServerSentEvent<String>> events = orchestrator.process("c5", 1L, "请帮我检查这份CAD图里的指标是否合规")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_draft", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.never()).process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void process_shouldFallbackToLlm_whenRagReturnsStreamingGenericError() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        OpenAiChatResponse llmResp = new OpenAiChatResponse();
        OpenAiChatResponse.Choice llmChoice = new OpenAiChatResponse.Choice();
        OpenAiChatResponse.Delta llmDelta = new OpenAiChatResponse.Delta();
        llmDelta.setContent("半面积是按规定折算后的面积值。");
        llmChoice.setDelta(llmDelta);
        llmResp.setChoices(List.of(llmChoice));
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.empty())
                .thenReturn(reactor.core.publisher.Flux.just(llmResp));
        Mockito.when(chatProcessor.process("admin", "什么是半面积", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "ERROR: GENERIC_ERROR - An error occurred during streaming",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c6", 1L, "什么是半面积")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        String merged = events.stream()
                .filter(e -> "token".equals(e.event()))
                .map(ServerSentEvent::data)
                .reduce("", String::concat);
        Assertions.assertTrue(merged.contains("【检索状态】知识库中未检索到与“什么是半面积”直接相关的资料。"));
        Assertions.assertTrue(merged.contains("半面积是按规定折算后的面积值。"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(llmClient, Mockito.atLeast(2)).chatStream(Mockito.any());
    }

    @Test
    void process_shouldReturnToolDraft_whenLlmUnavailableButHeuristicMatchesIndicatorSkill() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());

        List<ServerSentEvent<String>> events = orchestrator.process("c7", 1L, "请帮我检查这份CAD图里的指标是否合规")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_draft", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.never()).process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void process_shouldUseRagLocalFallback_whenRagAndLlmUnavailable() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());
        Mockito.when(chatProcessor.process("admin", "什么是半面积", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"Chat failed: upstream timeout\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c8", 1L, "什么是半面积")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("token", events.get(0).event());
        String merged = events.stream()
                .filter(e -> "token".equals(e.event()))
                .map(ServerSentEvent::data)
                .reduce("", String::concat);
        Assertions.assertTrue(merged.contains("【检索状态】知识库中未检索到与“什么是半面积”直接相关的资料。"));
        Assertions.assertTrue(merged.contains("当前无法基于模型知识继续回答你的问题（什么是半面积）"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldFallbackToLlmAnswer_whenRagReturnsEmptyPayload() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "localRouterAutoThreshold", 0.80d);
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildLlmMessageResponse("半面积通常按顶盖水平投影面积的1/2计算。")));
        Mockito.when(chatProcessor.process("admin", "什么是半面积", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"\",\"reference\":[]}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c8-empty", 1L, "什么是半面积")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        String merged = events.stream()
                .filter(e -> "token".equals(e.event()))
                .map(ServerSentEvent::data)
                .reduce("", String::concat);
        Assertions.assertTrue(merged.contains("【检索状态】知识库中未检索到与“什么是半面积”直接相关的资料。"));
        Assertions.assertTrue(merged.contains("半面积通常按顶盖水平投影面积的1/2计算"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldUseSkillDescriptionWithLogicFlow_whenIndicatorKnowledgeRagMiss() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenAnswer(invocation -> reactor.core.publisher.Flux.just(buildPlannerResponse("RAG", 0.82, null)));
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "什么是指标校核", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"\",\"reference\":[]}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c8-skill-fallback", 1L, "什么是指标校核")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        ServerSentEvent<String> messageEvent = events.stream()
                .filter(e -> "message".equals(e.event()))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(messageEvent);
        Map<?, ?> payload = parseMap(messageEvent.data());
        Assertions.assertEquals("SKILL", payload.get("source"));
        Assertions.assertEquals("技能库检索", payload.get("sourceLabel"));
        Assertions.assertTrue(String.valueOf(payload.get("answer")).contains("指标校核"));
        Assertions.assertTrue(String.valueOf(payload.get("logicFlow")).contains("1. 查询RAG知识库"));
        Assertions.assertTrue(String.valueOf(payload.get("logicFlow")).contains("2. RAG未命中相关内容"));
        Assertions.assertTrue(String.valueOf(payload.get("logicFlow")).contains("3. 检索技能库并命中相关技能，返回技能描述"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldGenerateGroundedAnswer_whenRagReturnsReferenceOnlyPayload() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("RAG", 0.82, null)));
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildLlmMessageResponse("半面积通常按顶盖水平投影面积的1/2计算，具体以规范条文为准。")));
        Mockito.when(chatProcessor.process("admin", "如何进行半面积计算", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"\",\"reference\":[{\"document_name\":\"武自然资建规[2026]1号.pdf\",\"content\":\"无围护结构、单排柱或独立柱、不封闭的建筑空间，应按其顶盖水平投影面积的1/2计算。\"}]}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c8-ref-only", 1L, "如何进行半面积计算")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        ServerSentEvent<String> messageEvent = events.stream()
                .filter(e -> "message".equals(e.event()))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(messageEvent);
        Map<?, ?> payload = parseMap(messageEvent.data());
        Assertions.assertEquals("RAG", payload.get("source"));
        Assertions.assertEquals("RAG检索", payload.get("sourceLabel"));
        Assertions.assertTrue(String.valueOf(payload.get("answer")).contains("1/2计算"));
        Object refsObj = payload.get("reference");
        Assertions.assertTrue(refsObj instanceof List<?>);
        Assertions.assertFalse(((List<?>) refsObj).isEmpty());
        String mergedToken = events.stream()
                .filter(e -> "token".equals(e.event()))
                .map(ServerSentEvent::data)
                .reduce("", String::concat);
        Assertions.assertFalse(mergedToken.contains("【检索状态】"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldEmitReferencePayload_whenRagStreamReturnsAnswerThenReferenceOnlyChunk() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenAnswer(invocation -> reactor.core.publisher.Flux.just(buildPlannerResponse("RAG", 0.82, null)));
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "如何进行半面积计算", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积可按顶盖投影面积折算。\",\"reference\":null}",
                        "{\"answer\":\"\",\"reference\":[{\"document_name\":\"武自然资建规[2026]1号.pdf\",\"content\":\"无围护结构按顶盖投影面积1/2计算。\",\"similarity\":0.93}]}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c11-rag-ref-tail", 1L, "如何进行半面积计算")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        ServerSentEvent<String> refEvent = events.stream()
                .filter(e -> "message".equals(e.event()) && e.data() != null && e.data().contains("\"reference\":["))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(refEvent);
        Map<?, ?> payload = parseMap(refEvent.data());
        Object refsObj = payload.get("reference");
        Assertions.assertTrue(refsObj instanceof List<?>);
        Assertions.assertFalse(((List<?>) refsObj).isEmpty());
        Assertions.assertEquals("RAG", payload.get("source"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldRespectConfiguredHighToolThreshold_whenPlannerReturnsToolDecision() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "toolConfidenceThreshold", 0.95d);
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("TOOL", 0.90, "cad_text_extractor_indicator_verification")));
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "解释半面积的定义", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积是按规则折算的面积。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.process("c9", 1L, "解释半面积的定义")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("message", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldUseDefaultThreshold_whenConfiguredThresholdIsInvalid() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "toolConfidenceThreshold", -0.1d);
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("TOOL", 0.60, "cad_text_extractor_indicator_verification")));

        List<ServerSentEvent<String>> events = orchestrator.process("c10", 1L, "请帮我检查这份CAD图里的指标是否合规")
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_draft", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void processAdvanced_shouldEmitAnalysisPlanAndSummary_whenExecutingFlow() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        ReflectionTestUtils.setField(orchestrator, "localRouterEnabled", true);
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("RAG", 0.91, null)));
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "什么是半面积", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积是按规则折算的面积。\",\"reference\":[{\"document_name\":\"规范A\",\"content\":\"半面积说明\",\"similarity\":0.9}]}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c12-advanced",
                        1L,
                        "什么是半面积",
                        "优先解释定义",
                        List.of(),
                        "AUTO",
                        1,
                        false
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("analysis_plan", events.get(0).event());
        Assertions.assertTrue(events.stream().anyMatch(e -> "analysis_step".equals(e.event())));
        Assertions.assertTrue(events.stream().anyMatch(e -> "analysis_summary".equals(e.event())));
        List<ServerSentEvent<String>> stepEvents = events.stream()
                .filter(e -> "analysis_step".equals(e.event()))
                .toList();
        Assertions.assertFalse(stepEvents.isEmpty());
        int prevStepNo = 0;
        for (ServerSentEvent<String> stepEvent : stepEvents) {
            Map<?, ?> payload = parseMap(stepEvent.data());
            Object noObj = payload.get("step_no");
            Assertions.assertNotNull(noObj);
            int currentNo = Integer.parseInt(String.valueOf(noObj));
            Assertions.assertTrue(currentNo > prevStepNo);
            prevStepNo = currentNo;
            Assertions.assertNotNull(payload.get("status"));
        }
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void processAdvanced_shouldReturnPlanOnly_whenReplanOnlyEnabled() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("CHAT", 0.8, null)));

        List<Map<String, Object>> editedSteps = List.of(
                Map.of(
                        "route", "RAG",
                        "label", "RAG查询",
                        "goal", "先检索知识库",
                        "query", "什么是指标校核"
                )
        );

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c13-replan-only",
                        1L,
                        "什么是指标校核",
                        "从第一步重排",
                        editedSteps,
                        "FULL_RERUN",
                        1,
                        true
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("analysis_plan", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Assertions.assertFalse(events.stream().anyMatch(e -> "message".equals(e.event())));
        Mockito.verify(chatProcessor, Mockito.never()).process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void processAdvanced_shouldNormalizeMiddleRerunAndKeepEditedStepFields_whenReplanOnlyEnabled() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());

        List<Map<String, Object>> editedSteps = List.of(
                Map.of(
                        "stepNo", 1,
                        "route", "RAG",
                        "label", "RAG查询",
                        "goal", "先检索知识库",
                        "query", "什么是指标校核"
                ),
                Map.of(
                        "stepNo", 2,
                        "route", "SKILL",
                        "label", "技能库查询",
                        "goal", "优先技能库匹配",
                        "query", "指标校核技能",
                        "toolName", "cad_text_extractor_indicator_verification",
                        "continueWhen", "技能未命中时继续",
                        "stopWhen", "技能结果满足任务目标"
                )
        );

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c14-middle-rerun",
                        1L,
                        "什么是指标校核",
                        "从第二步开始执行",
                        editedSteps,
                        "MIDDLE_RERUN",
                        2,
                        true
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("analysis_plan", events.get(0).event());
        Map<?, ?> payload = parseMap(events.get(0).data());
        Assertions.assertEquals("PARTIAL_RERUN", payload.get("rerunMode"));
        Assertions.assertEquals(2, payload.get("restartFromStep"));
        List<?> steps = (List<?>) payload.get("steps");
        Assertions.assertEquals(2, steps.size());
        Map<?, ?> secondStep = (Map<?, ?>) steps.get(1);
        Assertions.assertEquals("cad_text_extractor_indicator_verification", secondStep.get("toolName"));
        Assertions.assertEquals("技能未命中时继续", secondStep.get("continueWhen"));
        Assertions.assertEquals("技能结果满足任务目标", secondStep.get("stopWhen"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void processAdvanced_shouldMergeEditedStepByStepNo_whenPlannerReturnsPlan() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildAnalysisPlanResponse()));

        List<Map<String, Object>> editedSteps = List.of(
                Map.of(
                        "stepNo", 2,
                        "route", "SKILL",
                        "label", "技能库查询",
                        "goal", "命中技能后直接返回",
                        "query", "指标校核技能",
                        "toolName", "cad_text_extractor_indicator_verification"
                )
        );

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c15-stepno-merge",
                        1L,
                        "什么是指标校核",
                        "修改第二步",
                        editedSteps,
                        "FULL_RERUN",
                        1,
                        true
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("analysis_plan", events.get(0).event());
        Map<?, ?> payload = parseMap(events.get(0).data());
        List<?> steps = (List<?>) payload.get("steps");
        Assertions.assertEquals(3, steps.size());
        Map<?, ?> firstStep = (Map<?, ?>) steps.get(0);
        Map<?, ?> secondStep = (Map<?, ?>) steps.get(1);
        Assertions.assertEquals("RAG", firstStep.get("route"));
        Assertions.assertEquals("SKILL", secondStep.get("route"));
        Assertions.assertEquals("cad_text_extractor_indicator_verification", secondStep.get("toolName"));
        Assertions.assertEquals("命中技能后直接返回", secondStep.get("goal"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void processAdvanced_shouldSplitCompositeTasksIntoIndependentPlanSteps_whenFallbackPlannerUsed() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c16-plan-split",
                        1L,
                        "执行三件事：1、查找如何进行半面积计算 2、查找指标校核工具怎么用的 3、调用指标校核工具",
                        "",
                        List.of(),
                        "AUTO",
                        1,
                        true
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("analysis_plan", events.get(0).event());
        Map<?, ?> payload = parseMap(events.get(0).data());
        List<?> steps = (List<?>) payload.get("steps");
        Assertions.assertNotNull(steps);
        Assertions.assertTrue(steps.size() >= 3);
        List<String> stepQueries = steps.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("query")))
                .toList();
        Assertions.assertTrue(stepQueries.stream().anyMatch(q -> q.contains("半面积计算")));
        Assertions.assertTrue(stepQueries.stream().anyMatch(q -> q.contains("怎么用")));
        Assertions.assertTrue(stepQueries.stream().anyMatch(q -> q.contains("调用")));
        Assertions.assertTrue(steps.stream().anyMatch(item -> "SKILL".equals(String.valueOf(((Map<?, ?>) item).get("route")))));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void processAdvanced_shouldRebuildAtomicPlanWhenPlannerReturnsSameCompositeQueryForAllSteps() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        String compositeQuery = "执行三件事：1、查找如何进行半面积计算 2、查找指标校核工具怎么用的 3、调用指标校核工具";
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildAnalysisPlanResponseWithSteps(List.of(
                        Map.of("route", "RAG", "label", "RAG查询", "goal", "查询知识库", "query", compositeQuery),
                        Map.of("route", "SKILL", "label", "技能库查询", "goal", "检查技能", "query", compositeQuery),
                        Map.of("route", "LLM", "label", "默认知识回答", "goal", "补充回答", "query", compositeQuery)
                ))));

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c16b-plan-rebuild",
                        1L,
                        compositeQuery,
                        "",
                        List.of(),
                        "AUTO",
                        1,
                        true
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("analysis_plan", events.get(0).event());
        Map<?, ?> payload = parseMap(events.get(0).data());
        List<?> steps = (List<?>) payload.get("steps");
        Assertions.assertNotNull(steps);
        Assertions.assertTrue(steps.size() >= 3);
        List<String> stepQueries = steps.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("query")))
                .toList();
        Assertions.assertTrue(stepQueries.stream().anyMatch(q -> q.contains("半面积计算")));
        Assertions.assertTrue(stepQueries.stream().anyMatch(q -> q.contains("怎么用")));
        Assertions.assertTrue(stepQueries.stream().anyMatch(q -> q.contains("调用")));
        Assertions.assertFalse(stepQueries.stream().allMatch(q -> q.equals(compositeQuery)));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void process_shouldPreferToolWhenPlannerReturnsToolForMixedKnowledgeAndActionQuery() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildPlannerResponse("TOOL", 0.95, "cad_text_extractor_indicator_verification")));

        List<ServerSentEvent<String>> events = orchestrator.process(
                        "c17-tool-priority",
                        1L,
                        "查找指标校核工具怎么用，并调用指标校核工具"
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_draft", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor, Mockito.never()).process(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void process_shouldFallbackToToolWhenPlannerUnknownButQueryContainsExplicitToolAction() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any())).thenReturn(reactor.core.publisher.Flux.empty());

        List<ServerSentEvent<String>> events = orchestrator.process(
                        "c18-fallback-tool",
                        1L,
                        "请先解释指标校核，再调用指标校核工具"
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("tool_draft", events.get(0).event());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void processAdvanced_shouldExecutePlanStepsSequentiallyAndContinueAfterToolDraft() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(
                        reactor.core.publisher.Flux.just(buildAnalysisPlanResponseWithSteps(List.of(
                                Map.of("route", "RAG", "label", "RAG查询", "goal", "查询定义", "query", "半面积计算规则"),
                                Map.of("route", "SKILL", "label", "技能调用", "goal", "调用指标校核工具", "query", "调用指标校核工具"),
                                Map.of("route", "LLM", "label", "默认知识回答", "goal", "汇总当前进展", "query", "请汇总当前进展")
                        ))),
                        reactor.core.publisher.Flux.just(buildLlmMessageResponse("已汇总：等待审批后可继续工具执行。"))
                );
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "半面积计算规则", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积按规则折算。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c19-plan-exec",
                        1L,
                        "执行两件事：先查半面积，再调用指标校核工具",
                        "",
                        List.of(),
                        "AUTO",
                        1,
                        false
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("analysis_plan", events.get(0).event());
        Assertions.assertTrue(events.stream().anyMatch(event -> "message".equals(event.event())));
        Assertions.assertTrue(events.stream().anyMatch(event -> "tool_draft".equals(event.event())));
        Assertions.assertTrue(events.stream().anyMatch(event -> "token".equals(event.event())));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor).process("admin", "半面积计算规则", true);
    }

    @Test
    void processAdvanced_shouldCountAnalysisStepsByPlanStepInsteadOfRagChunks() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildAnalysisPlanResponseWithSteps(List.of(
                        Map.of("route", "RAG", "label", "RAG查询", "goal", "查询定义", "query", "半面积计算规则"),
                        Map.of("route", "SKILL", "label", "技能调用", "goal", "调用指标校核工具", "query", "调用指标校核工具")
                ))));
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "半面积计算规则", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积按规则折算第一段。\",\"reference\":null}",
                        "{\"answer\":\"第二段补充。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c19b-step-count",
                        1L,
                        "执行两件事：先查半面积，再调用指标校核工具",
                        "",
                        List.of(),
                        "AUTO",
                        1,
                        false
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        long analysisStepCount = events.stream().filter(event -> "analysis_step".equals(event.event())).count();
        Assertions.assertEquals(2L, analysisStepCount);
        Map<?, ?> summary = events.stream()
                .filter(event -> "analysis_summary".equals(event.event()))
                .findFirst()
                .map(event -> parseMap(event.data()))
                .orElse(Map.of());
        Number stepCount = summary.get("step_count") instanceof Number n ? n : 0;
        Assertions.assertEquals(2, stepCount.intValue());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void processAdvanced_shouldExecuteThreeTasksAndContinueToFinalLlmSummary() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(
                        reactor.core.publisher.Flux.just(buildAnalysisPlanResponseWithSteps(List.of(
                                Map.of("route", "RAG", "label", "RAG查询", "goal", "查半面积", "query", "查找如何进行半面积计算"),
                                Map.of("route", "SKILL", "label", "技能库查询", "goal", "查工具怎么用", "query", "查找指标校核工具怎么用的"),
                                Map.of("route", "SKILL", "label", "技能调用", "goal", "调用工具", "query", "调用指标校核工具", "toolName", "cad_text_extractor_indicator_verification"),
                                Map.of("route", "LLM", "label", "默认知识回答", "goal", "整合前序结果", "query", "执行三件事：1、查找如何进行半面积计算 2、查找指标校核工具怎么用的 3、调用指标校核工具")
                        ))),
                        reactor.core.publisher.Flux.just(buildLlmMessageResponse("最终汇总：工具调用待审批，其他步骤已完成。"))
                );
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "查找如何进行半面积计算", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"半面积可按规范条文折算。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c19c-three-tasks",
                        1L,
                        "执行三件事：1、查找如何进行半面积计算 2、查找指标校核工具怎么用的 3、调用指标校核工具",
                        "",
                        List.of(),
                        "AUTO",
                        1,
                        false
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        long analysisStepCount = events.stream().filter(event -> "analysis_step".equals(event.event())).count();
        Assertions.assertEquals(4L, analysisStepCount);
        boolean hasUsageSkillMessage = events.stream()
                .filter(event -> "message".equals(event.event()))
                .map(ServerSentEvent::data)
                .filter(data -> data != null)
                .anyMatch(data -> data.contains("\"source\":\"SKILL\"") && data.contains("技能库匹配到技能描述"));
        Assertions.assertTrue(hasUsageSkillMessage);
        boolean hasToolDraft = events.stream().anyMatch(event -> "tool_draft".equals(event.event()));
        Assertions.assertTrue(hasToolDraft);
        Map<?, ?> summary = events.stream()
                .filter(event -> "analysis_summary".equals(event.event()))
                .findFirst()
                .map(event -> parseMap(event.data()))
                .orElse(Map.of());
        Number stepCount = summary.get("step_count") instanceof Number n ? n : 0;
        Assertions.assertEquals(4, stepCount.intValue());
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void processAdvanced_shouldTrySkillThenFallbackToRagWhenSkillNotFound() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        SkillExecutionService executionService = Mockito.mock(SkillExecutionService.class);
        ChatProcessor chatProcessor = Mockito.mock(ChatProcessor.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of());
        SkillRegistryService registryService = new SkillRegistryService(
                List.of(new CadIndicatorVerificationSkillExecutor(), new SendEmailMockSkillExecutor()),
                permissionMapper
        );
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EngineOrchestrator orchestrator = new EngineOrchestrator(
                llmClient,
                conversationService,
                objectMapper,
                registryService,
                executionService,
                chatProcessor,
                userMapper
        );
        Mockito.when(llmClient.chatStream(Mockito.any()))
                .thenReturn(reactor.core.publisher.Flux.just(buildAnalysisPlanResponseWithSteps(List.of(
                        Map.of("route", "SKILL", "label", "技能调用", "goal", "尝试技能", "query", "查询半面积计算规范")
                ))));
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Mockito.when(userMapper.selectById(1L)).thenReturn(user);
        Mockito.when(chatProcessor.process("admin", "查询半面积计算规范", true))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "{\"answer\":\"可参考建筑面积计算规范。\",\"reference\":null}",
                        "[DONE]"
                ));

        List<ServerSentEvent<String>> events = orchestrator.processAdvanced(
                        "c20-plan-fallback",
                        1L,
                        "请尝试技能查半面积",
                        "",
                        List.of(),
                        "AUTO",
                        1,
                        false
                )
                .collectList()
                .block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertTrue(events.stream().noneMatch(event -> "tool_draft".equals(event.event())));
        Assertions.assertTrue(events.stream().anyMatch(event -> "message".equals(event.event())));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(chatProcessor).process("admin", "查询半面积计算规范", true);
    }

    private OpenAiChatResponse buildPlannerResponse(String route, double confidence, String toolName) {
        OpenAiChatResponse response = new OpenAiChatResponse();
        OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
        OpenAiChatResponse.Message message = new OpenAiChatResponse.Message();
        OpenAiChatResponse.ToolCall toolCall = new OpenAiChatResponse.ToolCall();
        OpenAiChatResponse.Function function = new OpenAiChatResponse.Function();
        function.setName("route_decision");
        String toolField = toolName == null ? "null" : "\"" + toolName + "\"";
        function.setArguments("{\"route\":\"" + route + "\",\"confidence\":" + confidence + ",\"tool_name\":" + toolField + "}");
        toolCall.setFunction(function);
        message.setToolCalls(List.of(toolCall));
        choice.setMessage(message);
        response.setChoices(List.of(choice));
        return response;
    }

    private OpenAiChatResponse buildLlmMessageResponse(String text) {
        OpenAiChatResponse response = new OpenAiChatResponse();
        OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
        OpenAiChatResponse.Message message = new OpenAiChatResponse.Message();
        message.setContent(text);
        choice.setMessage(message);
        response.setChoices(List.of(choice));
        return response;
    }

    private OpenAiChatResponse buildAnalysisPlanResponse() {
        OpenAiChatResponse response = new OpenAiChatResponse();
        OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
        OpenAiChatResponse.Message message = new OpenAiChatResponse.Message();
        OpenAiChatResponse.ToolCall toolCall = new OpenAiChatResponse.ToolCall();
        OpenAiChatResponse.Function function = new OpenAiChatResponse.Function();
        function.setName("analysis_plan");
        function.setArguments("{\"deep_thinking\":\"先做知识检索再看技能\",\"question_type\":\"TASK\",\"summary\":\"按计划执行\",\"steps\":[{\"route\":\"RAG\",\"label\":\"RAG查询\",\"goal\":\"查询知识库\",\"query\":\"什么是指标校核\"},{\"route\":\"LLM\",\"label\":\"默认知识回答\",\"goal\":\"给出通用解释\",\"query\":\"什么是指标校核\"},{\"route\":\"SUMMARY\",\"label\":\"总结\",\"goal\":\"汇总结论\",\"query\":\"什么是指标校核\"}]}");
        toolCall.setFunction(function);
        message.setToolCalls(List.of(toolCall));
        choice.setMessage(message);
        response.setChoices(List.of(choice));
        return response;
    }

    private OpenAiChatResponse buildAnalysisPlanResponseWithSteps(List<Map<String, Object>> steps) {
        OpenAiChatResponse response = new OpenAiChatResponse();
        OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
        OpenAiChatResponse.Message message = new OpenAiChatResponse.Message();
        OpenAiChatResponse.ToolCall toolCall = new OpenAiChatResponse.ToolCall();
        OpenAiChatResponse.Function function = new OpenAiChatResponse.Function();
        function.setName("analysis_plan");
        Map<String, Object> payload = Map.of(
                "deep_thinking", "按子任务执行",
                "question_type", "TASK",
                "summary", "分批执行",
                "steps", steps
        );
        try {
            function.setArguments(new ObjectMapper().writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        toolCall.setFunction(function);
        message.setToolCalls(List.of(toolCall));
        choice.setMessage(message);
        response.setChoices(List.of(choice));
        return response;
    }

    private Map<?, ?> parseMap(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            Assertions.fail("invalid json payload: " + json);
            return Map.of();
        }
    }
}
