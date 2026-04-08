package com.ai4kb.backend.skill.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
/**
 * 工具规格描述模型。
 * 用于向路由器与前端暴露工具元信息、输入输出能力与参数 JSON Schema。
 */
public class ToolSpec {
    /**
     * 工具唯一编码（通常对应 tool_code）。
     */
    private String name;
    /**
     * 工具展示名（对应 tool_name），用于前端优先显示。
     */
    private String toolName;
    /**
     * 兼容字段：与 toolName 含义一致，便于前端统一读取。
     */
    private String displayName;
    private String description;
    private List<String> triggerKeywords;
    private String inputMode;
    private String outputMode;
    private boolean uploadRequired;
    private List<String> acceptedFileTypes;
    private Integer maxFiles;
    private Map<String, Object> parametersSchema;
}
