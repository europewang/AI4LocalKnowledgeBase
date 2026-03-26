package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.executor.SkillExecutor;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.ai4kb.backend.user.entity.Permission;
import com.ai4kb.backend.user.mapper.PermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
/**
 * 技能注册与检索服务。
 * 提供“按用户权限筛选工具”“按关键词匹配工具”“构建工具草稿载荷”等能力。
 */
public class SkillRegistryService {

    private final List<SkillExecutor> executors;
    private final PermissionMapper permissionMapper;

    /**
     * 获取用户可用工具列表。
     * 当用户未配置 SKILL 权限记录时，当前实现保持“不过滤”策略。
     */
    public List<ToolSpec> getAvailableTools(Long userId) {
        Set<String> permitted = loadPermittedSkillNames(userId);
        return executors.stream()
                .map(SkillExecutor::getToolSpec)
                .filter(spec -> permitted.isEmpty() || permitted.contains(spec.getName()))
                .toList();
    }

    /**
     * 基于 triggerKeywords 做轻量关键词匹配，返回首个命中工具。
     */
    public Optional<ToolSpec> matchTool(String query, Long userId) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (ToolSpec spec : getAvailableTools(userId)) {
            List<String> keywords = spec.getTriggerKeywords();
            if (keywords == null || keywords.isEmpty()) {
                continue;
            }
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return Optional.of(spec);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 按工具名查找执行器实例。
     */
    public SkillExecutor getExecutorByToolName(String toolName) {
        return executors.stream()
                .filter(e -> e.getToolSpec().getName().equals(toolName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 组装 tool_draft 事件所需载荷。
     */
    public Map<String, Object> buildDraftPayload(ToolSpec spec, String query) {
        SkillExecutor executor = getExecutorByToolName(spec.getName());
        Map<String, Object> args = executor == null ? Map.of("query", query == null ? "" : query) : executor.buildDraftArgs(query);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", spec.getName());
        payload.put("description", spec.getDescription());
        payload.put("input_mode", spec.getInputMode());
        payload.put("output_mode", spec.getOutputMode());
        payload.put("upload_required", spec.isUploadRequired());
        payload.put("accepted_file_types", spec.getAcceptedFileTypes());
        payload.put("max_files", spec.getMaxFiles());
        payload.put("parameters_schema", spec.getParametersSchema());
        payload.put("draft_args", args);
        return payload;
    }

    /**
     * 读取用户 SKILL 权限并转换为资源名集合。
     */
    private Set<String> loadPermittedSkillNames(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        List<Permission> permissions = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, userId)
                .eq(Permission::getResourceType, "SKILL"));
        return permissions.stream()
                .map(Permission::getResourceId)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.toSet());
    }
}
