package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.entity.DynamicSkillRegistry;
import com.ai4kb.backend.skill.model.SkillFileRecord;
import com.ai4kb.backend.skill.model.ToolExecutionRequest;
import com.ai4kb.backend.skill.model.ToolExecutionResult;
import com.ai4kb.backend.user.auth.JwtTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 动态技能 HTTP 协议调用客户端。
 * 负责把内部执行请求转换为协议请求并解析为统一执行结果。
 */
public class DynamicSkillProtocolClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final JwtTokenService jwtTokenService;

    /**
     * 按注册协议调用技能 invoke 接口。
     */
    public ToolExecutionResult invoke(DynamicSkillRegistry registry, ToolExecutionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversation_id", request.getConversationId());
        payload.put("tool_call_id", request.getToolCallId());
        payload.put("user_id", request.getUserId());
        payload.put("args", request.getArgs());
        payload.put("input_files", convertFiles(request.getInputFiles()));
        String responseBody = webClientBuilder.build()
                .post()
                .uri(registry.getInvokeUrl())
                .header("Authorization", "Bearer " + buildInternalToken(request))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        Map<String, Object> body = parseJson(responseBody);
        boolean success = Boolean.TRUE.equals(body.get("success"));
        String summary = String.valueOf(body.getOrDefault("summary", ""));
        String errorMessage = body.get("error_message") == null ? null : String.valueOf(body.get("error_message"));
        Map<String, Object> structuredData = body.get("structured_data") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        List<ToolExecutionResult.GeneratedFile> generatedFiles = parseGeneratedFiles(body.get("generated_files"));
        return ToolExecutionResult.builder()
                .success(success)
                .summary(summary)
                .errorMessage(errorMessage)
                .structuredData(structuredData)
                .generatedFiles(generatedFiles)
                .build();
    }

    private List<Map<String, Object>> convertFiles(List<SkillFileRecord> files) {
        if (files == null) {
            return List.of();
        }
        return files.stream().map(file -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file_id", file.getFileId());
            item.put("file_name", file.getFileName());
            item.put("absolute_path", file.getAbsolutePath());
            item.put("content_type", file.getContentType());
            item.put("size", file.getSize());
            return item;
        }).toList();
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<ToolExecutionResult.GeneratedFile> parseGeneratedFiles(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(item -> item instanceof Map<?, ?>).map(item -> {
            Map<?, ?> file = (Map<?, ?>) item;
            String absolutePath = file.get("absolute_path") == null ? "" : String.valueOf(file.get("absolute_path"));
            String fileName = file.get("file_name") == null ? "" : String.valueOf(file.get("file_name"));
            long size = toLong(file.get("size"));
            return ToolExecutionResult.GeneratedFile.builder()
                    .absolutePath(absolutePath)
                    .fileName(fileName)
                    .size(size)
                    .build();
        }).toList();
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return 0L;
        }
    }

    private String buildInternalToken(ToolExecutionRequest request) {
        Long userId = request.getUserId() == null ? 0L : request.getUserId();
        String username = "skill-runtime-" + userId;
        String role = "admin";
        return jwtTokenService.generateToken(userId, username, role);
    }
}
