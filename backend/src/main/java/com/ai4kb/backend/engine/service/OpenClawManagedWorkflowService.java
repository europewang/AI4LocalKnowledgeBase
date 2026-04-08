package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.config.BrainOpenClawProperties;
import com.ai4kb.backend.engine.model.ToolCallDraft;
import com.ai4kb.backend.engine.model.openai.OpenAiChatResponse;
import com.ai4kb.backend.rag.processor.ChatProcessor;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.ai4kb.backend.skill.service.SkillRegistryService;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OpenClawManagedWorkflowService {
    private static final Pattern ROUTE_JSON_PATTERN = Pattern.compile("\\{\\s*\"route\"\\s*:\\s*\"(?:RAG|LLM|SKILL|SKILL_DESC)\"[\\s\\S]*?\\}", Pattern.CASE_INSENSITIVE);
    private final BrainOpenClawProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final SkillRegistryService skillRegistryService;
    private final EngineOrchestrator engineOrchestrator;
    private final ChatProcessor chatProcessor;
    private final UserMapper userMapper;

    public boolean isManagedConfigured() {
        return StringUtils.hasText(properties.getManagedBaseUrl());
    }

    public Flux<ServerSentEvent<String>> chatStream(String conversationId,
                                                    Long userId,
                                                    String query,
                                                    String adjustmentInstruction,
                                                    List<Map<String, Object>> editedSteps,
                                                    String rerunMode,
                                                    Integer restartFromStep,
                                                    boolean replanOnly) {
        String baseUrl = safeText(properties.getManagedBaseUrl());
        if (baseUrl.isBlank()) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("OpenClaw托管未配置 ai4kb.brain.openclaw.managed-base-url").build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        String chatPath = safeText(properties.getManagedChatPath());
        if (chatPath.isBlank()) {
            chatPath = "/api/v1/agent/chat/stream";
        }
        String token = safeText(properties.getManagedApiKey());
        if (token.isBlank()) {
            token = safeText(properties.getApiKey());
        }
        final String finalToken = token;
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        if (isOpenAiCompatPath(chatPath)) {
            Map<String, Object> payload = buildOpenAiCompatPayload(query, userId);
            return client.post()
                    .uri(chatPath)
                    .headers(headers -> {
                        applyHeaders(headers, finalToken);
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class).flatMap(body ->
                                    reactor.core.publisher.Mono.error(new RuntimeException("OpenClaw托管请求失败: " + body))))
                    .bodyToMono(String.class)
                    .flatMapMany(body -> mapOpenAiNonStreamResponse(body, safeText(conversationId), userId, safeText(query), true))
                    .onErrorResume(ex -> Flux.just(
                            ServerSentEvent.<String>builder().event("error").data("OpenClaw托管调用失败：" + ex.getMessage()).build(),
                            ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                    ));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversation_id", safeText(conversationId));
        payload.put("user_id", userId);
        payload.put("query", safeText(query));
        payload.put("adjustment_instruction", safeText(adjustmentInstruction));
        payload.put("edited_steps", editedSteps == null ? List.of() : editedSteps);
        payload.put("rerun_mode", safeText(rerunMode));
        payload.put("restart_from_step", restartFromStep == null ? 1 : restartFromStep);
        payload.put("replan_only", replanOnly);
        payload.put("rag_search_url", safeText(properties.getRagSearchUrl()));
        payload.put("skill_catalog_url", safeText(properties.getSkillCatalogUrl()));
        payload.put("skill_execute_url", safeText(properties.getSkillExecuteUrl()));
        payload.put("workflow_spec_url", safeText(properties.getWorkflowSpecUrl()));
        payload.put("brain_mode", "openclaw_hosted");
        return client.post()
                .uri(chatPath)
                .headers(headers -> {
                    applyHeaders(headers, finalToken);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                reactor.core.publisher.Mono.error(new RuntimeException("OpenClaw托管请求失败: " + body))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(event -> {
                    String eventName = event.event() == null ? "message" : event.event();
                    String data = event.data() == null ? "" : safeText(String.valueOf(event.data()));
                    if (!"done".equalsIgnoreCase(eventName) && data.isBlank()) {
                        return ServerSentEvent.<String>builder().event("error").data("OpenClaw托管返回空消息").build();
                    }
                    if ("message".equalsIgnoreCase(eventName) && isEmptyManagedMessagePayload(data)) {
                        return ServerSentEvent.<String>builder().event("error").data("OpenClaw托管返回空内容").build();
                    }
                    if ("done".equalsIgnoreCase(eventName) && data.isBlank()) {
                        data = "[DONE]";
                    }
                    return ServerSentEvent.<String>builder().event(eventName).data(data).build();
                })
                .onErrorResume(ex -> Flux.just(
                        ServerSentEvent.<String>builder().event("error").data("OpenClaw托管调用失败：" + ex.getMessage()).build(),
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                ));
    }

    private boolean isEmptyManagedMessagePayload(String data) {
        String normalized = safeText(data);
        if (normalized.isBlank()) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(normalized);
            if (node == null || !node.isObject()) {
                return false;
            }
            String answer = safeText(node.path("answer").asText(""));
            String content = safeText(node.path("content").asText(""));
            String message = safeText(node.path("message").asText(""));
            String text = safeText(node.path("text").asText(""));
            JsonNode dataNode = node.path("data");
            String dataAnswer = safeText(dataNode.path("answer").asText(""));
            String dataContent = safeText(dataNode.path("content").asText(""));
            String dataMessage = safeText(dataNode.path("message").asText(""));
            String dataText = safeText(dataNode.path("text").asText(""));
            boolean hasText = !answer.isBlank()
                    || !content.isBlank()
                    || !message.isBlank()
                    || !text.isBlank()
                    || !dataAnswer.isBlank()
                    || !dataContent.isBlank()
                    || !dataMessage.isBlank()
                    || !dataText.isBlank();
            if (hasText) {
                return false;
            }
            JsonNode refs = node.path("reference");
            if (refs.isArray() && refs.size() > 0) {
                return false;
            }
            JsonNode dataRefs = dataNode.path("reference");
            if (dataRefs.isArray() && dataRefs.size() > 0) {
                return false;
            }
            JsonNode chunks = refs.path("chunks");
            if (chunks.isArray() && chunks.size() > 0) {
                return false;
            }
            JsonNode dataChunks = dataRefs.path("chunks");
            return !dataChunks.isArray() || dataChunks.size() == 0;
        } catch (Exception ignore) {
            return false;
        }
    }

    private boolean isOpenAiCompatPath(String chatPath) {
        String path = safeText(chatPath);
        return path.endsWith("/chat/completions");
    }

    private ServerSentEvent<String> mapOpenAiEvent(ServerSentEvent<String> event) {
        String data = event.data() == null ? "" : String.valueOf(event.data()).trim();
        if (data.isBlank()) {
            return null;
        }
        if ("[DONE]".equals(data)) {
            return ServerSentEvent.<String>builder().event("done").data("[DONE]").build();
        }
        try {
            OpenAiChatResponse response = objectMapper.readValue(data, OpenAiChatResponse.class);
            if (response.getChoices() == null || response.getChoices().isEmpty()) {
                return null;
            }
            OpenAiChatResponse.Choice choice = response.getChoices().get(0);
            if (choice == null || choice.getDelta() == null || !StringUtils.hasText(choice.getDelta().getContent())) {
                return null;
            }
            return ServerSentEvent.<String>builder().event("token").data(choice.getDelta().getContent()).build();
        } catch (Exception ignore) {
            return null;
        }
    }

    private Flux<ServerSentEvent<String>> mapOpenAiNonStreamResponse(String body,
                                                                     String conversationId,
                                                                     Long userId,
                                                                     String query,
                                                                     boolean allowRagRoute) {
        try {
            OpenAiChatResponse response = objectMapper.readValue(body, OpenAiChatResponse.class);
            if (response.getChoices() == null || response.getChoices().isEmpty() || response.getChoices().get(0) == null) {
                return Flux.just(
                        ServerSentEvent.<String>builder().event("error").data("OpenClaw托管调用失败：响应为空").build(),
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                );
            }
            OpenAiChatResponse.Choice choice = response.getChoices().get(0);
            if (choice.getMessage() != null && choice.getMessage().getToolCalls() != null && !choice.getMessage().getToolCalls().isEmpty()) {
                return mapOpenAiToolCallsToDraft(choice.getMessage().getToolCalls(), conversationId, userId, query);
            }
            String content = choice.getMessage() == null ? "" : safeText(choice.getMessage().getContent());
            if (content.isBlank()) {
                return Flux.just(
                        ServerSentEvent.<String>builder().event("error").data("OpenClaw托管调用失败：响应内容为空").build(),
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                );
            }
            if ("No response from OpenClaw.".equalsIgnoreCase(content)) {
                return Flux.just(
                        ServerSentEvent.<String>builder().event("error").data("OpenClaw托管调用失败：No response from OpenClaw").build(),
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                );
            }
            Optional<RouteDecisionResult> decision = parseRouteDecision(content);
            if (decision.isPresent()) {
                return applyRouteDecisionResult(decision.get(), conversationId, userId, query, content, allowRagRoute);
            }
            return applyLocalRouteFallback(conversationId, userId, query, content, allowRagRoute);
        } catch (Exception ex) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("OpenClaw托管调用失败：响应解析失败").build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
    }

    private Optional<RouteDecisionResult> parseRouteDecision(String content) {
        String normalized = safeText(content);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        String json = extractRouteJson(normalized);
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                return Optional.empty();
            }
            String route = safeText(node.path("route").asText(""));
            if (route.isBlank()) {
                return Optional.empty();
            }
            String skillName = safeText(node.path("skill_name").asText(""));
            String answer = safeText(node.path("answer").asText(""));
            return Optional.of(new RouteDecisionResult(route.toUpperCase(), skillName, answer));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String extractRouteJson(String content) {
        String json = content;
        Matcher matcher = ROUTE_JSON_PATTERN.matcher(content);
        String matched = "";
        while (matcher.find()) {
            matched = safeText(matcher.group());
        }
        if (!matched.isBlank()) {
            return matched;
        }
        if (json.startsWith("```")) {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return json.substring(start, end + 1);
            }
        }
        return json;
    }

    private Flux<ServerSentEvent<String>> applyRouteDecisionResult(RouteDecisionResult decision,
                                                                    String conversationId,
                                                                    Long userId,
                                                                    String query,
                                                                    String rawContent,
                                                                    boolean allowRagRoute) {
        if ("SKILL".equals(decision.route())) {
            if (isSkillDescriptionQuery(query)) {
                String answer = decision.answer().isBlank() ? buildSkillDescriptionFallback(query, userId, decision.skillName()) : decision.answer();
                return buildTokenFlux(answer);
            }
            return buildDraftBySkillName(conversationId, userId, query, decision.skillName());
        }
        if ("SKILL_DESC".equals(decision.route())) {
            String answer = decision.answer().isBlank() ? buildSkillDescriptionFallback(query, userId, decision.skillName()) : decision.answer();
            return buildTokenFlux(answer);
        }
        if ("RAG".equals(decision.route())) {
            if (!allowRagRoute) {
                String answer = decision.answer().isBlank() ? rawContent : decision.answer();
                return buildTokenFlux(answer);
            }
            return executeForcedRagRoute(conversationId, userId, query, rawContent);
        }
        if ("LLM".equals(decision.route())) {
            if (allowRagRoute && !isSkillDescriptionQuery(query) && !isExplicitSkillExecutionQuery(query)) {
                return executeForcedRagRoute(conversationId, userId, query, rawContent);
            }
            if (isExplicitSkillExecutionQuery(query)) {
                return buildDraftBySkillName(conversationId, userId, query, decision.skillName());
            }
            String answer = decision.answer().isBlank() ? rawContent : decision.answer();
            return buildTokenFlux(answer);
        }
        return applyLocalRouteFallback(conversationId, userId, query, rawContent, allowRagRoute);
    }

    private Flux<ServerSentEvent<String>> applyLocalRouteFallback(String conversationId,
                                                                   Long userId,
                                                                   String query,
                                                                   String rawContent,
                                                                   boolean allowRagRoute) {
        if (isSkillDescriptionQuery(query)) {
            return buildTokenFlux(buildSkillDescriptionFallback(query, userId, ""));
        }
        if (isExplicitSkillExecutionQuery(query)) {
            return buildDraftBySkillName(conversationId, userId, query, "");
        }
        if (allowRagRoute) {
            return executeForcedRagRoute(conversationId, userId, query, rawContent);
        }
        return buildTokenFlux(rawContent);
    }

    private Flux<ServerSentEvent<String>> executeForcedRagRoute(String conversationId,
                                                                 Long userId,
                                                                 String query,
                                                                 String rawContent) {
        String username = resolveUsername(userId);
        if (username.isBlank()) {
            return askOpenClawAfterRagMiss(conversationId, userId, query, "RAG未命中：用户不存在", rawContent);
        }
        AtomicBoolean emittedRagPayload = new AtomicBoolean(false);
        AtomicReference<String> ragReason = new AtomicReference<>("RAG未命中：未返回有效内容");
        Flux<ServerSentEvent<String>> ragStream = chatProcessor.process(username, query, true)
                .flatMap(chunk -> {
                    String normalized = safeText(chunk);
                    if (normalized.isBlank() || "[DONE]".equals(normalized)) {
                        return Flux.empty();
                    }
                    if (isEmptyRagStructuredPayload(normalized)) {
                        ragReason.set("RAG未命中：返回空消息载荷");
                        return Flux.empty();
                    }
                    String answer = extractAnswerFromRagPayload(normalized);
                    if (!answer.isBlank() && isRagMissAnswer(answer) && !emittedRagPayload.get()) {
                        ragReason.set("RAG未命中：" + answer);
                        return Flux.empty();
                    }
                    emittedRagPayload.set(true);
                    return Flux.just(ServerSentEvent.<String>builder().event("message").data(normalized).build());
                });
        return ragStream
                .switchIfEmpty(Flux.defer(() ->
                        askOpenClawAfterRagMiss(conversationId, userId, query, ragReason.get(), rawContent)))
                .concatWith(Flux.defer(() -> emittedRagPayload.get()
                        ? Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build())
                        : Flux.empty()))
                .onErrorResume(ex -> askOpenClawAfterRagMiss(
                        conversationId,
                        userId,
                        query,
                        "RAG执行异常：" + safeText(ex.getMessage()),
                        rawContent
                ));
    }

    private String extractAnswerFromRagPayload(String payload) {
        String normalized = safeText(payload);
        if (normalized.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(normalized);
            return safeText(node.path("answer").asText(""));
        } catch (Exception ex) {
            return "";
        }
    }

    private boolean isEmptyRagStructuredPayload(String payload) {
        String normalized = safeText(payload);
        if (normalized.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(normalized);
            if (root == null || !root.isObject()) {
                return false;
            }
            JsonNode dataNode = root.path("data");
            String answer = safeText(root.path("answer").asText(""));
            String content = safeText(root.path("content").asText(""));
            String message = safeText(root.path("message").asText(""));
            String text = safeText(root.path("text").asText(""));
            String dataAnswer = safeText(dataNode.path("answer").asText(""));
            String dataContent = safeText(dataNode.path("content").asText(""));
            String dataMessage = safeText(dataNode.path("message").asText(""));
            String dataText = safeText(dataNode.path("text").asText(""));
            boolean hasText = !answer.isBlank()
                    || !content.isBlank()
                    || !message.isBlank()
                    || !text.isBlank()
                    || !dataAnswer.isBlank()
                    || !dataContent.isBlank()
                    || !dataMessage.isBlank()
                    || !dataText.isBlank();
            if (hasText) {
                return false;
            }
            JsonNode refs = root.path("reference");
            if (refs.isArray() && refs.size() > 0) {
                return false;
            }
            JsonNode dataRefs = dataNode.path("reference");
            if (dataRefs.isArray() && dataRefs.size() > 0) {
                return false;
            }
            JsonNode chunks = refs.path("chunks");
            if (chunks.isArray() && chunks.size() > 0) {
                return false;
            }
            JsonNode dataChunks = dataRefs.path("chunks");
            return !dataChunks.isArray() || dataChunks.size() == 0;
        } catch (Exception ignore) {
            return false;
        }
    }

    private boolean isRagMissAnswer(String answer) {
        return answer.startsWith("Chat failed:")
                || answer.startsWith("Stream failed:")
                || answer.startsWith("No ready dataset")
                || answer.startsWith("You have no permission")
                || answer.startsWith("User not found")
                || answer.startsWith("RAGFlow datasets API response shape unexpected")
                || answer.startsWith("Your permitted knowledge base does not exist in RAGFlow")
                || answer.startsWith("Sorry! No relevant content was found in the knowledge base!")
                || answer.contains("GENERIC_ERROR")
                || answer.contains("An error occurred during streaming")
                || answer.contains("未找到相关内容");
    }

    private Flux<ServerSentEvent<String>> askOpenClawAfterRagMiss(String conversationId,
                                                                   Long userId,
                                                                   String query,
                                                                   String ragReason,
                                                                   String rawContent) {
        String baseUrl = safeText(properties.getManagedBaseUrl());
        String chatPath = safeText(properties.getManagedChatPath());
        if (chatPath.isBlank()) {
            chatPath = "/v1/chat/completions";
        }
        if (baseUrl.isBlank() || !isOpenAiCompatPath(chatPath)) {
            return buildTokenFlux(rawContent);
        }
        String token = safeText(properties.getManagedApiKey());
        if (token.isBlank()) {
            token = safeText(properties.getApiKey());
        }
        String systemPrompt = buildManagedSystemPrompt(userId)
                + "\n附加约束：RAG 已执行且未命中。你必须自主决定 route=LLM 或 route=SKILL 或 route=SKILL_DESC，禁止输出 route=RAG。";
        String userPrompt = "用户问题：" + safeText(query) + "\nRAG状态：" + safeText(ragReason);
        Map<String, Object> payload = buildOpenAiCompatPayloadByMessages(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        String finalToken = token;
        return client.post()
                .uri(chatPath)
                .headers(headers -> applyHeaders(headers, finalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException("OpenClaw托管请求失败: " + body))))
                .bodyToMono(String.class)
                .flatMapMany(body -> mapOpenAiNonStreamResponse(body, conversationId, userId, query, false))
                .onErrorResume(ex -> buildTokenFlux("RAG未命中，且OpenClaw二次决策失败：" + safeText(ex.getMessage())));
    }

    private String resolveUsername(Long userId) {
        if (userId == null) {
            return "";
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return "";
        }
        return safeText(user.getUsername());
    }

    private boolean isSkillDescriptionQuery(String query) {
        String text = safeText(query);
        if (text.isBlank()) {
            return false;
        }
        return text.contains("什么是")
                || text.contains("介绍")
                || text.contains("说明")
                || text.contains("用途")
                || text.contains("作用");
    }

    private boolean isExplicitSkillExecutionQuery(String query) {
        String text = safeText(query);
        if (text.isBlank()) {
            return false;
        }
        boolean hasSkillWord = text.contains("技能") || text.contains("工具");
        boolean hasExecuteWord = text.contains("执行") || text.contains("调用") || text.contains("运行") || text.contains("使用");
        return hasSkillWord && hasExecuteWord;
    }

    private Flux<ServerSentEvent<String>> buildDraftBySkillName(String conversationId,
                                                                 Long userId,
                                                                 String query,
                                                                 String skillName) {
        Optional<ToolSpec> selected = Optional.empty();
        if (!safeText(skillName).isBlank()) {
            selected = skillRegistryService.findAvailableToolByCode(userId, safeText(skillName));
        }
        if (selected.isEmpty()) {
            selected = skillRegistryService.matchTool(query, userId);
        }
        if (selected.isEmpty()) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("OpenClaw已判定应调用技能，但未找到可用技能").build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        try {
            ToolCallDraft draft = engineOrchestrator.createManualToolDraft(conversationId, userId, selected.get().getName(), query);
            String draftJson = objectMapper.writeValueAsString(draft);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("tool_draft").data(draftJson).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        } catch (Exception ex) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("OpenClaw工具草稿生成失败：" + ex.getMessage()).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
    }

    private String buildSkillDescriptionFallback(String query, Long userId, String skillName) {
        Optional<ToolSpec> selected = Optional.empty();
        if (!safeText(skillName).isBlank()) {
            selected = skillRegistryService.findAvailableToolByCode(userId, safeText(skillName));
        }
        if (selected.isEmpty()) {
            selected = skillRegistryService.matchTool(query, userId);
        }
        if (selected.isEmpty()) {
            return "当前没有匹配到可用的指标校核技能，请联系管理员确认技能是否已上线并授权。";
        }
        ToolSpec spec = selected.get();
        String keywords = spec.getTriggerKeywords() == null ? "" : String.join("、", spec.getTriggerKeywords());
        return "可用技能：" + safeText(spec.getName()) + "。功能：" + safeText(spec.getDescription())
                + (keywords.isBlank() ? "" : "。触发关键词：" + keywords);
    }

    private Flux<ServerSentEvent<String>> mapOpenAiToolCallsToDraft(List<OpenAiChatResponse.ToolCall> toolCalls,
                                                                     String conversationId,
                                                                     Long userId,
                                                                     String query) {
        OpenAiChatResponse.ToolCall first = toolCalls.get(0);
        if (first == null || first.getFunction() == null || !StringUtils.hasText(first.getFunction().getName())) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("OpenClaw返回了无效工具调用").build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        String toolName = safeText(first.getFunction().getName());
        Optional<ToolSpec> toolSpec = skillRegistryService.findAvailableToolByCode(userId, toolName);
        if (toolSpec.isEmpty()) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("OpenClaw请求调用未授权技能：" + toolName).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        try {
            ToolCallDraft draft = engineOrchestrator.createManualToolDraft(conversationId, userId, toolName, query);
            String draftJson = objectMapper.writeValueAsString(draft);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("tool_draft").data(draftJson).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        } catch (Exception ex) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("OpenClaw工具草稿生成失败：" + ex.getMessage()).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
    }

    private Map<String, Object> buildOpenAiCompatPayload(String query, Long userId) {
        return buildOpenAiCompatPayloadByMessages(buildOpenAiCompatMessages(query, userId));
    }

    private Map<String, Object> buildOpenAiCompatPayloadByMessages(List<Map<String, Object>> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", resolveManagedModel());
        payload.put("stream", false);
        payload.put("messages", messages);
        return payload;
    }

    private List<Map<String, Object>> buildOpenAiCompatMessages(String query, Long userId) {
        String systemPrompt = buildManagedSystemPrompt(userId);
        return List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", safeText(query))
        );
    }

    private String buildManagedSystemPrompt(Long userId) {
        List<ToolSpec> tools = skillRegistryService.getAvailableTools(userId);
        StringBuilder toolDesc = new StringBuilder();
        for (ToolSpec spec : tools) {
            if (spec == null || !StringUtils.hasText(spec.getName())) {
                continue;
            }
            toolDesc.append("- tool_code=").append(safeText(spec.getName()));
            toolDesc.append("; description=").append(safeText(spec.getDescription()));
            if (spec.getTriggerKeywords() != null && !spec.getTriggerKeywords().isEmpty()) {
                toolDesc.append("; trigger_keywords=").append(String.join(",", spec.getTriggerKeywords()));
            }
            toolDesc.append('\n');
        }
        return "你是 OpenClaw 托管大脑，必须独立做路由判断。\n"
                + "你只能输出 JSON，不要输出 markdown 或额外文本。\n"
                + "JSON 格式：{\"route\":\"RAG|LLM|SKILL|SKILL_DESC\",\"skill_name\":\"\",\"answer\":\"\"}\n"
                + "规则：\n"
                + "1) 除纯闲聊和明显通用常识外，默认 route=RAG；\n"
                + "2) 事实定义类、公式类、指标类问题必须 route=RAG；\n"
                + "3) 明确要求执行技能时 route=SKILL，并给 skill_name；\n"
                + "4) 询问工具说明/用途时 route=SKILL_DESC，并在 answer 给出技能描述；\n"
                + "5) 不确定时一律 route=RAG，不要直接 route=LLM。\n"
                + "可用技能列表：\n"
                + (toolDesc.isEmpty() ? "- 无可用技能\n" : toolDesc.toString());
    }

    private Flux<ServerSentEvent<String>> fallbackDirectChat(String query, String reason) {
        String llmBaseUrl = safeText(properties.getLlmBaseUrl());
        if (llmBaseUrl.isBlank()) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data(reason + "；且未配置 ai4kb.brain.openclaw.llm-base-url").build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", resolveDirectModel());
        payload.put("stream", false);
        payload.put("messages", List.of(Map.of("role", "user", "content", safeText(query))));
        WebClient directClient = webClientBuilder.baseUrl(llmBaseUrl).build();
        return directClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException(reason + "；兜底直连失败: " + body))))
                .bodyToMono(String.class)
                .flatMapMany(this::mapDirectOpenAiResponse)
                .onErrorResume(ex -> Flux.just(
                        ServerSentEvent.<String>builder().event("error").data(reason + "；兜底直连失败：" + ex.getMessage()).build(),
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                ));
    }

    private Flux<ServerSentEvent<String>> mapDirectOpenAiResponse(String body) {
        try {
            OpenAiChatResponse response = objectMapper.readValue(body, OpenAiChatResponse.class);
            if (response.getChoices() == null || response.getChoices().isEmpty() || response.getChoices().get(0) == null) {
                return Flux.just(
                        ServerSentEvent.<String>builder().event("error").data("兜底直连模型返回空响应").build(),
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                );
            }
            OpenAiChatResponse.Choice choice = response.getChoices().get(0);
            String content = choice.getMessage() == null ? "" : safeText(choice.getMessage().getContent());
            if (content.isBlank()) {
                return Flux.just(
                        ServerSentEvent.<String>builder().event("error").data("兜底直连模型返回空内容").build(),
                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                );
            }
            return buildTokenFlux(content);
        } catch (Exception ex) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("兜底直连模型响应解析失败").build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
    }

    private Flux<ServerSentEvent<String>> buildTokenFlux(String rawContent) {
        String normalized = normalizeThinkContent(rawContent);
        if (!normalized.contains("</think>")) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("token").data(normalized).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        int splitIndex = normalized.indexOf("</think>");
        String thinkPart = normalized.substring(0, splitIndex + "</think>".length());
        String answerPart = normalized.substring(splitIndex + "</think>".length()).stripLeading();
        if (answerPart.isBlank()) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("token").data(thinkPart).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        return Flux.just(
                ServerSentEvent.<String>builder().event("token").data(thinkPart).build(),
                ServerSentEvent.<String>builder().event("token").data(answerPart).build(),
                ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
        );
    }

    private String normalizeThinkContent(String rawContent) {
        String content = safeText(rawContent);
        if (content.contains("</think>") && !content.contains("<think>")) {
            return "<think>" + content;
        }
        return content;
    }

    private String resolveManagedModel() {
        String configured = safeText(properties.getModel());
        if (configured.startsWith("openclaw")) {
            return configured;
        }
        String agentId = safeText(properties.getAgentId());
        if (agentId.isBlank()) {
            return "openclaw";
        }
        return "openclaw/" + agentId;
    }

    private String resolveDirectModel() {
        String configured = safeText(properties.getModel());
        if (configured.isBlank() || configured.startsWith("openclaw")) {
            return "deepseek-r1-distill-qwen-14b";
        }
        return configured;
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers, String token) {
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token);
        }
        if (StringUtils.hasText(properties.getAgentId())) {
            headers.set("X-OpenClaw-Agent-Id", properties.getAgentId());
        }
        if (StringUtils.hasText(properties.getScopes())) {
            headers.set("X-OpenClaw-Scopes", properties.getScopes());
        }
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace("<|im_start|>", "")
                .replace("<|im_end|>", "")
                .replace("<|im_start", "")
                .replace("<|im_end", "")
                .trim();
    }

    private record RouteDecisionResult(String route, String skillName, String answer) {}
}
