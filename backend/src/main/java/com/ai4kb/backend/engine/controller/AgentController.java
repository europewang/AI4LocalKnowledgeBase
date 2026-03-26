package com.ai4kb.backend.engine.controller;

import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import com.ai4kb.backend.engine.service.EngineOrchestrator;
import com.ai4kb.backend.skill.model.SkillFileRecord;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.ai4kb.backend.skill.service.SkillRegistryService;
import com.ai4kb.backend.skill.service.ToolFileStorageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
/**
 * Agent 对外入口控制器。
 * 提供流式对话、工具审批、工具目录、文件上传与结果下载能力。
 */
public class AgentController {

    private final EngineOrchestrator engineOrchestrator;
    private final ToolFileStorageService toolFileStorageService;
    private final SkillRegistryService skillRegistryService;

    /**
     * 发起 Agent 流式对话。
     * 支持分析计划重跑参数（editedSteps/rerunMode/restartFromStep/replanOnly）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        Long userId = requireCurrentUser().getUserId();
        return engineOrchestrator.processAdvanced(
                request.getConversationId(),
                userId,
                request.getQuery(),
                request.getAdjustmentInstruction(),
                request.getEditedSteps(),
                request.getRerunMode(),
                request.getRestartFromStep(),
                Boolean.TRUE.equals(request.getReplanOnly())
        );
    }

    /**
     * 提交工具审批并恢复执行。
     */
    @PostMapping(value = "/tool/approve", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> approveTool(@RequestBody ToolApproveRequest request) {
        return engineOrchestrator.processToolApproval(
                request.getConversationId(), 
                request.getToolCallId(), 
                request.getReviewedArgs()
        );
    }

    /**
     * 获取当前用户可用工具目录。
     */
    @GetMapping("/tool/catalog")
    public List<ToolSpec> getToolCatalog() {
        return skillRegistryService.getAvailableTools(requireCurrentUser().getUserId());
    }

    /**
     * 上传工具输入文件（如 CAD、附件等），并绑定到 toolCallId。
     */
    @PostMapping(value = "/tool/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadToolFile(@RequestParam String toolCallId, @RequestPart("file") MultipartFile file) throws Exception {
        SkillFileRecord record = toolFileStorageService.storeInputFile(toolCallId, file);
        return Map.of(
                "file_id", record.getFileId(),
                "file_name", record.getFileName(),
                "size", record.getSize(),
                "tool_call_id", record.getToolCallId()
        );
    }

    /**
     * 查询某次工具调用已上传文件列表。
     */
    @GetMapping("/tool/files")
    public List<Map<String, Object>> listToolFiles(@RequestParam String toolCallId) {
        return toolFileStorageService.listInputFiles(toolCallId).stream().map(record -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file_id", record.getFileId());
            item.put("file_name", record.getFileName());
            item.put("size", record.getSize());
            item.put("created_at", record.getCreatedAt());
            return item;
        }).toList();
    }

    /**
     * 下载工具执行输出文件。
     */
    @GetMapping("/tool/result/{fileId}")
    public ResponseEntity<Resource> downloadToolResult(@PathVariable String fileId) {
        SkillFileRecord record = toolFileStorageService.getFileRecord(fileId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = toolFileStorageService.getFileResource(fileId);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        String encoded = URLEncoder.encode(record.getFileName(), StandardCharsets.UTF_8);
        String disposition = "attachment; filename*=UTF-8''" + encoded;
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (record.getContentType() != null && !record.getContentType().isBlank()) {
            try {
                mediaType = MediaType.parseMediaType(record.getContentType());
            } catch (Exception ignored) {
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(mediaType)
                .contentLength(record.getSize())
                .body(resource);
    }

    @Data
    /**
     * 对话请求体。
     * 支持重规划、步骤编辑与从指定步骤重跑。
     */
    public static class ChatRequest {
        private String conversationId;
        private String query;
        private String adjustmentInstruction;
        private List<Map<String, Object>> editedSteps;
        private String rerunMode;
        private Integer restartFromStep;
        private Boolean replanOnly;
    }

    @Data
    /**
     * 工具审批请求体。
     * 由前端回传审核后的参数 JSON。
     */
    public static class ToolApproveRequest {
        private String conversationId;
        private String toolCallId;
        private String reviewedArgs;
    }

    /**
     * 获取当前认证用户。
     */
    private AuthenticatedUser requireCurrentUser() {
        AuthenticatedUser user = AuthContextHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new IllegalStateException("认证上下文缺失");
        }
        return user;
    }
}
