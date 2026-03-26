package com.ai4kb.backend.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * 对话消息模型。
 * 兼容普通 user/assistant/system 消息与 tool_call / tool_result 消息结构。
 */
public class Message {
    private String role;
    private String content;
    private String name;
    private String toolCallId;
    private List<ToolCall> toolCalls;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * 工具调用节点，通常来自 assistant 的 function/tool 调用意图。
     */
    public static class ToolCall {
        private String id;
        private String type;
        private Function function;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * 工具函数描述，arguments 使用 JSON 字符串承载参数。
     */
    public static class Function {
        private String name;
        private String arguments;
    }
}
