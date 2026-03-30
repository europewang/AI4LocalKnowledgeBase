package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.entity.RouteSample;
import com.ai4kb.backend.engine.model.ConversationState;
import com.ai4kb.backend.engine.model.Message;
import com.ai4kb.backend.engine.model.ToolCallDraft;
import com.ai4kb.backend.engine.model.openai.OpenAiChatRequest;
import com.ai4kb.backend.engine.model.openai.OpenAiChatResponse;
import com.ai4kb.backend.rag.processor.ChatProcessor;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.ai4kb.backend.skill.service.SkillExecutionService;
import com.ai4kb.backend.skill.service.SkillRegistryService;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * Agent 主编排器，负责把会话状态、路由决策、RAG、技能执行和 SSE 事件串成一条可恢复的执行链路。
 * 核心目标是保证三件事：
 * 1) 每轮请求都能在统一状态机中推进（RUNNING/WAITING_APPROVAL/RESUMED/FINISHED）；
 * 2) 路由决策可回退（本地规则 + Planner + 兜底）；
 * 3) 对前端输出稳定的流式事件协议。
 */
public class EngineOrchestrator {
    private static final String PLAN_STEP_BOUNDARY_EVENT = "plan_step_boundary";

    /**
     * LLM 网关客户端：统一向模型侧发起 chat/tool_call 请求。
     */
    private final LlmClient llmClient;
    /**
     * 会话状态持久化服务：负责读写 Redis 中的会话态与工具草稿。
     */
    private final ConversationService conversationService;
    /**
     * JSON 序列化/反序列化工具：用于 SSE 载荷、工具结果、计划快照转换。
     */
    private final ObjectMapper objectMapper;
    /**
     * 技能注册中心：提供“可用工具列表”和“按语义匹配工具”的能力。
     */
    private final SkillRegistryService skillRegistryService;
    /**
     * 技能执行服务：执行具体工具并返回结构化结果。
     */
    private final SkillExecutionService skillExecutionService;
    /**
     * RAG 能力入口：负责知识检索与参考片段回传。
     */
    private final ChatProcessor chatProcessor;
    /**
     * 用户信息访问层：用于路由与权限关联场景中的用户数据读取。
     */
    private final UserMapper userMapper;
    /**
     * 路由样本服务（可选注入）：用于落库存储路由决策样本，便于后续训练与审计。
     */
    @Autowired(required = false)
    private RouteSampleService routeSampleService;

    /**
     * 路由为 TOOL 的最低置信度阈值，低于该值将进入澄清或回退链路。
     */
    @Value("${ai4kb.router.tool-confidence-threshold:0.55}")
    private double toolConfidenceThreshold;
    /**
     * 低置信度澄清阈值，低于该值直接发 clarify 事件，不执行工具或检索。
     */
    @Value("${ai4kb.router.clarify-confidence-threshold:0.45}")
    private double clarifyConfidenceThreshold;
    /**
     * RAG/CHAT 双通道竞速阈值，在模糊区间触发并行裁决。
     */
    @Value("${ai4kb.router.race-confidence-threshold:0.75}")
    private double raceConfidenceThreshold;
    /**
     * 本地 Router 总开关：开启后走“本地先筛 + Planner 复核”的混合模式。
     */
    @Value("${ai4kb.router.local-router-enabled:false}")
    private boolean localRouterEnabled;
    /**
     * 本地 Router 直出阈值：本地置信度高于该值时可跳过 Planner 复核。
     */
    @Value("${ai4kb.router.local-router-auto-threshold:0.90}")
    private double localRouterAutoThreshold;

    /**
     * 兼容入口：按“当前最新用户问题”执行一轮标准路由。
     * 该入口主要用于未启用分析计划编辑场景的调用，内部最终仍复用 executeByRouting。
     */
    public Flux<ServerSentEvent<String>> process(String conversationId, Long userId, String query) {
        ConversationState state = conversationService.getState(conversationId);
        if (state == null) {
            state = ConversationState.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .memoryWindow(new ArrayList<>())
                    .status("RUNNING")
                    .build();
        }

        // 写入当前用户输入到短期记忆窗口
        state.getMemoryWindow().add(Message.builder()
                .role("user")
                .content(query)
                .build());

        state.setStatus("RUNNING");
        return executeByRouting(state);
    }

    /**
     * 高级入口：支持“分析计划生成 + 人工调整 + 重跑模式 + 仅重规划”。
     * 会先构建 PlanSnapshot 并发出 analysis_plan，再根据 replanOnly 决定是否继续执行主链路。
     */
    public Flux<ServerSentEvent<String>> processAdvanced(String conversationId,
                                                         Long userId,
                                                         String query,
                                                         String adjustmentInstruction,
                                                         List<Map<String, Object>> editedSteps,
                                                         String rerunMode,
                                                         Integer restartFromStep,
                                                         boolean replanOnly) {
        ConversationState loadedState = conversationService.getState(conversationId);
        if (loadedState == null) {
            loadedState = ConversationState.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .memoryWindow(new ArrayList<>())
                    .taskMemory(new ArrayList<>())
                    .conversationMemory(new ArrayList<>())
                    .status("RUNNING")
                    .build();
        }
        if (loadedState.getMemoryWindow() == null) {
            loadedState.setMemoryWindow(new ArrayList<>());
        }
        if (loadedState.getTaskMemory() == null) {
            loadedState.setTaskMemory(new ArrayList<>());
        }
        if (loadedState.getConversationMemory() == null) {
            loadedState.setConversationMemory(new ArrayList<>());
        }

        String normalizedQuery = query == null ? "" : query.trim();
        boolean hasNewUserQuery = !normalizedQuery.isBlank();
        if (hasNewUserQuery) {
            loadedState.getMemoryWindow().add(Message.builder()
                    .role("user")
                    .content(normalizedQuery)
                    .build());
        } else {
            normalizedQuery = loadedState.getMemoryWindow().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .reduce((first, second) -> second)
                    .map(Message::getContent)
                    .orElse("");
        }
        if (normalizedQuery.isBlank()) {
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("缺少可执行的问题内容").build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }

        String mode = normalizeRerunMode(rerunMode);
        if ("FULL_RERUN".equals(mode)) {
            trimToLatestUserTurn(loadedState);
        }
        loadedState.setStatus("RUNNING");

        final ConversationState state = loadedState;
        final String effectiveQuery = normalizedQuery;
        final String effectiveMode = mode;
        return buildExecutionPlan(state, effectiveQuery, adjustmentInstruction, editedSteps, effectiveMode, restartFromStep)
                .flatMapMany(plan -> {
                    state.setActivePlan(plan);
                    state.getTaskMemory().add(plan);
                    state.getConversationMemory().add(Map.of(
                            "timestamp", LocalDateTime.now().toString(),
                            "plan_id", plan.getPlanId(),
                            "query", plan.getQuery(),
                            "rerun_mode", plan.getRerunMode(),
                            "restart_from_step", plan.getRestartFromStep() == null ? 1 : plan.getRestartFromStep()
                    ));
                    conversationService.saveState(state);
                    Flux<ServerSentEvent<String>> prelude = Flux.just(
                            ServerSentEvent.<String>builder().event("analysis_plan").data(toJson(plan)).build()
                    );
                    if (replanOnly) {
                        return Flux.concat(prelude, Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build()));
                    }
                    return Flux.concat(
                            prelude,
                            decorateExecutionWithAnalysisEvents(executeByPlan(state, plan), state, plan, effectiveMode)
                    );
                });
    }

    /**
     * 工具审批入口：接收前端人工审核后的参数，执行技能并把结果注入会话，再恢复主链路继续生成回答。
     */
    public Flux<ServerSentEvent<String>> processToolApproval(String conversationId, String toolCallId, String reviewedArgs) {
        ConversationState state = conversationService.getState(conversationId);
        if (state == null) {
            return Flux.just(ServerSentEvent.<String>builder().event("error").data("Conversation not found").build());
        }
        
        ToolCallDraft draft = conversationService.getDraft(toolCallId);
        if (draft == null) {
            return Flux.just(ServerSentEvent.<String>builder().event("error").data("Tool draft not found").build());
        }

        String finalArgs = (reviewedArgs == null || reviewedArgs.isBlank()) ? draft.getDraftArgs() : reviewedArgs;
        draft.setReviewedArgs(finalArgs);
        draft.setApprovalStatus("APPROVED");
        try {
            Map<String, Object> executionResult = skillExecutionService.execute(
                    conversationId,
                    toolCallId,
                    draft.getToolName(),
                    state.getUserId(),
                    finalArgs
            );
            String resultJson = objectMapper.writeValueAsString(executionResult);
            draft.setExecutionResult(resultJson);
            conversationService.saveDraft(draft);

            state.setStatus("RESUMED");
            state.getMemoryWindow().add(Message.builder()
                    .role("tool")
                    .name(draft.getToolName())
                    .toolCallId(toolCallId)
                    .content(resultJson)
                    .build());
            return Flux.concat(
                    Flux.just(ServerSentEvent.<String>builder().event("tool_result").data(resultJson).build()),
                    executeByRouting(state)
            );
        } catch (Exception e) {
            log.error("Tool execution failed. toolCallId={}", toolCallId, e);
            draft.setExecutionResult("{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            conversationService.saveDraft(draft);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("Tool execution failed: " + e.getMessage()).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
    }

    /**
     * 主路由执行器：
     * - RESUMED 态直接走 LLM-only（避免审批恢复后再次触发工具识别）；
     * - RUNNING 态走“本地路由 + Planner”混合决策，再按决策进入 TOOL/RAG/CHAT 分支。
     */
    private Flux<ServerSentEvent<String>> executeByRouting(ConversationState state) {
        String latestUserText = state.getMemoryWindow().stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((first, second) -> second)
                .map(Message::getContent)
                .orElse("");
        boolean allowToolDetection = !"RESUMED".equals(state.getStatus());
        if (!allowToolDetection) {
            return executeLlmOnly(state, true, null);
        }
        return planRouteWithHybridRouter(latestUserText, state.getUserId())
                .flatMapMany(decision -> {
                    logRouteSample(state.getConversationId(), state.getUserId(), latestUserText, decision);
                    return applyRouteDecision(state, latestUserText, decision.decision());
                })
                .onErrorResume(ex -> fallbackRoute(state, latestUserText));
    }

    private Flux<ServerSentEvent<String>> executeByPlan(ConversationState state, ConversationState.PlanSnapshot plan) {
        List<ConversationState.PlanStep> steps = extractExecutablePlanSteps(plan);
        if (steps.isEmpty()) {
            return executeByRouting(state);
        }
        return executePlanStepRecursively(state, steps, 0);
    }

    private List<ConversationState.PlanStep> extractExecutablePlanSteps(ConversationState.PlanSnapshot plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return List.of();
        }
        int restartFrom = plan.getRestartFromStep() == null ? 1 : Math.max(plan.getRestartFromStep(), 1);
        return plan.getSteps().stream()
                .filter(step -> step != null && (step.getStepNo() == null || step.getStepNo() >= restartFrom))
                .toList();
    }

    private Flux<ServerSentEvent<String>> executePlanStepRecursively(ConversationState state,
                                                                     List<ConversationState.PlanStep> steps,
                                                                     int index) {
        if (steps == null || index >= steps.size()) {
            return Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
        }
        ConversationState.PlanStep step = steps.get(index);
        String stepQuery = resolveStepQuery(state, step);
        String stepRoute = normalizePlanRoute(step == null ? null : step.getRoute());
        AtomicBoolean shouldStop = new AtomicBoolean(false);
        AtomicBoolean doneEmitted = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> stepStream = executeSinglePlanStep(state, stepRoute, step, stepQuery)
                .concatMap(event -> {
                    String name = event.event() == null ? "" : event.event();
                    if ("clarify".equals(name) || "error".equals(name)) {
                        shouldStop.set(true);
                    }
                    if ("done".equals(name)) {
                        doneEmitted.set(true);
                        if (shouldStop.get() || index == steps.size() - 1) {
                            return Mono.just(event);
                        }
                        return Mono.empty();
                    }
                    return Mono.just(event);
                });
        return stepStream.concatWith(Flux.defer(() -> {
            if (shouldStop.get()) {
                if (doneEmitted.get()) {
                    return Flux.empty();
                }
                return Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
            }
            if (index == steps.size() - 1) {
                if (doneEmitted.get()) {
                    return Flux.empty();
                }
                return Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
            }
            ConversationState.PlanStep current = steps.get(index);
            int boundaryStepNo = current != null && current.getStepNo() != null ? current.getStepNo() : index + 1;
            ServerSentEvent<String> boundary = ServerSentEvent.<String>builder()
                    .event(PLAN_STEP_BOUNDARY_EVENT)
                    .data(String.valueOf(boundaryStepNo))
                    .build();
            return Flux.concat(
                    Flux.just(boundary),
                    executePlanStepRecursively(state, steps, index + 1)
            );
        }));
    }

    private Flux<ServerSentEvent<String>> executeSinglePlanStep(ConversationState state,
                                                                String stepRoute,
                                                                ConversationState.PlanStep step,
                                                                String stepQuery) {
        if ("SKILL".equals(stepRoute)) {
            return executeSkillStepAutonomous(state, step, stepQuery);
        }
        if ("RAG".equals(stepRoute)) {
            return executeRagWithFallback(state, stepQuery);
        }
        if ("LLM".equals(stepRoute)) {
            return executeLlmOnlyForPlanStep(state, stepQuery);
        }
        return executeByRoutingForQuery(state, stepQuery);
    }

    private Flux<ServerSentEvent<String>> executeSkillStepAutonomous(ConversationState state,
                                                                     ConversationState.PlanStep step,
                                                                     String stepQuery) {
        List<ToolSpec> available = skillRegistryService.getAvailableTools(state.getUserId());
        String preferredTool = step == null ? "" : safeText(step.getToolName());
        boolean explicitExecution = isExplicitToolExecutionIntent(stepQuery);
        boolean skillUsageQuery = isSkillUsageIntent(stepQuery);
        Optional<ToolSpec> preferred = findToolByName(available, preferredTool);
        if (preferred.isPresent()) {
            if (!explicitExecution && skillUsageQuery) {
                return buildSkillUsageMessageEvent(state, preferred.get(), stepQuery);
            }
            return buildToolDraftEvent(state, preferred.get(), stepQuery);
        }
        Optional<ToolSpec> matched = skillRegistryService.matchTool(stepQuery, state.getUserId());
        if (matched.isPresent()) {
            if (!explicitExecution && skillUsageQuery) {
                return buildSkillUsageMessageEvent(state, matched.get(), stepQuery);
            }
            return buildToolDraftEvent(state, matched.get(), stepQuery);
        }
        return maybeSelectToolByLlm(stepQuery, state.getUserId())
                .flatMapMany(selected -> selected
                        .map(spec -> {
                            if (!explicitExecution && skillUsageQuery) {
                                return buildSkillUsageMessageEvent(state, spec, stepQuery);
                            }
                            return buildToolDraftEvent(state, spec, stepQuery);
                        })
                        .orElseGet(() -> matchToolByIntentHeuristics(stepQuery, state.getUserId())
                                .map(spec -> {
                                    if (!explicitExecution && skillUsageQuery) {
                                        return buildSkillUsageMessageEvent(state, spec, stepQuery);
                                    }
                                    return buildToolDraftEvent(state, spec, stepQuery);
                                })
                                .orElseGet(() -> executeRagWithFallback(state, stepQuery))));
    }

    private Flux<ServerSentEvent<String>> buildSkillUsageMessageEvent(ConversationState state, ToolSpec skill, String stepQuery) {
        String answer = buildSkillDescriptionFallback(skill, stepQuery);
        List<String> logicSteps = new ArrayList<>();
        appendLogicStep(logicSteps, "检索技能库并命中相关技能，返回技能使用说明");
        String payload = buildStructuredPayload(
                answer,
                null,
                "SKILL",
                "技能库检索",
                buildLogicFlow(logicSteps)
        );
        state.getMemoryWindow().add(Message.builder()
                .role("assistant")
                .content(answer)
                .build());
        return Flux.just(ServerSentEvent.<String>builder().event("message").data(payload).build());
    }

    private Flux<ServerSentEvent<String>> executeLlmOnlyForPlanStep(ConversationState state, String stepQuery) {
        String fallbackAnswer = buildGeneralDialogueFallback(stepQuery);
        return executeLlmOnly(state, false, fallbackAnswer);
    }

    private Flux<ServerSentEvent<String>> executeByRoutingForQuery(ConversationState state, String query) {
        String text = safeText(query);
        if (text.isBlank()) {
            return executeByRouting(state);
        }
        state.getMemoryWindow().add(Message.builder()
                .role("user")
                .content(text)
                .build());
        state.setStatus("RUNNING");
        return executeByRouting(state);
    }

    private String resolveStepQuery(ConversationState state, ConversationState.PlanStep step) {
        String fromStep = step == null ? "" : safeText(step.getQuery());
        if (!fromStep.isBlank()) {
            return fromStep;
        }
        return state.getMemoryWindow().stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((first, second) -> second)
                .map(Message::getContent)
                .orElse("");
    }

    private String normalizePlanRoute(String route) {
        String value = safeText(route).toUpperCase(Locale.ROOT);
        if ("SKILL".equals(value) || "TOOL".equals(value)) {
            return "SKILL";
        }
        if ("RAG".equals(value)) {
            return "RAG";
        }
        if ("LLM".equals(value) || "CHAT".equals(value) || "SUMMARY".equals(value)) {
            return "LLM";
        }
        return "AUTO";
    }

    private Mono<DecisionEnvelope> planRouteWithHybridRouter(String query, Long userId) {
        if (!localRouterEnabled) {
            return planRouteByLlm(query, userId)
                    .map(decision -> DecisionEnvelope.of(decision, "PLANNER_ONLY", null, decision));
        }
        RouteDecision localDecision = planRouteByLocalRouter(query, userId).orElse(null);
        if (localDecision == null) {
            return planRouteByLlm(query, userId)
                    .map(decision -> DecisionEnvelope.of(decision, "PLANNER_FALLBACK", null, decision));
        }
        if (!shouldReviewByPlanner(localDecision)) {
            return Mono.just(DecisionEnvelope.of(localDecision, "LOCAL_DIRECT", localDecision, null));
        }
        return planRouteByLlm(query, userId)
                .map(plannerDecision -> mergeLocalAndPlanner(localDecision, plannerDecision))
                .defaultIfEmpty(DecisionEnvelope.of(localDecision, "LOCAL_FALLBACK", localDecision, RouteDecision.unknown()))
                .onErrorResume(ex -> Mono.just(DecisionEnvelope.of(localDecision, "LOCAL_FALLBACK", localDecision, RouteDecision.unknown())));
    }

    private DecisionEnvelope mergeLocalAndPlanner(RouteDecision localDecision, RouteDecision plannerDecision) {
        RouteDecision planner = plannerDecision == null ? RouteDecision.unknown() : plannerDecision;
        String localRoute = normalizeRoute(localDecision.route());
        String plannerRoute = normalizeRoute(planner.route());
        if ("UNKNOWN".equals(plannerRoute)) {
            return DecisionEnvelope.of(localDecision, "LOCAL_FALLBACK", localDecision, planner);
        }
        if (plannerRoute.equals(localRoute)) {
            return DecisionEnvelope.of(planner, "HYBRID_PLANNER_CONFIRMED", localDecision, planner);
        }
        if (planner.confidence() >= localDecision.confidence() + 0.10d) {
            return DecisionEnvelope.of(planner, "HYBRID_PLANNER_OVERRIDE", localDecision, planner);
        }
        return DecisionEnvelope.of(localDecision, "HYBRID_LOCAL_KEEP", localDecision, planner);
    }

    private Optional<RouteDecision> planRouteByLocalRouter(String query, Long userId) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String normalizedQuery = query.trim();
        if (isGeneralDialogueQuery(normalizedQuery)) {
            return Optional.of(new RouteDecision("CHAT", 0.92d, null, "local_general_dialogue"));
        }
        Optional<ToolSpec> matchedTool = skillRegistryService.matchTool(normalizedQuery, userId);
        if (matchedTool.isPresent() && isExplicitToolExecutionIntent(normalizedQuery)) {
            return Optional.of(new RouteDecision("TOOL", 0.96d, matchedTool.get().getName(), "local_explicit_tool_intent"));
        }
        if (looksLikeKnowledgeQuestion(normalizedQuery) && !isExplicitToolExecutionIntent(normalizedQuery)) {
            return Optional.of(new RouteDecision("RAG", 0.82d, null, "local_knowledge_question"));
        }
        if (matchedTool.isPresent()) {
            return Optional.of(new RouteDecision("TOOL", 0.90d, matchedTool.get().getName(), "local_keyword_match"));
        }
        return Optional.empty();
    }

    private boolean shouldReviewByPlanner(RouteDecision localDecision) {
        String route = normalizeRoute(localDecision.route());
        if ("TOOL".equals(route)) {
            return true;
        }
        return localDecision.confidence() < effectiveLocalRouterAutoThreshold();
    }

    private double effectiveLocalRouterAutoThreshold() {
        if (localRouterAutoThreshold > 0d && localRouterAutoThreshold <= 1d) {
            return localRouterAutoThreshold;
        }
        return 0.90d;
    }

    private boolean looksLikeKnowledgeQuestion(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.endsWith("?") || normalized.endsWith("？")) {
            return true;
        }
        Pattern keywordPattern = Pattern.compile("(什么是|定义|解释|说明|原理|依据|区别|规范|要求|流程|如何|为什么|有哪些|怎么做|含义)");
        return keywordPattern.matcher(normalized).find();
    }

    private boolean isExplicitToolExecutionIntent(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return false;
        }
        Pattern actionPattern = Pattern.compile("(调用|执行|运行|发起|触发|帮我做|帮我跑|帮我执行)");
        Pattern toolPattern = Pattern.compile("(工具|技能|校核|核验|检查|提取|导出)");
        return actionPattern.matcher(normalized).find() && toolPattern.matcher(normalized).find();
    }

    private boolean isSkillUsageIntent(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return false;
        }
        Pattern usagePattern = Pattern.compile("(怎么用|如何用|使用方法|参数|触发词|示例)");
        Pattern toolPattern = Pattern.compile("(工具|技能|校核|核验|检查|提取|导出)");
        return usagePattern.matcher(normalized).find() && toolPattern.matcher(normalized).find();
    }

    private void logRouteSample(String conversationId, Long userId, String query, DecisionEnvelope envelope) {
        RouteDecision chosen = envelope.decision() == null ? RouteDecision.unknown() : envelope.decision();
        RouteDecision local = envelope.localCandidate() == null ? RouteDecision.unknown() : envelope.localCandidate();
        RouteDecision planner = envelope.plannerCandidate() == null ? RouteDecision.unknown() : envelope.plannerCandidate();
        if (routeSampleService != null) {
            RouteSample sample = new RouteSample();
            sample.setConversationId(conversationId);
            sample.setUserId(userId);
            sample.setSource(envelope.source());
            sample.setChosenRoute(normalizeRoute(chosen.route()));
            sample.setChosenConfidence(chosen.confidence());
            sample.setChosenTool(chosen.toolName());
            sample.setLocalRoute(normalizeRoute(local.route()));
            sample.setLocalConfidence(local.confidence());
            sample.setPlannerRoute(normalizeRoute(planner.route()));
            sample.setPlannerConfidence(planner.confidence());
            sample.setQueryText(query);
            sample.setCreatedAt(LocalDateTime.now());
            routeSampleService.saveSample(sample);
        }
        log.info("route_sample: conversation_id={} user_id={} source={} chosen_route={} chosen_confidence={} chosen_tool={} local_route={} local_confidence={} planner_route={} planner_confidence={} query={}",
                conversationId,
                userId,
                envelope.source(),
                normalizeRoute(chosen.route()),
                chosen.confidence(),
                chosen.toolName(),
                normalizeRoute(local.route()),
                local.confidence(),
                normalizeRoute(planner.route()),
                planner.confidence(),
                query);
    }

    private Flux<ServerSentEvent<String>> applyRouteDecision(ConversationState state, String query, RouteDecision decision) {
        if (decision == null) {
            log.info("route_decision fallback: reason=planner_null query={}", query);
            return fallbackRoute(state, query);
        }
        String normalizedRoute = normalizeRoute(decision.route());
        double effectiveThreshold = effectiveToolConfidenceThreshold();
        double effectiveClarifyThreshold = effectiveClarifyConfidenceThreshold();
        double effectiveRaceThreshold = effectiveRaceConfidenceThreshold();
        log.info("route_decision planner: route={}, confidence={}, tool={}, reason={}, threshold={}, query={}",
                normalizedRoute, decision.confidence(), decision.toolName(), decision.reason(), effectiveThreshold, query);
        if (isClarifyNeeded(normalizedRoute, decision.confidence(), effectiveClarifyThreshold)) {
            log.info("route_decision clarify: route={} confidence={} clarify_threshold={} query={}",
                    normalizedRoute, decision.confidence(), effectiveClarifyThreshold, query);
            return buildClarifyEvent(state, query, decision, normalizedRoute);
        }
        if (isRaceNeeded(normalizedRoute, decision.confidence(), effectiveClarifyThreshold, effectiveRaceThreshold)) {
            log.info("route_decision race: route={} confidence={} clarify_threshold={} race_threshold={} query={}",
                    normalizedRoute, decision.confidence(), effectiveClarifyThreshold, effectiveRaceThreshold, query);
            return executeDualPathRace(state, query, normalizedRoute, decision.confidence());
        }
        if ("TOOL".equals(normalizedRoute) && isIndicatorKnowledgeQuestion(query) && !isExplicitToolExecutionIntent(query)) {
            return executeRagWithFallback(state, query);
        }
        if ("TOOL".equals(normalizedRoute) && decision.confidence() >= effectiveThreshold && decision.toolName() != null) {
            return skillRegistryService.getAvailableTools(state.getUserId()).stream()
                    .filter(spec -> decision.toolName().equals(spec.getName()))
                    .findFirst()
                    .map(spec -> buildToolDraftEvent(state, spec, query))
                    .orElseGet(() -> {
                        log.info("route_decision fallback: reason=tool_not_permitted tool={} query={}", decision.toolName(), query);
                        return fallbackRoute(state, query);
                    });
        }
        if ("TOOL".equals(normalizedRoute)) {
            log.info("route_decision fallback: reason=tool_low_confidence confidence={} threshold={} query={}",
                    decision.confidence(), effectiveThreshold, query);
            return fallbackRoute(state, query);
        }
        if ("CHAT".equals(normalizedRoute)) {
            return executeLlmOnly(state, false, buildGeneralDialogueFallback(query));
        }
        if ("RAG".equals(normalizedRoute)) {
            return executeRagWithFallback(state, query);
        }
        log.info("route_decision fallback: reason=invalid_route route={} query={}", decision.route(), query);
        return fallbackRoute(state, query);
    }

    private Flux<ServerSentEvent<String>> fallbackRoute(ConversationState state, String query) {
        Optional<ToolSpec> matched = skillRegistryService.matchTool(query, state.getUserId());
        if (matched.isPresent() && isExplicitToolExecutionIntent(query)) {
            log.info("route_decision fallback_hit: path=explicit_tool tool={} query={}", matched.get().getName(), query);
            return buildToolDraftEvent(state, matched.get(), query);
        }
        if (isIndicatorKnowledgeQuestion(query) && !isExplicitToolExecutionIntent(query)) {
            return executeRagWithFallback(state, query);
        }
        if (matched.isPresent()) {
            log.info("route_decision fallback_hit: path=keyword_tool tool={} query={}", matched.get().getName(), query);
            return buildToolDraftEvent(state, matched.get(), query);
        }
        if (isGeneralDialogueQuery(query)) {
            log.info("route_decision fallback_hit: path=general_chat query={}", query);
            return executeLlmOnly(state, false, buildGeneralDialogueFallback(query));
        }
        return maybeSelectToolByLlm(query, state.getUserId())
                .flatMapMany(selected -> selected
                        .map(spec -> {
                            log.info("route_decision fallback_hit: path=llm_tool_selector tool={} query={}", spec.getName(), query);
                            return buildToolDraftEvent(state, spec, query);
                        })
                        .orElseGet(() -> matchToolByIntentHeuristics(query, state.getUserId())
                                .map(spec -> {
                                    log.info("route_decision fallback_hit: path=heuristic_tool tool={} query={}", spec.getName(), query);
                                    return buildToolDraftEvent(state, spec, query);
                                })
                                .orElseGet(() -> {
                                    log.info("route_decision fallback_hit: path=rag query={}", query);
                                    return executeRagWithFallback(state, query);
                                })));
    }

    private Mono<RouteDecision> planRouteByLlm(String query, Long userId) {
        if (query == null || query.isBlank()) {
            return Mono.just(RouteDecision.unknown());
        }
        List<ToolSpec> available = skillRegistryService.getAvailableTools(userId);
        String availableToolNames = available.stream().map(ToolSpec::getName).reduce((a, b) -> a + "," + b).orElse("NONE");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("route", Map.of("type", "string", "enum", List.of("TOOL", "RAG", "CHAT")));
        properties.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1));
        properties.put("tool_name", Map.of("type", "string"));
        properties.put("reason", Map.of("type", "string"));
        schema.put("properties", properties);
        schema.put("required", List.of("route", "confidence"));
        List<Message> messages = List.of(
                Message.builder()
                        .role("system")
                        .content("你是路由规划器。请严格调用 route_decision 工具并返回决策：TOOL 仅用于明确需要执行工具的请求；RAG 用于知识库事实问答；CHAT 用于寒暄/通用对话。tool_name 必须来自可用工具清单。")
                        .build(),
                Message.builder()
                        .role("user")
                        .content("可用工具: " + availableToolNames + "\n用户问题: " + query)
                        .build()
        );
        OpenAiChatRequest.Tool plannerTool = OpenAiChatRequest.Tool.builder()
                .type("function")
                .function(OpenAiChatRequest.Function.builder()
                        .name("route_decision")
                        .description("输出路由决策")
                        .parameters(schema)
                        .build())
                .build();
        return llmClient.chatStream(OpenAiChatRequest.builder()
                        .messages(messages)
                        .tools(List.of(plannerTool))
                        .build())
                .next()
                .map(this::extractRouteDecision)
                .defaultIfEmpty(RouteDecision.unknown())
                .onErrorResume(ex -> Mono.just(RouteDecision.unknown()));
    }

    private Flux<ServerSentEvent<String>> executeRagWithFallback(ConversationState state, String query) {
        String username = resolveUsername(state.getUserId());
        String skillKnowledgeHint = buildSkillKnowledgeHint(query, state.getUserId());
        boolean indicatorKnowledgeQuery = isIndicatorKnowledgeQuestion(query);
        List<String> logicSteps = new ArrayList<>();
        if (indicatorKnowledgeQuery) {
            appendLogicStep(logicSteps, "查询RAG知识库");
        }
        AtomicBoolean hasNonFailureAnswer = new AtomicBoolean(false);
        AtomicBoolean skillHintInjected = new AtomicBoolean(skillKnowledgeHint.isBlank());
        AtomicReference<JsonNode> retainedReference = new AtomicReference<>(null);
        AtomicBoolean referencePayloadSent = new AtomicBoolean(false);
        AtomicBoolean ragHitMarked = new AtomicBoolean(false);
        StringBuilder ragReply = new StringBuilder();
        Flux<ServerSentEvent<String>> ragStream = chatProcessor.process(username, query, true)
                .timeout(Duration.ofSeconds(15))
                .map(this::normalizeSsePayload)
                .filter(payload -> !payload.isBlank())
                .filter(payload -> !"[DONE]".equals(payload))
                .flatMap(payload -> {
                    try {
                        JsonNode node = objectMapper.readTree(payload);
                        String answer = node.path("answer").asText("");
                        if (!answer.isBlank() && isRagFailure(answer)) {
                            return Mono.empty();
                        }
                        JsonNode referenceNode = node.path("reference");
                        if (hasRenderableReference(referenceNode)) {
                            retainedReference.set(referenceNode.deepCopy());
                            if (indicatorKnowledgeQuery && ragHitMarked.compareAndSet(false, true)) {
                                appendLogicStep(logicSteps, "RAG命中相关内容，基于知识库回答");
                            }
                        }
                        if (answer.isBlank()) {
                            if (hasRenderableReference(referenceNode)
                                    && hasNonFailureAnswer.get()
                                    && referencePayloadSent.compareAndSet(false, true)) {
                                String payloadWithRefs = buildStructuredPayload(
                                        "",
                                        referenceNode,
                                        "RAG",
                                        "RAG检索",
                                        indicatorKnowledgeQuery ? buildLogicFlow(logicSteps) : null
                                );
                                return Mono.just(ServerSentEvent.<String>builder()
                                        .event("message")
                                        .data(payloadWithRefs)
                                        .build());
                            }
                            return Mono.empty();
                        }
                        if (indicatorKnowledgeQuery && ragHitMarked.compareAndSet(false, true)) {
                            appendLogicStep(logicSteps, "RAG命中相关内容，基于知识库回答");
                        }
                        if (!skillHintInjected.get()) {
                            answer = mergeSkillHint(skillKnowledgeHint, answer);
                            skillHintInjected.set(true);
                        }
                        hasNonFailureAnswer.set(true);
                        ragReply.append(answer);
                        return Mono.just(ServerSentEvent.<String>builder()
                                .event("message")
                                .data(annotatePayload(
                                        overridePayloadAnswer(node, answer),
                                        "RAG",
                                        "RAG检索",
                                        indicatorKnowledgeQuery ? buildLogicFlow(logicSteps) : null
                                ))
                                .build());
                    } catch (Exception ignored) {
                        if (isRagFailure(payload)) {
                            return Mono.empty();
                        }
                        String answer = payload;
                        if (!skillHintInjected.get()) {
                            answer = mergeSkillHint(skillKnowledgeHint, answer);
                            skillHintInjected.set(true);
                        }
                        String wrapped = wrapPlainRagPayload(answer);
                        if (wrapped == null) {
                            return Mono.empty();
                        }
                        hasNonFailureAnswer.set(true);
                        ragReply.append(answer);
                        if (indicatorKnowledgeQuery && ragHitMarked.compareAndSet(false, true)) {
                            appendLogicStep(logicSteps, "RAG命中相关内容，基于知识库回答");
                        }
                        return Mono.just(ServerSentEvent.<String>builder()
                                .event("message")
                                .data(annotatePayload(
                                        wrapped,
                                        "RAG",
                                        "RAG检索",
                                        indicatorKnowledgeQuery ? buildLogicFlow(logicSteps) : null
                                ))
                                .build());
                    }
                })
                .onErrorResume(ex -> Flux.empty());
        return ragStream
                .concatWith(Flux.defer(() -> {
                    if (!hasNonFailureAnswer.get()) {
                        JsonNode refs = retainedReference.get();
                        if (hasRenderableReference(refs)) {
                            if (indicatorKnowledgeQuery && ragHitMarked.compareAndSet(false, true)) {
                                appendLogicStep(logicSteps, "RAG命中相关内容，基于知识库回答");
                            }
                            return executeRagReferenceGroundedAnswer(
                                    state,
                                    query,
                                    refs,
                                    skillKnowledgeHint,
                                    indicatorKnowledgeQuery ? buildLogicFlow(logicSteps) : null
                            );
                        }
                        if (indicatorKnowledgeQuery) {
                            appendLogicStep(logicSteps, "RAG未命中相关内容");
                            Optional<ToolSpec> matchedSkill = skillRegistryService.matchTool(query, state.getUserId());
                            if (matchedSkill.isPresent()) {
                                appendLogicStep(logicSteps, "检索技能库并命中相关技能，返回技能描述");
                                String answer = buildSkillDescriptionFallback(matchedSkill.get(), query);
                                String payload = buildStructuredPayload(
                                        answer,
                                        null,
                                        "SKILL",
                                        "技能库检索",
                                        buildLogicFlow(logicSteps)
                                );
                                state.getMemoryWindow().add(Message.builder()
                                        .role("assistant")
                                        .content(answer)
                                        .build());
                                state.setStatus("FINISHED");
                                conversationService.saveState(state);
                                return Flux.just(
                                        ServerSentEvent.<String>builder().event("message").data(payload).build(),
                                        ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                                );
                            }
                            appendLogicStep(logicSteps, "技能库未命中，回退大模型自主回答");
                        }
                        return Flux.empty();
                    }
                    state.getMemoryWindow().add(Message.builder()
                            .role("assistant")
                            .content(ragReply.toString())
                            .build());
                    state.setStatus("FINISHED");
                    conversationService.saveState(state);
                    return Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
                }))
                .switchIfEmpty(executeGeneralKnowledgeAnswerAfterRagMiss(
                        state,
                        query,
                        skillKnowledgeHint,
                        indicatorKnowledgeQuery ? buildLogicFlow(logicSteps) : ""
                ));
    }

    private Flux<ServerSentEvent<String>> executeRagReferenceGroundedAnswer(ConversationState state, String query, JsonNode referenceNode, String skillKnowledgeHint, String logicFlow) {
        String referenceContext = buildReferenceContext(referenceNode);
        String fallbackAnswer = mergeSkillHint(skillKnowledgeHint, buildReferenceOnlyFallback(query));
        if (referenceContext.isBlank()) {
            String payload = annotatePayload(buildRagPayload(fallbackAnswer, referenceNode), "RAG", "RAG检索", logicFlow);
            state.getMemoryWindow().add(Message.builder()
                    .role("assistant")
                    .content(fallbackAnswer)
                    .build());
            state.setStatus("FINISHED");
            conversationService.saveState(state);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("message").data(payload).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        StringBuilder modelReply = new StringBuilder();
        List<Message> messages = List.of(
                Message.builder()
                        .role("system")
                        .content("你是中文助手。你将收到用户问题与知识库检索片段。请基于片段进行归纳回答，不要大段照抄原文，优先给出简洁结论并保留关键条件。")
                        .build(),
                Message.builder()
                        .role("user")
                        .content("用户问题：" + (query == null ? "" : query.trim()) + "\n\n检索片段：\n" + referenceContext)
                        .build()
        );
        return llmClient.chatStream(OpenAiChatRequest.builder().messages(messages).build())
                .mapNotNull(response -> {
                    if (response.getChoices() == null || response.getChoices().isEmpty()) {
                        return null;
                    }
                    OpenAiChatResponse.Choice choice = response.getChoices().get(0);
                    OpenAiChatResponse.Delta delta = choice.getDelta();
                    OpenAiChatResponse.Message message = choice.getMessage();
                    if (delta != null) {
                        return extractText(delta.getContent(), delta.getReasoningContent());
                    }
                    if (message != null) {
                        return extractText(message.getContent(), message.getReasoningContent());
                    }
                    return null;
                })
                .doOnNext(modelReply::append)
                .thenMany(Flux.defer(() -> {
                    String answer = modelReply.toString().trim();
                    if (answer.isBlank()) {
                        answer = fallbackAnswer;
                    } else {
                        answer = mergeSkillHint(skillKnowledgeHint, answer);
                    }
                    String payload = annotatePayload(buildRagPayload(answer, referenceNode), "RAG", "RAG检索", logicFlow);
                    state.getMemoryWindow().add(Message.builder()
                            .role("assistant")
                            .content(answer)
                            .build());
                    state.setStatus("FINISHED");
                    conversationService.saveState(state);
                    return Flux.just(
                            ServerSentEvent.<String>builder().event("message").data(payload).build(),
                            ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                    );
                }))
                .onErrorResume(ex -> {
                    String payload = annotatePayload(buildRagPayload(fallbackAnswer, referenceNode), "RAG", "RAG检索", logicFlow);
                    state.getMemoryWindow().add(Message.builder()
                            .role("assistant")
                            .content(fallbackAnswer)
                            .build());
                    state.setStatus("FINISHED");
                    conversationService.saveState(state);
                    return Flux.just(
                            ServerSentEvent.<String>builder().event("message").data(payload).build(),
                            ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
                    );
                });
    }

    private boolean hasRenderableReference(JsonNode referenceNode) {
        if (referenceNode == null || referenceNode.isMissingNode() || referenceNode.isNull()) {
            return false;
        }
        if (referenceNode.isArray()) {
            return referenceNode.size() > 0;
        }
        JsonNode chunks = referenceNode.path("chunks");
        return chunks.isArray() && chunks.size() > 0;
    }

    private String buildReferenceContext(JsonNode referenceNode) {
        JsonNode refs = normalizeReferenceArray(referenceNode);
        if (refs == null || !refs.isArray() || refs.size() == 0) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        int limit = Math.min(refs.size(), 4);
        for (int i = 0; i < limit; i++) {
            JsonNode ref = refs.get(i);
            if (ref == null || ref.isNull() || !ref.isObject()) {
                continue;
            }
            String name = ref.path("document_name").asText("");
            if (name.isBlank()) {
                name = ref.path("doc_name").asText("");
            }
            if (name.isBlank()) {
                name = "资料" + (i + 1);
            }
            String content = ref.path("content").asText("");
            if (content.isBlank()) {
                content = ref.path("chunk").asText("");
            }
            if (content.isBlank()) {
                content = ref.path("text").asText("");
            }
            String normalized = content.replaceAll("\\s+", " ").trim();
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.length() > 260) {
                normalized = normalized.substring(0, 260);
            }
            lines.add((i + 1) + ". " + name + "：" + normalized);
        }
        return String.join("\n", lines);
    }

    private JsonNode normalizeReferenceArray(JsonNode referenceNode) {
        if (referenceNode == null || referenceNode.isMissingNode() || referenceNode.isNull()) {
            return null;
        }
        if (referenceNode.isArray()) {
            return referenceNode;
        }
        JsonNode chunks = referenceNode.path("chunks");
        if (chunks.isArray()) {
            return chunks;
        }
        return null;
    }

    private String buildReferenceOnlyFallback(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return "已检索到相关资料，但暂时无法自动整理答案，请查看下方引用原文。";
        }
        return "已检索到与“" + q + "”相关的资料，但暂时无法自动整理答案，请查看下方引用原文。";
    }

    private String buildRagPayload(String answer, JsonNode referenceNode) {
        return buildStructuredPayload(answer, referenceNode, "RAG", "RAG检索", null);
    }

    private String buildStructuredPayload(String answer, JsonNode referenceNode, String source, String sourceLabel, String logicFlow) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("answer", answer == null ? "" : answer);
            JsonNode refs = normalizeReferenceArray(referenceNode);
            if (refs == null || !refs.isArray() || refs.size() == 0) {
                payload.putNull("reference");
            } else {
                payload.set("reference", refs.deepCopy());
            }
            if (source != null && !source.isBlank()) {
                payload.put("source", source);
            }
            if (sourceLabel != null && !sourceLabel.isBlank()) {
                payload.put("sourceLabel", sourceLabel);
            }
            if (logicFlow != null && !logicFlow.isBlank()) {
                payload.put("logicFlow", logicFlow);
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return answer == null ? "" : answer;
        }
    }

    private String annotateRagPayload(String payload) {
        return annotatePayload(payload, "RAG", "RAG检索", null);
    }

    private String annotatePayload(String payload, String source, String sourceLabel, String logicFlow) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            ObjectNode objectNode;
            if (node != null && node.isObject()) {
                objectNode = (ObjectNode) node.deepCopy();
            } else {
                objectNode = objectMapper.createObjectNode();
                objectNode.put("answer", payload);
                objectNode.putNull("reference");
            }
            if (source != null && !source.isBlank()) {
                objectNode.put("source", source);
            }
            if (sourceLabel != null && !sourceLabel.isBlank()) {
                objectNode.put("sourceLabel", sourceLabel);
            }
            if (logicFlow != null && !logicFlow.isBlank()) {
                objectNode.put("logicFlow", logicFlow);
            }
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception ignored) {
            return payload;
        }
    }

    private Flux<ServerSentEvent<String>> executeGeneralKnowledgeAnswerAfterRagMiss(ConversationState state, String query, String skillKnowledgeHint, String logicFlow) {
        String skillPrefix = skillKnowledgeHint == null || skillKnowledgeHint.isBlank() ? "" : skillKnowledgeHint + "\n";
        String ragMissNotice = buildRagUnavailableFallback(query);
        String modelUnavailableFallback = buildModelUnavailableFallback(query);
        StringBuilder modelReply = new StringBuilder();
        List<Message> messages = List.of(
                Message.builder()
                        .role("system")
                        .content("你是中文助手。当前知识库未检索到直接相关资料。请先说明以下内容基于通用知识，再给出清晰、简洁且可执行的回答。")
                        .build(),
                Message.builder()
                        .role("user")
                        .content(query == null ? "" : query)
                        .build()
        );
        Flux<ServerSentEvent<String>> prelude = Flux.empty();
        if (logicFlow != null && !logicFlow.isBlank()) {
            String payload = buildStructuredPayload("", null, "LLM", "大模型回答", logicFlow);
            prelude = Flux.just(ServerSentEvent.<String>builder().event("message").data(payload).build());
        }
        return Flux.concat(
                        prelude,
                        Flux.just(ServerSentEvent.<String>builder().event("token").data(skillPrefix + ragMissNotice + "\n【模型知识】\n").build()),
                        llmClient.chatStream(OpenAiChatRequest.builder().messages(messages).build())
                                .mapNotNull(response -> {
                                    if (response.getChoices() == null || response.getChoices().isEmpty()) {
                                        return null;
                                    }
                                    OpenAiChatResponse.Choice choice = response.getChoices().get(0);
                                    OpenAiChatResponse.Delta delta = choice.getDelta();
                                    OpenAiChatResponse.Message message = choice.getMessage();
                                    if (delta != null) {
                                        String text = extractText(delta.getContent(), delta.getReasoningContent());
                                        if (text != null) {
                                            modelReply.append(text);
                                            return ServerSentEvent.<String>builder().event("token").data(text).build();
                                        }
                                    }
                                    if (message != null) {
                                        String text = extractText(message.getContent(), message.getReasoningContent());
                                        if (text != null) {
                                            modelReply.append(text);
                                            return ServerSentEvent.<String>builder().event("token").data(text).build();
                                        }
                                    }
                                    return null;
                                })
                                .onErrorResume(ex -> Flux.empty())
                )
                .concatWith(Flux.defer(() -> {
                    List<ServerSentEvent<String>> tail = new ArrayList<>();
                    String finalBody;
                    if (modelReply.length() == 0) {
                        finalBody = modelUnavailableFallback;
                        tail.add(ServerSentEvent.<String>builder().event("token").data(modelUnavailableFallback).build());
                    } else {
                        finalBody = modelReply.toString();
                    }
                    state.getMemoryWindow().add(Message.builder()
                            .role("assistant")
                            .content(skillPrefix + ragMissNotice + "\n【模型知识】\n" + finalBody)
                            .build());
                    state.setStatus("FINISHED");
                    conversationService.saveState(state);
                    tail.add(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
                    return Flux.fromIterable(tail);
                }));
    }

    private String overridePayloadAnswer(JsonNode node, String answer) {
        try {
            ObjectNode payload = node != null && node.isObject()
                    ? (ObjectNode) node.deepCopy()
                    : objectMapper.createObjectNode();
            payload.put("answer", answer == null ? "" : answer);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return answer == null ? "" : answer;
        }
    }

    private String buildSkillKnowledgeHint(String query, Long userId) {
        if (!looksLikeKnowledgeQuestion(query)) {
            return "";
        }
        Optional<ToolSpec> matched = skillRegistryService.matchTool(query, userId);
        if (matched.isEmpty()) {
            return "";
        }
        ToolSpec spec = matched.get();
        String toolName = spec.getName() == null ? "" : spec.getName().trim();
        String desc = spec.getDescription() == null ? "" : spec.getDescription().trim();
        if (toolName.isBlank() && desc.isBlank()) {
            return "";
        }
        String aliasText = spec.getTriggerKeywords() == null ? "" : spec.getTriggerKeywords().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .limit(3)
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        StringBuilder builder = new StringBuilder("【相关技能】");
        if (!desc.isBlank()) {
            builder.append(desc);
        } else {
            builder.append(toolName);
        }
        if (!aliasText.isBlank()) {
            builder.append("（可用触发词：").append(aliasText).append("）");
        }
        return builder.toString();
    }

    private String mergeSkillHint(String skillKnowledgeHint, String answer) {
        String hint = skillKnowledgeHint == null ? "" : skillKnowledgeHint.trim();
        String body = answer == null ? "" : answer.trim();
        if (hint.isBlank()) {
            return body;
        }
        if (body.isBlank()) {
            return hint;
        }
        if (body.contains(hint)) {
            return body;
        }
        return hint + "\n" + body;
    }

    private boolean isIndicatorKnowledgeQuestion(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (q.isBlank() || !looksLikeKnowledgeQuestion(query)) {
            return false;
        }
        return q.contains("指标") || q.contains("校核") || q.contains("核验");
    }

    private void appendLogicStep(List<String> steps, String stepText) {
        if (steps == null || stepText == null || stepText.isBlank()) {
            return;
        }
        steps.add((steps.size() + 1) + ". " + stepText.trim());
    }

    private String buildLogicFlow(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        return String.join("\n", steps);
    }

    private String buildSkillDescriptionFallback(ToolSpec skill, String query) {
        String q = query == null ? "" : query.trim();
        String name = skill.getName() == null ? "相关技能" : skill.getName().trim();
        String desc = skill.getDescription() == null ? "" : skill.getDescription().trim();
        if (desc.isBlank()) {
            return "知识库未检索到“" + q + "”的直接资料；技能库匹配到技能：" + name + "。";
        }
        return "知识库未检索到“" + q + "”的直接资料；技能库匹配到技能描述：" + desc;
    }

    private Mono<Optional<ToolSpec>> maybeSelectToolByLlm(String query, Long userId) {
        if (query == null || query.isBlank()) {
            return Mono.just(Optional.empty());
        }
        List<ToolSpec> available = skillRegistryService.getAvailableTools(userId);
        if (available.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        List<OpenAiChatRequest.Tool> tools = available.stream()
                .map(spec -> OpenAiChatRequest.Tool.builder()
                        .type("function")
                        .function(OpenAiChatRequest.Function.builder()
                                .name(spec.getName())
                                .description(spec.getDescription())
                                .parameters(spec.getParametersSchema())
                                .build())
                        .build())
                .toList();
        List<Message> messages = List.of(
                Message.builder()
                        .role("system")
                        .content("你是工具路由器。仅在用户明确需要执行某个工具时才返回 tool_call；若只是问知识、解释概念、闲聊或不确定，请不要调用任何工具。")
                        .build(),
                Message.builder()
                        .role("user")
                        .content(query)
                        .build()
        );
        return llmClient.chatStream(OpenAiChatRequest.builder()
                        .messages(messages)
                        .tools(tools)
                        .build())
                .next()
                .map(this::extractToolNameFromSelectorResponse)
                .map(toolName -> findToolByName(available, toolName))
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(ex -> Mono.just(Optional.empty()));
    }

    private Flux<ServerSentEvent<String>> executeLlmOnly(ConversationState state, boolean silentOnEmpty, String fallbackAnswer) {
        StringBuilder assistantReply = new StringBuilder();
        return llmClient.chatStream(com.ai4kb.backend.engine.model.openai.OpenAiChatRequest.builder()
                        .messages(state.getMemoryWindow())
                        .build())
                .mapNotNull(response -> {
                    if (response.getChoices() != null && !response.getChoices().isEmpty()) {
                        OpenAiChatResponse.Choice choice = response.getChoices().get(0);
                        OpenAiChatResponse.Delta delta = choice.getDelta();
                        OpenAiChatResponse.Message message = choice.getMessage();

                        if (delta != null) {
                            String text = extractText(delta.getContent(), delta.getReasoningContent());
                            if (text != null) {
                                assistantReply.append(text);
                                return ServerSentEvent.<String>builder().event("token").data(text).build();
                            }
                        }

                        if (message != null) {
                            String text = extractText(message.getContent(), message.getReasoningContent());
                            if (text != null) {
                                assistantReply.append(text);
                                return ServerSentEvent.<String>builder().event("token").data(text).build();
                            }
                        }
                    }
                    return null;
                })
                .cast(ServerSentEvent.class)
                .map(event -> (ServerSentEvent<String>) event)
                .concatWith(Flux.defer(() -> {
                    List<ServerSentEvent<String>> tail = new ArrayList<>();
                    if (assistantReply.length() > 0) {
                        state.getMemoryWindow().add(Message.builder()
                                .role("assistant")
                                .content(assistantReply.toString())
                                .build());
                        state.setStatus("FINISHED");
                        conversationService.saveState(state);
                    } else {
                        if (fallbackAnswer != null && !fallbackAnswer.isBlank()) {
                            tail.add(ServerSentEvent.<String>builder().event("token").data(fallbackAnswer).build());
                            state.getMemoryWindow().add(Message.builder()
                                    .role("assistant")
                                    .content(fallbackAnswer)
                                    .build());
                            state.setStatus("FINISHED");
                            conversationService.saveState(state);
                        } else if (!silentOnEmpty) {
                            tail.add(ServerSentEvent.<String>builder().event("error").data("当前模型与知识库暂时不可用，请稍后再试。").build());
                            conversationService.saveState(state);
                        } else {
                            conversationService.saveState(state);
                        }
                    }
                    tail.add(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
                    return Flux.fromIterable(tail);
                }))
                .onErrorResume(ex -> {
                    List<ServerSentEvent<String>> tail = new ArrayList<>();
                    if (fallbackAnswer != null && !fallbackAnswer.isBlank()) {
                        tail.add(ServerSentEvent.<String>builder().event("token").data(fallbackAnswer).build());
                        state.getMemoryWindow().add(Message.builder()
                                .role("assistant")
                                .content(fallbackAnswer)
                                .build());
                        state.setStatus("FINISHED");
                    } else if (!silentOnEmpty) {
                        tail.add(ServerSentEvent.<String>builder().event("error").data("当前模型与知识库暂时不可用，请稍后再试。").build());
                    }
                    conversationService.saveState(state);
                    tail.add(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
                    return Flux.fromIterable(tail);
                });
    }

    private String extractText(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return null;
    }

    private String resolveUsername(Long userId) {
        if (userId == null) {
            return "admin";
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return "admin";
        }
        return user.getUsername();
    }

    private boolean isRagFailure(String answer) {
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

    private String normalizeSsePayload(String payload) {
        if (payload == null) {
            return "";
        }
        String trimmed = payload.trim();
        if (trimmed.startsWith("data:")) {
            return trimmed.substring(5).trim();
        }
        return trimmed;
    }

    private String wrapPlainRagPayload(String text) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answer", text);
            payload.put("reference", null);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractToolNameFromSelectorResponse(OpenAiChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return null;
        }
        OpenAiChatResponse.Choice choice = response.getChoices().get(0);
        if (choice.getMessage() != null && choice.getMessage().getToolCalls() != null && !choice.getMessage().getToolCalls().isEmpty()) {
            OpenAiChatResponse.ToolCall toolCall = choice.getMessage().getToolCalls().get(0);
            if (toolCall.getFunction() != null) {
                return toolCall.getFunction().getName();
            }
        }
        String content = choice.getMessage() == null ? null : choice.getMessage().getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            String toolName = node.path("tool_name").asText("");
            return toolName.isBlank() ? null : toolName;
        } catch (Exception ignored) {
            return null;
        }
    }

    private RouteDecision extractRouteDecision(OpenAiChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return RouteDecision.unknown();
        }
        OpenAiChatResponse.Choice choice = response.getChoices().get(0);
        OpenAiChatResponse.Message message = choice.getMessage();
        if (message == null) {
            return RouteDecision.unknown();
        }
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            OpenAiChatResponse.ToolCall toolCall = message.getToolCalls().get(0);
            if (toolCall.getFunction() != null && "route_decision".equals(toolCall.getFunction().getName())) {
                String arguments = toolCall.getFunction().getArguments();
                RouteDecision fromArgs = parseRouteDecisionJson(arguments);
                if (fromArgs != null) {
                    return fromArgs;
                }
            }
        }
        RouteDecision fromContent = parseRouteDecisionJson(message.getContent());
        return fromContent == null ? RouteDecision.unknown() : fromContent;
    }

    private RouteDecision parseRouteDecisionJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            String route = node.path("route").asText("UNKNOWN").trim().toUpperCase(Locale.ROOT);
            double confidence = node.path("confidence").asDouble(0d);
            if (confidence < 0d) {
                confidence = 0d;
            }
            if (confidence > 1d) {
                confidence = 1d;
            }
            String toolName = node.path("tool_name").asText("").trim();
            if (toolName.isBlank()) {
                toolName = null;
            }
            String reason = node.path("reason").asText("");
            return new RouteDecision(route, confidence, toolName, reason);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeRoute(String route) {
        if (route == null || route.isBlank()) {
            return "UNKNOWN";
        }
        String value = route.trim().toUpperCase(Locale.ROOT);
        if ("TOOL".equals(value) || "RAG".equals(value) || "CHAT".equals(value)) {
            return value;
        }
        return "UNKNOWN";
    }

    private double effectiveToolConfidenceThreshold() {
        if (toolConfidenceThreshold > 0d && toolConfidenceThreshold <= 1d) {
            return toolConfidenceThreshold;
        }
        return 0.55d;
    }

    private double effectiveClarifyConfidenceThreshold() {
        if (clarifyConfidenceThreshold > 0d && clarifyConfidenceThreshold <= 1d) {
            return clarifyConfidenceThreshold;
        }
        return 0.45d;
    }

    private double effectiveRaceConfidenceThreshold() {
        if (raceConfidenceThreshold > 0d && raceConfidenceThreshold <= 1d) {
            return raceConfidenceThreshold;
        }
        return 0.75d;
    }

    private boolean isClarifyNeeded(String route, double confidence, double clarifyThreshold) {
        if (!("TOOL".equals(route) || "RAG".equals(route) || "CHAT".equals(route))) {
            return false;
        }
        return confidence < clarifyThreshold;
    }

    private boolean isRaceNeeded(String route, double confidence, double clarifyThreshold, double raceThreshold) {
        if (!("RAG".equals(route) || "CHAT".equals(route))) {
            return false;
        }
        if (confidence < clarifyThreshold) {
            return false;
        }
        return confidence < raceThreshold;
    }

    private Flux<ServerSentEvent<String>> executeDualPathRace(ConversationState state, String query, String route, double confidence) {
        String username = resolveUsername(state.getUserId());
        Mono<RaceCandidate> ragMono = collectRagRaceCandidate(username, query)
                .timeout(Duration.ofSeconds(12))
                .onErrorResume(ex -> Mono.just(RaceCandidate.empty("RAG")));
        Mono<RaceCandidate> llmMono = collectLlmRaceCandidate(state)
                .timeout(Duration.ofSeconds(12))
                .onErrorResume(ex -> Mono.just(RaceCandidate.empty("CHAT")));
        return Mono.zip(ragMono, llmMono)
                .flatMapMany(tuple -> applyRaceDecision(state, query, route, confidence, tuple.getT1(), tuple.getT2()))
                .onErrorResume(ex -> executeRagWithFallback(state, query));
    }

    private Flux<ServerSentEvent<String>> applyRaceDecision(ConversationState state, String query, String route, double confidence, RaceCandidate rag, RaceCandidate llm) {
        RaceCandidate winner = chooseRaceWinner(route, rag, llm);
        if (winner == null || !winner.hasAnswer()) {
            return executeRagWithFallback(state, query);
        }
        if ("RAG".equals(winner.source())) {
            String payload = winner.toRagPayloadJson(objectMapper);
            if (payload == null) {
                return executeRagWithFallback(state, query);
            }
            state.getMemoryWindow().add(Message.builder()
                    .role("assistant")
                    .content(winner.answer())
                    .build());
            state.setStatus("FINISHED");
            conversationService.saveState(state);
            log.info("route_decision race_result: winner=RAG route={} confidence={} rag_score={} llm_score={} query={}",
                    route, confidence, rag.score(), llm.score(), query);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("message").data(annotateRagPayload(payload)).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
        state.getMemoryWindow().add(Message.builder()
                .role("assistant")
                .content(winner.answer())
                .build());
        state.setStatus("FINISHED");
        conversationService.saveState(state);
        log.info("route_decision race_result: winner=CHAT route={} confidence={} rag_score={} llm_score={} query={}",
                route, confidence, rag.score(), llm.score(), query);
        return Flux.just(
                ServerSentEvent.<String>builder().event("token").data(winner.answer()).build(),
                ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
        );
    }

    public ToolCallDraft createManualToolDraft(String conversationId, Long userId, String toolCode, String query) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (toolCode == null || toolCode.isBlank()) {
            throw new IllegalArgumentException("toolCode 不能为空");
        }
        ToolSpec spec = skillRegistryService.findAvailableToolByCode(userId, toolCode.trim())
                .orElseThrow(() -> new IllegalArgumentException("技能不存在或当前用户无权限: " + toolCode));
        ConversationState state = ensureConversationState(conversationId, userId);
        String normalizedQuery = query == null ? "" : query.trim();
        try {
            return createAndSaveDraft(state, spec, normalizedQuery);
        } catch (Exception e) {
            throw new IllegalStateException("创建技能草稿失败: " + e.getMessage(), e);
        }
    }

    private RaceCandidate chooseRaceWinner(String route, RaceCandidate rag, RaceCandidate llm) {
        if (rag == null) {
            rag = RaceCandidate.empty("RAG");
        }
        if (llm == null) {
            llm = RaceCandidate.empty("CHAT");
        }
        if (!rag.hasAnswer() && llm.hasAnswer()) {
            return llm;
        }
        if (rag.hasAnswer() && !llm.hasAnswer()) {
            return rag;
        }
        if (!rag.hasAnswer()) {
            return null;
        }
        if ("RAG".equals(route) && rag.score() + 1 >= llm.score()) {
            return rag;
        }
        if ("CHAT".equals(route) && llm.score() + 1 >= rag.score()) {
            return llm;
        }
        if (rag.score() >= llm.score()) {
            return rag;
        }
        return llm;
    }

    private Mono<RaceCandidate> collectRagRaceCandidate(String username, String query) {
        AtomicBoolean hasReference = new AtomicBoolean(false);
        StringBuilder answer = new StringBuilder();
        return chatProcessor.process(username, query, true)
                .map(this::normalizeSsePayload)
                .filter(payload -> !payload.isBlank())
                .filter(payload -> !"[DONE]".equals(payload))
                .flatMap(payload -> {
                    try {
                        JsonNode node = objectMapper.readTree(payload);
                        String text = node.path("answer").asText("");
                        if (isRagFailure(text)) {
                            return Mono.empty();
                        }
                        if (!text.isBlank()) {
                            answer.append(text);
                        }
                        JsonNode refs = node.path("reference");
                        if (refs != null && refs.isArray() && refs.size() > 0) {
                            hasReference.set(true);
                        }
                        return Mono.just(node);
                    } catch (Exception ignored) {
                        if (isRagFailure(payload)) {
                            return Mono.empty();
                        }
                        if (!payload.isBlank()) {
                            answer.append(payload);
                        }
                        return Mono.empty();
                    }
                })
                .then(Mono.fromSupplier(() -> RaceCandidate.of("RAG", answer.toString(), hasReference.get())));
    }

    private Mono<RaceCandidate> collectLlmRaceCandidate(ConversationState state) {
        StringBuilder answer = new StringBuilder();
        return llmClient.chatStream(OpenAiChatRequest.builder()
                        .messages(state.getMemoryWindow())
                        .build())
                .mapNotNull(response -> {
                    if (response.getChoices() == null || response.getChoices().isEmpty()) {
                        return null;
                    }
                    OpenAiChatResponse.Choice choice = response.getChoices().get(0);
                    if (choice.getDelta() != null) {
                        return extractText(choice.getDelta().getContent(), choice.getDelta().getReasoningContent());
                    }
                    if (choice.getMessage() != null) {
                        return extractText(choice.getMessage().getContent(), choice.getMessage().getReasoningContent());
                    }
                    return null;
                })
                .doOnNext(answer::append)
                .then(Mono.fromSupplier(() -> RaceCandidate.of("CHAT", answer.toString(), false)));
    }

    private Flux<ServerSentEvent<String>> buildClarifyEvent(ConversationState state, String query, RouteDecision decision, String normalizedRoute) {
        try {
            String clarifyQuestion = buildClarifyQuestion(normalizedRoute, query, decision.toolName());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", clarifyQuestion);
            payload.put("route", normalizedRoute);
            payload.put("confidence", decision.confidence());
            payload.put("tool_name", decision.toolName());
            payload.put("reason", decision.reason());
            payload.put("suggestions", buildClarifySuggestions(normalizedRoute, query, decision.toolName()));
            String payloadJson = objectMapper.writeValueAsString(payload);
            state.getMemoryWindow().add(Message.builder()
                    .role("assistant")
                    .content(clarifyQuestion)
                    .build());
            state.setStatus("WAITING_CLARIFY");
            conversationService.saveState(state);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("clarify").data(payloadJson).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        } catch (Exception e) {
            log.error("Failed to build clarify event", e);
            return fallbackRoute(state, query);
        }
    }

    private String buildClarifyQuestion(String route, String query, String toolName) {
        String q = query == null ? "" : query.trim();
        if ("TOOL".equals(route)) {
            if (toolName != null && !toolName.isBlank()) {
                return "我对是否要直接执行工具“" + toolName + "”把握不高。你希望我立即执行工具，还是先做知识解释？";
            }
            return "我判断你可能想执行工具，但置信度较低。你希望我执行工具，还是先给你知识解释？";
        }
        if ("RAG".equals(route)) {
            return "我不确定你更想要知识库检索答案，还是让系统执行操作。请明确你的目标。";
        }
        if ("CHAT".equals(route)) {
            return "我不确定你是在闲聊还是在提业务问题。请补充你的意图，我再给出更准确结果。";
        }
        return "我需要你补充一下意图，才能继续准确处理你的请求。";
    }

    private List<String> buildClarifySuggestions(String route, String query, String toolName) {
        String q = query == null ? "" : query.trim();
        if ("TOOL".equals(route)) {
            String toolHint = toolName == null || toolName.isBlank() ? "请直接执行合适技能" : "请直接执行技能：" + toolName;
            return List.of(
                    toolHint,
                    "先解释相关规则，不执行工具",
                    "我会补充输入文件和参数后再执行"
            );
        }
        if ("RAG".equals(route)) {
            return List.of(
                    "我只要知识库检索答案，并给出处",
                    "我希望你调用技能执行，不只是解释",
                    "先给我简要结论，再给详细依据"
            );
        }
        if ("CHAT".equals(route)) {
            return List.of(
                    "这是业务问题，请用知识库回答",
                    "这是闲聊，直接自然回答即可",
                    q.isBlank() ? "我补充更具体的问题" : "我想问的是：" + q
            );
        }
        return List.of("我补充一下问题背景", "请先给一个简短回答");
    }

    private Optional<ToolSpec> findToolByName(List<ToolSpec> specs, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return specs.stream()
                .filter(spec -> toolName.equals(spec.getName()))
                .findFirst();
    }

    private Optional<ToolSpec> matchToolByIntentHeuristics(String query, Long userId) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            return Optional.empty();
        }
        boolean hasCadContext = q.contains("cad") || q.contains("dxf") || q.contains("图纸");
        boolean hasVerificationIntent = q.contains("指标")
                || q.contains("校核")
                || q.contains("核验")
                || q.contains("合规")
                || q.contains("检查");
        if (!hasCadContext || !hasVerificationIntent) {
            return Optional.empty();
        }
        return skillRegistryService.getAvailableTools(userId).stream()
                .filter(spec -> "cad_text_extractor_indicator_verification".equals(spec.getName()))
                .findFirst();
    }

    private String buildRagUnavailableFallback(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return "【检索状态】知识库未检索到可用资料。";
        }
        return "【检索状态】知识库中未检索到与“" + q + "”直接相关的资料。";
    }

    private String buildModelUnavailableFallback(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return "当前大模型暂时不可用，请稍后重试。";
        }
        return "当前无法基于模型知识继续回答你的问题（" + q + "），请稍后重试。";
    }

    private boolean isGeneralDialogueQuery(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            return false;
        }
        return q.startsWith("你好")
                || q.startsWith("嗨")
                || q.startsWith("hello")
                || q.contains("你是谁")
                || q.contains("你能做什么")
                || q.contains("介绍一下你自己")
                || q.equals("在吗");
    }

    private String buildGeneralDialogueFallback(String query) {
        String q = query == null ? "" : query.trim();
        if (q.contains("你是谁") || q.contains("介绍一下你自己")) {
            return "我是AI4LocalKnowledgeBase助手，可以为你做知识库检索问答、技能识别与执行（如指标校核），并返回可下载结果。";
        }
        if (q.contains("你能做什么")) {
            return "我可以先识别是否需要调用技能；如果需要会进入技能流程，不需要时优先走知识库问答，并在必要时回退到通用回答。";
        }
        return "你好，我在。你可以直接提问题，我会自动判断是走知识库问答还是调用技能。";
    }

    /**
     * 将执行过程标准化为“分析步骤事件”：
     * - message -> analysis_step(completed)
     * - tool_draft -> analysis_step(waiting_approval)
     * - 首个 token -> analysis_step(streaming)
     * - done -> analysis_summary
     * 该方法不吞掉原始事件，而是“补充事件后透传原事件”，确保前端兼容旧渲染逻辑。
     */
    private Flux<ServerSentEvent<String>> decorateExecutionWithAnalysisEvents(Flux<ServerSentEvent<String>> upstream,
                                                                              ConversationState state,
                                                                              ConversationState.PlanSnapshot plan,
                                                                              String rerunMode) {
        AtomicBoolean tokenStepSent = new AtomicBoolean(false);
        AtomicBoolean stepOpened = new AtomicBoolean(false);
        AtomicInteger stepCounter = new AtomicInteger(0);
        return upstream.concatMap(event -> {
            String eventName = event.event() == null ? "" : event.event();
            if (PLAN_STEP_BOUNDARY_EVENT.equals(eventName)) {
                stepOpened.set(false);
                tokenStepSent.set(false);
                return Flux.empty();
            }
            List<ServerSentEvent<String>> out = new ArrayList<>();
            if ("message".equals(eventName)) {
                Map<String, Object> payload = parseJsonMap(event.data());
                String source = String.valueOf(payload.getOrDefault("source", "UNKNOWN"));
                String label = String.valueOf(payload.getOrDefault("sourceLabel", "执行步骤"));
                String answer = String.valueOf(payload.getOrDefault("answer", ""));
                if (stepOpened.compareAndSet(false, true)) {
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("step_no", stepCounter.incrementAndGet());
                    step.put("type", source);
                    step.put("label", label);
                    step.put("status", "completed");
                    step.put("result", answer);
                    step.put("has_reference", payload.get("reference") != null);
                    out.add(ServerSentEvent.<String>builder().event("analysis_step").data(toJson(step)).build());
                }
            } else if ("tool_draft".equals(eventName)) {
                Map<String, Object> draft = parseJsonMap(event.data());
                if (stepOpened.compareAndSet(false, true)) {
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("step_no", stepCounter.incrementAndGet());
                    step.put("type", "SKILL");
                    step.put("label", "技能库查询");
                    step.put("status", "waiting_approval");
                    step.put("result", draft.getOrDefault("toolName", ""));
                    out.add(ServerSentEvent.<String>builder().event("analysis_step").data(toJson(step)).build());
                }
            } else if ("token".equals(eventName) && tokenStepSent.compareAndSet(false, true) && stepOpened.compareAndSet(false, true)) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step_no", stepCounter.incrementAndGet());
                step.put("type", "LLM");
                step.put("label", "默认知识回答");
                step.put("status", "streaming");
                step.put("result", "正在流式生成回答");
                out.add(ServerSentEvent.<String>builder().event("analysis_step").data(toJson(step)).build());
            } else if ("done".equals(eventName)) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("plan_id", plan.getPlanId());
                summary.put("version", plan.getVersion());
                summary.put("rerun_mode", rerunMode);
                summary.put("restart_from_step", plan.getRestartFromStep() == null ? 1 : plan.getRestartFromStep());
                summary.put("step_count", stepCounter.get());
                summary.put("final_answer", extractLatestAssistantReply(state));
                out.add(ServerSentEvent.<String>builder().event("analysis_summary").data(toJson(summary)).build());
            }
            out.add(event);
            return Flux.fromIterable(out);
        });
    }

    private String extractLatestAssistantReply(ConversationState state) {
        if (state == null || state.getMemoryWindow() == null || state.getMemoryWindow().isEmpty()) {
            return "";
        }
        return state.getMemoryWindow().stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .reduce((first, second) -> second)
                .map(Message::getContent)
                .orElse("");
    }

    /**
     * 统一构建执行计划：
     * 先尝试由 LLM Planner 生成结构化计划；
     * 若解析失败或模型异常，则降级为本地 fallback 计划，保证链路可继续。
     */
    private Mono<ConversationState.PlanSnapshot> buildExecutionPlan(ConversationState state,
                                                                    String query,
                                                                    String adjustmentInstruction,
                                                                    List<Map<String, Object>> editedSteps,
                                                                    String rerunMode,
                                                                    Integer restartFromStep) {
        return buildExecutionPlanByLlm(state, query, adjustmentInstruction, editedSteps, rerunMode, restartFromStep)
                .onErrorResume(ex -> Mono.just(buildFallbackPlan(state, query, adjustmentInstruction, editedSteps, rerunMode, restartFromStep)));
    }

    private Mono<ConversationState.PlanSnapshot> buildExecutionPlanByLlm(ConversationState state,
                                                                         String query,
                                                                         String adjustmentInstruction,
                                                                         List<Map<String, Object>> editedSteps,
                                                                         String rerunMode,
                                                                         Integer restartFromStep) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> stepSchema = new LinkedHashMap<>();
        stepSchema.put("type", "object");
        stepSchema.put("properties", Map.of(
                "route", Map.of("type", "string"),
                "label", Map.of("type", "string"),
                "goal", Map.of("type", "string"),
                "query", Map.of("type", "string"),
                "tool_name", Map.of("type", "string"),
                "continue_when", Map.of("type", "string"),
                "stop_when", Map.of("type", "string")
        ));
        schema.put("properties", Map.of(
                "deep_thinking", Map.of("type", "string"),
                "question_type", Map.of("type", "string", "enum", List.of("KNOWLEDGE", "TASK", "GENERAL")),
                "summary", Map.of("type", "string"),
                "steps", Map.of("type", "array", "items", stepSchema)
        ));
        schema.put("required", List.of("deep_thinking", "question_type", "steps", "summary"));

        String editedStepsJson = editedSteps == null ? "[]" : toJson(editedSteps);
        String planContext = state.getActivePlan() == null ? "" : toJson(state.getActivePlan());
        List<Message> messages = List.of(
                Message.builder()
                        .role("system")
                        .content("你是任务编排规划器。必须先把用户请求拆解为原子子任务，再输出结构化步骤。每个步骤必须有独立 query，route 仅可为 RAG、SKILL、LLM。涉及“调用/执行某工具”的子任务必须给出 SKILL 步骤；涉及“怎么用/参数/触发词”的子任务优先 SKILL；定义解释类问题优先 RAG。若存在人工修改，必须融合后输出新计划。")
                        .build(),
                Message.builder()
                        .role("user")
                        .content("用户问题：" + query + "\n请按子任务逐条拆分，不要把所有任务合并成同一条 query。\n人工调整说明：" + safeText(adjustmentInstruction) + "\n重跑模式：" + rerunMode + "\n从步骤开始：" + (restartFromStep == null ? 1 : restartFromStep) + "\n编辑后的步骤：" + editedStepsJson + "\n当前活跃计划：" + planContext)
                        .build()
        );
        OpenAiChatRequest.Tool plannerTool = OpenAiChatRequest.Tool.builder()
                .type("function")
                .function(OpenAiChatRequest.Function.builder()
                        .name("analysis_plan")
                        .description("输出执行链路计划")
                        .parameters(schema)
                        .build())
                .build();
        return llmClient.chatStream(OpenAiChatRequest.builder()
                        .messages(messages)
                        .tools(List.of(plannerTool))
                        .build())
                .next()
                .map(this::extractPlanPayload)
                .map(payload -> mapToPlanSnapshot(state, query, payload, adjustmentInstruction, editedSteps, rerunMode, restartFromStep))
                .switchIfEmpty(Mono.fromSupplier(() -> buildFallbackPlan(state, query, adjustmentInstruction, editedSteps, rerunMode, restartFromStep)));
    }

    private Map<String, Object> extractPlanPayload(OpenAiChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return Map.of();
        }
        OpenAiChatResponse.Choice choice = response.getChoices().get(0);
        OpenAiChatResponse.Message message = choice.getMessage();
        if (message != null && message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            OpenAiChatResponse.Function function = message.getToolCalls().get(0).getFunction();
            if (function != null && function.getArguments() != null) {
                return parseJsonMap(function.getArguments());
            }
        }
        if (message != null && message.getContent() != null) {
            return parseJsonMap(message.getContent());
        }
        return Map.of();
    }

    private ConversationState.PlanSnapshot mapToPlanSnapshot(ConversationState state,
                                                             String query,
                                                             Map<String, Object> payload,
                                                             String adjustmentInstruction,
                                                             List<Map<String, Object>> editedSteps,
                                                             String rerunMode,
                                                             Integer restartFromStep) {
        int version = state.getTaskMemory() == null ? 1 : state.getTaskMemory().size() + 1;
        List<ConversationState.PlanStep> steps = parsePlanSteps(payload.get("steps"));
        steps = mergeEditedSteps(steps, editedSteps);
        if (editedSteps == null || editedSteps.isEmpty()) {
            steps = enforceAtomicPlanSteps(query, steps, state.getUserId());
        }
        if (steps.isEmpty()) {
            steps = buildDefaultSteps(query, rerunMode, restartFromStep, state.getUserId());
        }
        return ConversationState.PlanSnapshot.builder()
                .planId("plan-" + UUID.randomUUID())
                .version(version)
                .query(query)
                .deepThinking(safeText(payload.get("deep_thinking")))
                .questionType(safeText(payload.get("question_type")))
                .rerunMode(rerunMode)
                .restartFromStep(restartFromStep == null ? 1 : restartFromStep)
                .steps(steps)
                .summary(safeText(payload.get("summary")))
                .adjustmentInstruction(safeText(adjustmentInstruction))
                .adjusted(adjustmentInstruction != null && !adjustmentInstruction.isBlank())
                .createdAt(LocalDateTime.now().toString())
                .build();
    }

    private ConversationState.PlanSnapshot buildFallbackPlan(ConversationState state,
                                                             String query,
                                                             String adjustmentInstruction,
                                                             List<Map<String, Object>> editedSteps,
                                                             String rerunMode,
                                                             Integer restartFromStep) {
        int version = state.getTaskMemory() == null ? 1 : state.getTaskMemory().size() + 1;
        List<ConversationState.PlanStep> steps = parsePlanSteps(editedSteps);
        if (steps.isEmpty()) {
            steps = buildDefaultSteps(query, rerunMode, restartFromStep, state.getUserId());
        }
        String questionType = looksLikeKnowledgeQuestion(query) ? "KNOWLEDGE" : "TASK";
        String deepThinking = "识别到用户请求后，先判断问题类型与执行目标；再按RAG、技能库、默认知识的可用性动态选择路径；若存在人工修改，优先遵循人工修改并基于已执行结果重排后续步骤。";
        String summary = "计划支持多轮RAG与技能库交替，并支持断点续跑与全量重跑。";
        return ConversationState.PlanSnapshot.builder()
                .planId("plan-" + UUID.randomUUID())
                .version(version)
                .query(query)
                .deepThinking(deepThinking)
                .questionType(questionType)
                .rerunMode(rerunMode)
                .restartFromStep(restartFromStep == null ? 1 : restartFromStep)
                .steps(steps)
                .summary(summary)
                .adjustmentInstruction(safeText(adjustmentInstruction))
                .adjusted(adjustmentInstruction != null && !adjustmentInstruction.isBlank())
                .createdAt(LocalDateTime.now().toString())
                .build();
    }

    private List<ConversationState.PlanStep> parsePlanSteps(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<ConversationState.PlanStep> steps = new ArrayList<>();
        int index = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Integer mappedStepNo = parseStepNo(map.get("stepNo"), map.get("step_no"));
            steps.add(ConversationState.PlanStep.builder()
                    .stepNo(mappedStepNo == null ? index : mappedStepNo)
                    .route(safeText(map.get("route")))
                    .label(safeText(map.get("label")))
                    .goal(safeText(map.get("goal")))
                    .query(safeText(map.get("query")))
                    .toolName(firstNonBlank(map.get("tool_name"), map.get("toolName")))
                    .continueWhen(firstNonBlank(map.get("continue_when"), map.get("continueWhen")))
                    .stopWhen(firstNonBlank(map.get("stop_when"), map.get("stopWhen")))
                    .build());
            index++;
        }
        return steps;
    }

    private List<ConversationState.PlanStep> mergeEditedSteps(List<ConversationState.PlanStep> baseSteps,
                                                              List<Map<String, Object>> editedSteps) {
        List<ConversationState.PlanStep> parsedEdited = parsePlanSteps(editedSteps);
        if (parsedEdited.isEmpty()) {
            return baseSteps == null ? new ArrayList<>() : baseSteps;
        }
        if (baseSteps == null || baseSteps.isEmpty()) {
            return parsedEdited;
        }
        List<ConversationState.PlanStep> merged = new ArrayList<>(baseSteps);
        for (ConversationState.PlanStep edited : parsedEdited) {
            int idx = edited.getStepNo() == null ? -1 : edited.getStepNo() - 1;
            if (idx >= 0 && idx < merged.size()) {
                ConversationState.PlanStep origin = merged.get(idx);
                merged.set(idx, ConversationState.PlanStep.builder()
                        .stepNo(origin.getStepNo())
                        .route(firstNonBlank(edited.getRoute(), origin.getRoute()))
                        .label(firstNonBlank(edited.getLabel(), origin.getLabel()))
                        .goal(firstNonBlank(edited.getGoal(), origin.getGoal()))
                        .query(firstNonBlank(edited.getQuery(), origin.getQuery()))
                        .toolName(firstNonBlank(edited.getToolName(), origin.getToolName()))
                        .continueWhen(firstNonBlank(edited.getContinueWhen(), origin.getContinueWhen()))
                        .stopWhen(firstNonBlank(edited.getStopWhen(), origin.getStopWhen()))
                        .build());
            }
        }
        return merged;
    }

    private List<ConversationState.PlanStep> buildDefaultSteps(String query, String rerunMode, Integer restartFromStep, Long userId) {
        List<String> atomicTasks = splitCompositeTasks(query);
        List<ConversationState.PlanStep> steps = new ArrayList<>();
        int index = 1;
        for (String taskQuery : atomicTasks) {
            String route = inferFallbackStepRoute(taskQuery, userId);
            String label = inferFallbackStepLabel(route);
            String goal = inferFallbackStepGoal(route);
            String continueWhen = "SKILL".equals(route) ? "技能未命中或参数不完整时继续" : "当前步骤结果不足以完成用户目标时继续";
            String stopWhen = "SKILL".equals(route) ? "技能草稿可执行且参数齐全" : "当前子任务已获得可用结论";
            String toolName = "SKILL".equals(route)
                    ? skillRegistryService.matchTool(taskQuery, userId).map(ToolSpec::getName).orElse("")
                    : "";
            steps.add(ConversationState.PlanStep.builder()
                    .stepNo(index++)
                    .route(route)
                    .label(label)
                    .goal(goal)
                    .query(taskQuery)
                    .toolName(toolName)
                    .continueWhen(continueWhen)
                    .stopWhen(stopWhen)
                    .build());
        }
        if (steps.isEmpty()) {
            steps.add(ConversationState.PlanStep.builder()
                    .stepNo(1)
                    .route("RAG")
                    .label("RAG查询")
                    .goal("查询知识库相关片段")
                    .query(query)
                    .continueWhen("RAG未命中或问题需要执行动作")
                    .stopWhen("RAG命中且可直接回答")
                    .build());
        }
        if (steps.stream().noneMatch(step -> "LLM".equalsIgnoreCase(safeText(step.getRoute())))) {
            steps.add(ConversationState.PlanStep.builder()
                    .stepNo(steps.size() + 1)
                    .route("LLM")
                    .label("默认知识回答")
                    .goal("整合前序步骤结果并输出最终回答")
                    .query(query)
                    .continueWhen("")
                    .stopWhen("完成最终答复")
                    .build());
        }
        if (isPartialRerun(rerunMode) && restartFromStep != null && restartFromStep > 1) {
            steps = steps.stream()
                    .filter(step -> step.getStepNo() >= restartFromStep)
                    .map(step -> ConversationState.PlanStep.builder()
                            .stepNo(step.getStepNo())
                            .route(step.getRoute())
                            .label(step.getLabel())
                            .goal(step.getGoal())
                            .query(step.getQuery())
                            .toolName(step.getToolName())
                            .continueWhen(step.getContinueWhen())
                            .stopWhen(step.getStopWhen())
                            .build())
                    .toList();
        }
        return steps;
    }

    private List<String> splitCompositeTasks(String query) {
        String normalized = safeText(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        String expanded = normalized
                .replaceAll("\\s*\\d+\\s*[、.．:]\\s*", "；")
                .replace("，并", "；")
                .replace("并且", "；")
                .replace("然后", "；")
                .replace("再", "；")
                .replace("以及", "；");
        String[] segments = expanded.split("[；;\\n]");
        List<String> tasks = new ArrayList<>();
        for (String segment : segments) {
            String item = segment == null ? "" : segment.trim();
            if (item.isBlank()) {
                continue;
            }
            item = item.replaceFirst("^(执行|完成|处理|请|帮我|麻烦)\\s*", "").trim();
            item = item.replaceFirst("^(一共)?(\\d+|[一二三四五六七八九十两])\\s*件?事\\s*[:：]?\\s*", "").trim();
            if (!item.isBlank()) {
                tasks.add(item);
            }
        }
        if (tasks.isEmpty()) {
            return List.of(normalized);
        }
        return tasks;
    }

    private List<ConversationState.PlanStep> enforceAtomicPlanSteps(String query,
                                                                    List<ConversationState.PlanStep> plannerSteps,
                                                                    Long userId) {
        List<ConversationState.PlanStep> existing = plannerSteps == null ? List.of() : plannerSteps;
        List<String> atomicTasks = splitCompositeTasks(query);
        if (atomicTasks.size() <= 1 || existing.isEmpty()) {
            return existing;
        }
        String normalizedQuery = safeText(query);
        long distinctQueryCount = existing.stream()
                .map(step -> safeText(step.getQuery()))
                .filter(text -> !text.isBlank())
                .map(text -> text.replaceAll("\\s+", ""))
                .distinct()
                .count();
        boolean allEmptyOrSameQuery = existing.stream()
                .map(step -> safeText(step.getQuery()))
                .allMatch(text -> text.isBlank() || text.replaceAll("\\s+", "").equals(normalizedQuery.replaceAll("\\s+", "")));
        boolean shouldRebuild = distinctQueryCount <= 1 && allEmptyOrSameQuery;
        if (!shouldRebuild) {
            return existing;
        }
        List<ConversationState.PlanStep> rebuilt = new ArrayList<>();
        int index = 1;
        for (String taskQuery : atomicTasks) {
            String route = inferFallbackStepRoute(taskQuery, userId);
            String label = inferFallbackStepLabel(route);
            String goal = inferFallbackStepGoal(route);
            String toolName = "SKILL".equals(route)
                    ? skillRegistryService.matchTool(taskQuery, userId).map(ToolSpec::getName).orElse("")
                    : "";
            rebuilt.add(ConversationState.PlanStep.builder()
                    .stepNo(index++)
                    .route(route)
                    .label(label)
                    .goal(goal)
                    .query(taskQuery)
                    .toolName(toolName)
                    .continueWhen("SKILL".equals(route) ? "技能未命中或参数不完整时继续" : "当前步骤结果不足以完成用户目标时继续")
                    .stopWhen("SKILL".equals(route) ? "技能草稿可执行且参数齐全" : "当前子任务已获得可用结论")
                    .build());
        }
        boolean hasSummary = rebuilt.stream().anyMatch(step -> "LLM".equals(normalizePlanRoute(step.getRoute())));
        if (!hasSummary) {
            rebuilt.add(ConversationState.PlanStep.builder()
                    .stepNo(index)
                    .route("LLM")
                    .label("结果汇总")
                    .goal("整合前述步骤输出最终回答")
                    .query(query)
                    .continueWhen("存在未覆盖信息点时继续补充")
                    .stopWhen("最终回答完整覆盖用户请求")
                    .build());
        }
        return rebuilt;
    }

    private String inferFallbackStepRoute(String taskQuery, Long userId) {
        String normalized = safeText(taskQuery);
        if (normalized.isBlank()) {
            return "RAG";
        }
        if (isExplicitToolExecutionIntent(normalized)) {
            return "SKILL";
        }
        Pattern usagePattern = Pattern.compile("(怎么用|如何用|使用方法|参数|触发词|示例)");
        if (usagePattern.matcher(normalized).find() && normalized.contains("工具")) {
            return "SKILL";
        }
        if (skillRegistryService.matchTool(normalized, userId).isPresent() && normalized.contains("工具")) {
            return "SKILL";
        }
        if (looksLikeKnowledgeQuestion(normalized) || normalized.startsWith("查找") || normalized.startsWith("查询")) {
            return "RAG";
        }
        return "LLM";
    }

    private String inferFallbackStepLabel(String route) {
        String normalized = safeText(route).toUpperCase(Locale.ROOT);
        if ("SKILL".equals(normalized)) {
            return "技能库查询";
        }
        if ("LLM".equals(normalized)) {
            return "默认知识回答";
        }
        return "RAG查询";
    }

    private String inferFallbackStepGoal(String route) {
        String normalized = safeText(route).toUpperCase(Locale.ROOT);
        if ("SKILL".equals(normalized)) {
            return "检查可执行技能并构建执行草稿";
        }
        if ("LLM".equals(normalized)) {
            return "在RAG与技能不足时给出通用回答";
        }
        return "查询知识库相关片段";
    }

    private void trimToLatestUserTurn(ConversationState state) {
        List<Message> memory = state.getMemoryWindow();
        if (memory == null || memory.isEmpty()) {
            return;
        }
        int lastUserIndex = -1;
        for (int i = memory.size() - 1; i >= 0; i--) {
            if ("user".equals(memory.get(i).getRole())) {
                lastUserIndex = i;
                break;
            }
        }
        if (lastUserIndex >= 0 && lastUserIndex < memory.size()) {
            state.setMemoryWindow(new ArrayList<>(memory.subList(0, lastUserIndex + 1)));
        }
    }

    private String normalizeRerunMode(String rerunMode) {
        String mode = rerunMode == null ? "" : rerunMode.trim().toUpperCase(Locale.ROOT);
        if ("FULL".equals(mode) || "FULL_RERUN".equals(mode) || "RESTART_ALL".equals(mode)) {
            return "FULL_RERUN";
        }
        if ("PARTIAL".equals(mode)
                || "PARTIAL_RERUN".equals(mode)
                || "RESUME_FROM_STEP".equals(mode)
                || "MIDDLE_RERUN".equals(mode)
                || "RESTART_FROM_MIDDLE".equals(mode)) {
            return "PARTIAL_RERUN";
        }
        return "AUTO";
    }

    private boolean isPartialRerun(String rerunMode) {
        return "PARTIAL_RERUN".equals(rerunMode);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String safeText(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    private String firstNonBlank(Object preferred, Object fallback) {
        String first = safeText(preferred);
        if (!first.isBlank()) {
            return first;
        }
        return safeText(fallback);
    }

    private Integer parseStepNo(Object preferred, Object fallback) {
        Integer preferredNo = parseInteger(preferred);
        if (preferredNo != null && preferredNo > 0) {
            return preferredNo;
        }
        Integer fallbackNo = parseInteger(fallback);
        if (fallbackNo != null && fallbackNo > 0) {
            return fallbackNo;
        }
        return null;
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = safeText(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ConversationState ensureConversationState(String conversationId, Long userId) {
        ConversationState state = conversationService.getState(conversationId);
        if (state == null) {
            state = ConversationState.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .memoryWindow(new ArrayList<>())
                    .taskMemory(new ArrayList<>())
                    .conversationMemory(new ArrayList<>())
                    .status("RUNNING")
                    .build();
        }
        if (state.getMemoryWindow() == null) {
            state.setMemoryWindow(new ArrayList<>());
        }
        if (state.getTaskMemory() == null) {
            state.setTaskMemory(new ArrayList<>());
        }
        if (state.getConversationMemory() == null) {
            state.setConversationMemory(new ArrayList<>());
        }
        if (state.getUserId() == null) {
            state.setUserId(userId);
        }
        return state;
    }

    private ToolCallDraft createAndSaveDraft(ConversationState state, ToolSpec spec, String query) throws Exception {
        String tcId = "tc-" + UUID.randomUUID();
        Map<String, Object> payload = skillRegistryService.buildDraftPayload(spec, query);
        String argsJson = objectMapper.writeValueAsString(payload.getOrDefault("draft_args", Map.of()));
        Message.ToolCall mc = Message.ToolCall.builder()
                .id(tcId)
                .type("function")
                .function(Message.Function.builder()
                        .name(spec.getName())
                        .arguments(argsJson)
                        .build())
                .build();
        Message asstMsg = Message.builder()
                .role("assistant")
                .toolCalls(List.of(mc))
                .build();
        state.getMemoryWindow().add(asstMsg);
        state.setStatus("WAITING_APPROVAL");
        state.setPendingToolCallId(tcId);
        ToolCallDraft draft = ToolCallDraft.builder()
                .toolCallId(tcId)
                .toolName(spec.getName())
                .draftArgs(argsJson)
                .toolSpec(payload)
                .approvalStatus("PENDING")
                .createdAt(LocalDateTime.now().toString())
                .build();
        conversationService.saveDraft(draft);
        conversationService.saveState(state);
        return draft;
    }

    private Flux<ServerSentEvent<String>> buildToolDraftEvent(ConversationState state, ToolSpec spec, String query) {
        try {
            ToolCallDraft draft = createAndSaveDraft(state, spec, query);
            String draftJson = objectMapper.writeValueAsString(draft);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("tool_draft").data(draftJson).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        } catch (Exception e) {
            log.error("Failed to build tool draft", e);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("error").data("Tool draft build failed: " + e.getMessage()).build(),
                    ServerSentEvent.<String>builder().event("done").data("[DONE]").build()
            );
        }
    }

    private record RouteDecision(String route, double confidence, String toolName, String reason) {
        private static RouteDecision unknown() {
            return new RouteDecision("UNKNOWN", 0d, null, "");
        }
    }

    private record DecisionEnvelope(RouteDecision decision, String source, RouteDecision localCandidate, RouteDecision plannerCandidate) {
        private static DecisionEnvelope of(RouteDecision decision, String source, RouteDecision localCandidate, RouteDecision plannerCandidate) {
            return new DecisionEnvelope(
                    decision == null ? RouteDecision.unknown() : decision,
                    source == null ? "UNKNOWN" : source,
                    localCandidate,
                    plannerCandidate
            );
        }
    }

    private record RaceCandidate(String source, String answer, boolean hasReference) {
        private static RaceCandidate empty(String source) {
            return new RaceCandidate(source, "", false);
        }

        private static RaceCandidate of(String source, String answer, boolean hasReference) {
            String normalizedAnswer = answer == null ? "" : answer.trim();
            return new RaceCandidate(source, normalizedAnswer, hasReference);
        }

        private boolean hasAnswer() {
            return answer != null && !answer.isBlank();
        }

        private int score() {
            if (!hasAnswer()) {
                return 0;
            }
            int score = 1;
            int len = answer.length();
            if (len >= 20 && len <= 600) {
                score += 1;
            }
            if ("RAG".equals(source) && hasReference) {
                score += 2;
            }
            return score;
        }

        private String toRagPayloadJson(ObjectMapper mapper) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("answer", answer);
                if (hasReference) {
                    payload.put("reference", List.of(Map.of("document_name", "竞速检索结果", "content", answer, "similarity", 1.0)));
                } else {
                    payload.put("reference", null);
                }
                return mapper.writeValueAsString(payload);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
