package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.entity.DynamicSkillRegistry;
import com.ai4kb.backend.skill.executor.SkillExecutor;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.ai4kb.backend.user.entity.Permission;
import com.ai4kb.backend.user.mapper.PermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
/**
 * 技能注册与检索服务。
 * 提供“按用户权限筛选工具”“按关键词匹配工具”“构建工具草稿载荷”等能力。
 */
public class SkillRegistryService {

    private final PermissionMapper permissionMapper;
    private final DynamicSkillRegistryService dynamicSkillRegistryService;

    @Autowired
    public SkillRegistryService(PermissionMapper permissionMapper, DynamicSkillRegistryService dynamicSkillRegistryService) {
        this.permissionMapper = permissionMapper;
        this.dynamicSkillRegistryService = dynamicSkillRegistryService;
    }

    /**
     * 仅供单元测试使用的兼容构造函数。
     * 生产环境使用 Spring 注入动态注册服务，不会走该分支。
     */
    public SkillRegistryService(List<SkillExecutor> executors, PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
        this.dynamicSkillRegistryService = new TestExecutorBridgeDynamicSkillRegistryService(executors);
    }

    /**
     * 获取用户可用工具列表。
     * 当用户未配置 SKILL 权限记录时，当前实现保持“不过滤”策略。
     */
    public List<ToolSpec> getAvailableTools(Long userId) {
        Set<String> permitted = loadPermittedSkillNames(userId);
        return dynamicSkillRegistryService.listOnlineSkills().stream()
                .map(dynamicSkillRegistryService::toToolSpec)
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

    public Optional<ToolSpec> findAvailableToolByCode(Long userId, String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return Optional.empty();
        }
        String normalized = toolCode.trim();
        return getAvailableTools(userId).stream()
                .filter(spec -> normalized.equals(spec.getName()))
                .findFirst();
    }

    /**
     * 组装 tool_draft 事件所需载荷。
     */
    public Map<String, Object> buildDraftPayload(ToolSpec spec, String query) {
        DynamicSkillRegistry registry = dynamicSkillRegistryService.findOnlineByToolCode(spec.getName()).orElse(null);
        Map<String, Object> args = registry == null
                ? Map.of("query", query == null ? "" : query)
                : dynamicSkillRegistryService.parseDraftArgsTemplate(registry, query);
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

    private static class TestExecutorBridgeDynamicSkillRegistryService extends DynamicSkillRegistryService {
        private final Map<String, SkillExecutor> executorMap = new ConcurrentHashMap<>();

        TestExecutorBridgeDynamicSkillRegistryService(List<SkillExecutor> executors) {
            super(null, null);
            if (executors == null) {
                return;
            }
            for (SkillExecutor executor : executors) {
                if (executor == null || executor.getToolSpec() == null || executor.getToolSpec().getName() == null) {
                    continue;
                }
                executorMap.put(executor.getToolSpec().getName(), executor);
            }
        }

        @Override
        public List<DynamicSkillRegistry> listOnlineSkills() {
            return executorMap.values().stream().map(executor -> {
                ToolSpec spec = executor.getToolSpec();
                DynamicSkillRegistry registry = new DynamicSkillRegistry();
                registry.setToolCode(spec.getName());
                registry.setToolName(spec.getName());
                registry.setDescription(spec.getDescription());
                registry.setTriggerKeywords(String.join(",", spec.getTriggerKeywords() == null ? List.of() : spec.getTriggerKeywords()));
                registry.setInputMode(spec.getInputMode());
                registry.setOutputMode(spec.getOutputMode());
                registry.setUploadRequired(spec.isUploadRequired() ? 1 : 0);
                registry.setAcceptedFileTypes(String.join(",", spec.getAcceptedFileTypes() == null ? List.of() : spec.getAcceptedFileTypes()));
                registry.setMaxFiles(spec.getMaxFiles());
                registry.setStatus("ONLINE");
                return registry;
            }).toList();
        }

        @Override
        public Optional<DynamicSkillRegistry> findOnlineByToolCode(String toolCode) {
            SkillExecutor executor = executorMap.get(toolCode);
            if (executor == null) {
                return Optional.empty();
            }
            ToolSpec spec = executor.getToolSpec();
            DynamicSkillRegistry registry = new DynamicSkillRegistry();
            registry.setToolCode(spec.getName());
            registry.setToolName(spec.getName());
            registry.setDescription(spec.getDescription());
            registry.setInputMode(spec.getInputMode());
            registry.setOutputMode(spec.getOutputMode());
            registry.setUploadRequired(spec.isUploadRequired() ? 1 : 0);
            registry.setAcceptedFileTypes(String.join(",", spec.getAcceptedFileTypes() == null ? List.of() : spec.getAcceptedFileTypes()));
            registry.setMaxFiles(spec.getMaxFiles());
            registry.setStatus("ONLINE");
            return Optional.of(registry);
        }
    }
}
