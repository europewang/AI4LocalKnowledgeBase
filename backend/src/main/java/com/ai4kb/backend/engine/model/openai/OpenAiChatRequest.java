package com.ai4kb.backend.engine.model.openai;

import com.ai4kb.backend.engine.model.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * OpenAI 兼容聊天请求模型。
 * 支持普通消息与工具调用定义透传。
 */
public class OpenAiChatRequest {
    private String model;
    private List<Message> messages;
    private Boolean stream;
    private List<Tool> tools;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * 工具声明节点。
     */
    public static class Tool {
        private String type;
        private Function function;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * 函数工具定义。
     */
    public static class Function {
        private String name;
        private String description;
        private Map<String, Object> parameters;
    }
}
