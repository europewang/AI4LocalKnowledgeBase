package com.ai4kb.backend.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallDraft {
    private String toolCallId;
    private String toolName;
    private String draftArgs;
    private String reviewedArgs;
    private String approvalStatus;
    private Map<String, Object> toolSpec;
    private String executionResult;
    private String createdAt;
}
