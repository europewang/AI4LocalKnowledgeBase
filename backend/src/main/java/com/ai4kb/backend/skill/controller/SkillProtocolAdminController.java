package com.ai4kb.backend.skill.controller;

import com.ai4kb.backend.skill.entity.DynamicSkillCallAudit;
import com.ai4kb.backend.skill.entity.DynamicSkillRegistry;
import com.ai4kb.backend.skill.service.DynamicSkillAuditService;
import com.ai4kb.backend.skill.service.DynamicSkillRegistryService;
import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/admin/skills")
@RequiredArgsConstructor
/**
 * 技能协议管理控制器。
 * 提供动态技能注册、列表查询、上下线与调用审计查询接口。
 */
public class SkillProtocolAdminController {

    private final DynamicSkillRegistryService dynamicSkillRegistryService;
    private final DynamicSkillAuditService dynamicSkillAuditService;
    private final UserMapper userMapper;

    /**
     * 注册或更新动态技能。
     */
    @PostMapping("/register")
    public Map<String, Object> registerSkill(@RequestBody RegisterSkillRequest request) {
        AuthenticatedUser current = requireSuperAdminUser();
        DynamicSkillRegistry registry = new DynamicSkillRegistry();
        registry.setToolCode(request.getToolCode());
        registry.setToolName(request.getToolName());
        registry.setDescription(request.getDescription());
        registry.setProtocolType(request.getProtocolType());
        registry.setInvokeUrl(request.getInvokeUrl());
        registry.setManifestUrl(request.getManifestUrl());
        registry.setHealthUrl(request.getHealthUrl());
        registry.setTriggerKeywords(request.getTriggerKeywords());
        registry.setInputMode(request.getInputMode());
        registry.setOutputMode(request.getOutputMode());
        registry.setUploadRequired(Boolean.TRUE.equals(request.getUploadRequired()) ? 1 : 0);
        registry.setAcceptedFileTypes(request.getAcceptedFileTypes());
        registry.setMaxFiles(request.getMaxFiles());
        registry.setParametersSchema(request.getParametersSchema());
        registry.setDraftArgsTemplate(request.getDraftArgsTemplate());
        registry.setStatus(request.getStatus());
        DynamicSkillRegistry saved = dynamicSkillRegistryService.upsert(registry, current.getUserId());
        return Map.of(
                "status", "ok",
                "tool_code", saved.getToolCode(),
                "version", saved.getVersion(),
                "skill_id", saved.getId()
        );
    }

    /**
     * 查询技能列表。
     */
    @GetMapping
    public List<Map<String, Object>> listSkills(@RequestParam(defaultValue = "false") boolean onlineOnly) {
        requireSuperAdminUser();
        List<DynamicSkillRegistry> skills = onlineOnly ? dynamicSkillRegistryService.listOnlineSkills() : dynamicSkillRegistryService.listAllSkills();
        return skills.stream().map(this::toSkillItem).toList();
    }

    /**
     * 下线技能。
     */
    @PostMapping("/{toolCode}/offline")
    public Map<String, Object> offlineSkill(@PathVariable String toolCode) {
        AuthenticatedUser current = requireSuperAdminUser();
        dynamicSkillRegistryService.offlineSkill(toolCode, current.getUserId());
        return Map.of("status", "ok", "tool_code", toolCode, "new_status", "OFFLINE");
    }

    @PostMapping("/{toolCode}/online")
    public Map<String, Object> onlineSkill(@PathVariable String toolCode) {
        AuthenticatedUser current = requireSuperAdminUser();
        dynamicSkillRegistryService.onlineSkill(toolCode, current.getUserId());
        return Map.of("status", "ok", "tool_code", toolCode, "new_status", "ONLINE");
    }

    @DeleteMapping("/{toolCode}")
    public Map<String, Object> deleteSkill(@PathVariable String toolCode) {
        requireSuperAdminUser();
        dynamicSkillRegistryService.deleteSkill(toolCode);
        return Map.of("status", "ok", "tool_code", toolCode, "deleted", true);
    }

    /**
     * 查询技能调用审计。
     */
    @GetMapping("/audit")
    public Map<String, Object> listAudit(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int pageSize,
                                         @RequestParam(required = false) String startTime,
                                         @RequestParam(required = false) String endTime,
                                         @RequestParam(required = false) String toolCallId,
                                         @RequestParam(required = false) String username,
                                         @RequestParam(required = false) Long userId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String toolCode) {
        AuthenticatedUser current = requireAdminLikeUser();
        Set<Long> scopedUserIds = buildAuditScopeUserIds(current);
        if (!"super_admin".equalsIgnoreCase(current.getRole()) && scopedUserIds.isEmpty()) {
            return Map.of(
                    "items", List.of(),
                    "total", 0,
                    "page", Math.max(page, 1),
                    "page_size", Math.max(1, Math.min(pageSize, 100)),
                    "has_more", false
            );
        }
        DynamicSkillAuditService.AuditQuery query = new DynamicSkillAuditService.AuditQuery(
                page,
                pageSize,
                parseDateTime(startTime, false),
                parseDateTime(endTime, true),
                toolCallId,
                username,
                userId,
                status,
                toolCode,
                scopedUserIds
        );
        DynamicSkillAuditService.AuditPageResult result = dynamicSkillAuditService.pageAudits(query);
        return Map.of(
                "items", result.items().stream().map(this::toAuditItem).toList(),
                "total", result.total(),
                "page", result.page(),
                "page_size", result.pageSize(),
                "has_more", result.hasMore()
        );
    }

    @GetMapping("/audit/options")
    public Map<String, Object> listAuditOptions(@RequestParam(defaultValue = "300") int cap) {
        AuthenticatedUser current = requireAdminLikeUser();
        DynamicSkillAuditService.AuditFilterOptions options = dynamicSkillAuditService.listFilterOptions(cap, buildAuditScopeUserIds(current));
        return Map.of(
                "statuses", options.statuses(),
                "usernames", options.usernames(),
                "tool_call_ids", options.toolCallIds(),
                "tool_codes", options.toolCodes()
        );
    }

    private Map<String, Object> toSkillItem(DynamicSkillRegistry skill) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", skill.getId());
        item.put("tool_code", skill.getToolCode());
        item.put("tool_name", skill.getToolName());
        item.put("description", skill.getDescription());
        item.put("protocol_type", skill.getProtocolType());
        item.put("invoke_url", skill.getInvokeUrl());
        item.put("manifest_url", skill.getManifestUrl());
        item.put("health_url", skill.getHealthUrl());
        item.put("trigger_keywords", skill.getTriggerKeywords());
        item.put("input_mode", skill.getInputMode());
        item.put("output_mode", skill.getOutputMode());
        item.put("upload_required", skill.getUploadRequired());
        item.put("accepted_file_types", skill.getAcceptedFileTypes());
        item.put("max_files", skill.getMaxFiles());
        item.put("parameters_schema", skill.getParametersSchema());
        item.put("draft_args_template", skill.getDraftArgsTemplate());
        item.put("status", skill.getStatus());
        item.put("version", skill.getVersion());
        item.put("updated_at", skill.getUpdatedAt());
        return item;
    }

    private Map<String, Object> toAuditItem(DynamicSkillCallAudit audit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", audit.getId());
        item.put("trace_id", audit.getTraceId());
        item.put("conversation_id", audit.getConversationId());
        item.put("tool_call_id", audit.getToolCallId());
        item.put("tool_code", audit.getToolCode());
        item.put("tool_name", audit.getToolName());
        item.put("user_id", audit.getUserId());
        item.put("username", audit.getUsername());
        item.put("user_role", audit.getUserRole());
        item.put("status", audit.getStatus());
        item.put("latency_ms", audit.getLatencyMs());
        item.put("error_message", audit.getErrorMessage());
        item.put("created_at", audit.getCreatedAt());
        return item;
    }

    private AuthenticatedUser requireAdminLikeUser() {
        AuthenticatedUser user = AuthContextHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new IllegalStateException("认证上下文缺失");
        }
        String role = user.getRole() == null ? "" : user.getRole().trim();
        if (!"admin".equalsIgnoreCase(role) && !"super_admin".equalsIgnoreCase(role)) {
            throw new IllegalStateException("仅 admin/super_admin 可执行该操作");
        }
        return user;
    }

    private AuthenticatedUser requireSuperAdminUser() {
        AuthenticatedUser user = requireAdminLikeUser();
        if (!"super_admin".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅 super_admin 可执行该操作");
        }
        return user;
    }

    private Set<Long> buildAuditScopeUserIds(AuthenticatedUser current) {
        if (current == null || current.getUserId() == null) {
            return Set.of();
        }
        if ("super_admin".equalsIgnoreCase(current.getRole())) {
            return null;
        }
        List<User> managedUsers = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getManagerUserId, current.getUserId())
                .eq(User::getRole, "user"));
        java.util.LinkedHashSet<Long> scope = new java.util.LinkedHashSet<>();
        scope.add(current.getUserId());
        if (managedUsers != null) {
            for (User item : managedUsers) {
                if (item != null && item.getId() != null) {
                    scope.add(item.getId());
                }
            }
        }
        return scope.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
    }

    private LocalDateTime parseDateTime(String raw, boolean endOfDayIfDateOnly) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDate date = LocalDate.parse(text);
            return endOfDayIfDateOnly ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    @Data
    /**
     * 动态技能注册请求体。
     */
    public static class RegisterSkillRequest {
        private String toolCode;
        private String toolName;
        private String description;
        private String protocolType;
        private String invokeUrl;
        private String manifestUrl;
        private String healthUrl;
        private String triggerKeywords;
        private String inputMode;
        private String outputMode;
        private Boolean uploadRequired;
        private String acceptedFileTypes;
        private Integer maxFiles;
        private String parametersSchema;
        private String draftArgsTemplate;
        private String status;
    }
}
