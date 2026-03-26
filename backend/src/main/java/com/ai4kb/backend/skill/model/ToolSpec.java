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
    private String name;
    private String description;
    private List<String> triggerKeywords;
    private String inputMode;
    private String outputMode;
    private boolean uploadRequired;
    private List<String> acceptedFileTypes;
    private Integer maxFiles;
    private Map<String, Object> parametersSchema;
}
