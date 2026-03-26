package com.ai4kb.backend.engine.model.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
/**
 * OpenAI 兼容聊天响应模型。
 * 同时承载流式增量片段与非流式完整消息。
 */
public class OpenAiChatResponse {
    private String id;
    private String object;
    private Long created;
    private String model;
    private List<Choice> choices;
    
    @Data
    /**
     * 候选结果节点。
     */
    public static class Choice {
        private Integer index;
        private Delta delta;
        private Message message;
        
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    /**
     * 流式增量消息片段。
     */
    public static class Delta {
        private String role;
        private String content;
        @JsonProperty("reasoning_content")
        private String reasoningContent;
        
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
    }
    
    @Data
    /**
     * 非流式完整消息。
     */
    public static class Message {
        private String role;
        private String content;
        @JsonProperty("reasoning_content")
        private String reasoningContent;
        
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
    }
    
    @Data
    /**
     * 工具调用节点。
     */
    public static class ToolCall {
        private Integer index;
        private String id;
        private String type;
        private Function function;
    }

    @Data
    /**
     * 工具函数参数载荷。
     */
    public static class Function {
        private String name;
        private String arguments;
    }
}
