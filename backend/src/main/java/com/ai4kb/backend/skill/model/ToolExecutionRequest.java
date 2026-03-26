package com.ai4kb.backend.skill.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
/**
 * 工具执行请求模型。
 * 聚合一次工具调用执行所需的会话上下文、参数与输入文件。
 */
public class ToolExecutionRequest {
    private String conversationId;
    private String toolCallId;
    private Long userId;
    private Map<String, Object> args;
    private List<SkillFileRecord> inputFiles;
}
