package com.ai4kb.backend.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * 会话状态模型。
 * 用于承载短期消息窗口、分析计划快照、审批挂起点和会话级元记忆信息。
 */
public class ConversationState {
    private String conversationId;
    private Long userId;
    @Builder.Default
    private List<Message> memoryWindow = new ArrayList<>();

    private String status;

    private String pendingToolCallId;

    @Builder.Default
    private List<PlanSnapshot> taskMemory = new ArrayList<>();

    @Builder.Default
    private List<Map<String, Object>> conversationMemory = new ArrayList<>();

    private PlanSnapshot activePlan;

    private String updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * 单次分析计划快照。
     * 每次重规划都会生成新版本，并写入 taskMemory 形成可追溯历史。
     */
    public static class PlanSnapshot {
        private String planId;
        private Integer version;
        private String query;
        private String deepThinking;
        private String questionType;
        private String rerunMode;
        private Integer restartFromStep;
        @Builder.Default
        private List<PlanStep> steps = new ArrayList<>();
        private String summary;
        private String adjustmentInstruction;
        private String createdAt;
        private Boolean adjusted;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * 分析计划中的单步骤定义。
     * route 决定执行分支（RAG/TOOL/CHAT），其余字段用于执行条件与前端展示。
     */
    public static class PlanStep {
        private Integer stepNo;
        private String route;
        private String label;
        private String goal;
        private String query;
        private String toolName;
        private String continueWhen;
        private String stopWhen;
    }
}
