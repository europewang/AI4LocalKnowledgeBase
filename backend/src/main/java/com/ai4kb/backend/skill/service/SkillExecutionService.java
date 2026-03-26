package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.executor.SkillExecutor;
import com.ai4kb.backend.skill.model.SkillFileRecord;
import com.ai4kb.backend.skill.model.ToolExecutionRequest;
import com.ai4kb.backend.skill.model.ToolExecutionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 技能执行编排服务。
 * 负责把审批后的参数、上传文件与具体执行器组装成统一请求，并将结果标准化为前端可渲染结构。
 */
public class SkillExecutionService {

    private final SkillRegistryService skillRegistryService;
    private final ToolFileStorageService toolFileStorageService;
    private final ObjectMapper objectMapper;

    /**
     * 执行指定工具并返回统一结果载荷。
     */
    public Map<String, Object> execute(String conversationId, String toolCallId, String toolName, Long userId, String reviewedArgs) throws Exception {
        SkillExecutor executor = skillRegistryService.getExecutorByToolName(toolName);
        if (executor == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        Map<String, Object> args = parseArgs(reviewedArgs);
        List<SkillFileRecord> inputFiles = toolFileStorageService.listInputFiles(toolCallId);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .conversationId(conversationId)
                .toolCallId(toolCallId)
                .userId(userId)
                .args(args)
                .inputFiles(inputFiles)
                .build();
        ToolExecutionResult result = executor.execute(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", toolName);
        payload.put("success", result.isSuccess());
        payload.put("summary", result.getSummary());
        payload.put("error_message", result.getErrorMessage());
        payload.put("structured_data", result.getStructuredData());
        payload.put("files", registerResultFiles(toolCallId, result.getGeneratedFiles()));
        return payload;
    }

    /**
     * 解析审批后参数 JSON，空参数降级为空 Map。
     */
    private Map<String, Object> parseArgs(String reviewedArgs) throws IOException {
        if (reviewedArgs == null || reviewedArgs.isBlank()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(reviewedArgs, new TypeReference<>() {});
    }

    /**
     * 注册工具输出文件并转换为下载信息列表。
     */
    private List<Map<String, Object>> registerResultFiles(String toolCallId, List<ToolExecutionResult.GeneratedFile> generatedFiles) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        if (generatedFiles == null) {
            return files;
        }
        for (ToolExecutionResult.GeneratedFile file : generatedFiles) {
            Path path = Path.of(file.getAbsolutePath());
            SkillFileRecord record = toolFileStorageService.registerResultFile(toolCallId, path, file.getFileName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file_id", record.getFileId());
            item.put("file_name", record.getFileName());
            item.put("size", record.getSize());
            item.put("download_url", "/api/v1/agent/tool/result/" + record.getFileId());
            files.add(item);
        }
        return files;
    }
}
