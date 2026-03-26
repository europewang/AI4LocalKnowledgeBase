package com.ai4kb.backend.skill.executor;

import com.ai4kb.backend.skill.model.ToolExecutionRequest;
import com.ai4kb.backend.skill.model.ToolExecutionResult;
import com.ai4kb.backend.skill.model.ToolSpec;

import java.util.Map;

public interface SkillExecutor {
    ToolSpec getToolSpec();

    default Map<String, Object> buildDraftArgs(String query) {
        return Map.of("query", query == null ? "" : query);
    }

    ToolExecutionResult execute(ToolExecutionRequest request) throws Exception;
}
