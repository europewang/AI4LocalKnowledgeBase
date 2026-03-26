package com.ai4kb.backend.skill.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
/**
 * 工具执行结果模型。
 * 包含执行状态、摘要、结构化数据与结果文件列表。
 */
public class ToolExecutionResult {
    private boolean success;
    private String summary;
    private String errorMessage;
    private Map<String, Object> structuredData;
    private List<GeneratedFile> generatedFiles;

    @Data
    @Builder
    /**
     * 工具产出文件描述。
     */
    public static class GeneratedFile {
        private String absolutePath;
        private String fileName;
        private long size;
    }
}
