package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.entity.DynamicSkillRegistry;
import com.ai4kb.backend.skill.mapper.DynamicSkillRegistryMapper;
import com.ai4kb.backend.skill.model.ToolSpec;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
/**
 * 动态技能注册服务。
 * 负责技能注册、上下线、在线列表查询与 ToolSpec 映射。
 */
public class DynamicSkillRegistryService {

    private final DynamicSkillRegistryMapper dynamicSkillRegistryMapper;
    private final ObjectMapper objectMapper;

    /**
     * 注册或更新动态技能。
     * 当 toolCode 已存在时执行覆盖更新并递增版本号。
     */
    public DynamicSkillRegistry upsert(DynamicSkillRegistry skill, Long operatorUserId) {
        if (skill == null || isBlank(skill.getToolCode()) || isBlank(skill.getInvokeUrl())) {
            throw new IllegalArgumentException("tool_code 与 invoke_url 不能为空");
        }
        DynamicSkillRegistry existing = findByToolCode(skill.getToolCode()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            skill.setStatus(normalizeStatus(skill.getStatus()));
            skill.setVersion(skill.getVersion() == null || skill.getVersion() < 1 ? 1 : skill.getVersion());
            skill.setCreatedBy(operatorUserId);
            skill.setUpdatedBy(operatorUserId);
            skill.setCreatedAt(now);
            skill.setUpdatedAt(now);
            if ("ONLINE".equals(skill.getStatus())) {
                skill.setLastOnlineAt(now);
            }
            dynamicSkillRegistryMapper.insert(skill);
            return skill;
        }
        existing.setToolName(defaultString(skill.getToolName(), existing.getToolName()));
        existing.setDescription(defaultString(skill.getDescription(), existing.getDescription()));
        existing.setProtocolType(defaultString(skill.getProtocolType(), existing.getProtocolType()));
        existing.setInvokeUrl(defaultString(skill.getInvokeUrl(), existing.getInvokeUrl()));
        existing.setManifestUrl(defaultString(skill.getManifestUrl(), existing.getManifestUrl()));
        existing.setHealthUrl(defaultString(skill.getHealthUrl(), existing.getHealthUrl()));
        existing.setTriggerKeywords(defaultString(skill.getTriggerKeywords(), existing.getTriggerKeywords()));
        existing.setInputMode(defaultString(skill.getInputMode(), existing.getInputMode()));
        existing.setOutputMode(defaultString(skill.getOutputMode(), existing.getOutputMode()));
        existing.setUploadRequired(skill.getUploadRequired() == null ? existing.getUploadRequired() : skill.getUploadRequired());
        existing.setAcceptedFileTypes(defaultString(skill.getAcceptedFileTypes(), existing.getAcceptedFileTypes()));
        existing.setMaxFiles(skill.getMaxFiles() == null ? existing.getMaxFiles() : skill.getMaxFiles());
        existing.setParametersSchema(defaultString(skill.getParametersSchema(), existing.getParametersSchema()));
        existing.setDraftArgsTemplate(defaultString(skill.getDraftArgsTemplate(), existing.getDraftArgsTemplate()));
        existing.setStatus(normalizeStatus(skill.getStatus()));
        existing.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
        existing.setUpdatedBy(operatorUserId);
        existing.setUpdatedAt(now);
        if ("ONLINE".equals(existing.getStatus())) {
            existing.setLastOnlineAt(now);
        }
        dynamicSkillRegistryMapper.updateById(existing);
        return existing;
    }

    /**
     * 将技能下线。
     */
    public void offlineSkill(String toolCode, Long operatorUserId) {
        DynamicSkillRegistry registry = findByToolCode(toolCode).orElseThrow(() -> new IllegalArgumentException("技能不存在: " + toolCode));
        registry.setStatus("OFFLINE");
        registry.setUpdatedBy(operatorUserId);
        registry.setUpdatedAt(LocalDateTime.now());
        dynamicSkillRegistryMapper.updateById(registry);
    }

    public void onlineSkill(String toolCode, Long operatorUserId) {
        DynamicSkillRegistry registry = findByToolCode(toolCode).orElseThrow(() -> new IllegalArgumentException("技能不存在: " + toolCode));
        LocalDateTime now = LocalDateTime.now();
        registry.setStatus("ONLINE");
        registry.setUpdatedBy(operatorUserId);
        registry.setUpdatedAt(now);
        registry.setLastOnlineAt(now);
        dynamicSkillRegistryMapper.updateById(registry);
    }

    public void deleteSkill(String toolCode) {
        DynamicSkillRegistry registry = findByToolCode(toolCode).orElseThrow(() -> new IllegalArgumentException("技能不存在: " + toolCode));
        dynamicSkillRegistryMapper.deleteById(registry.getId());
    }

    /**
     * 查询在线技能列表。
     */
    public List<DynamicSkillRegistry> listOnlineSkills() {
        return dynamicSkillRegistryMapper.selectList(new LambdaQueryWrapper<DynamicSkillRegistry>()
                .eq(DynamicSkillRegistry::getStatus, "ONLINE")
                .orderByAsc(DynamicSkillRegistry::getId));
    }

    /**
     * 查询全部技能列表。
     */
    public List<DynamicSkillRegistry> listAllSkills() {
        return dynamicSkillRegistryMapper.selectList(new LambdaQueryWrapper<DynamicSkillRegistry>()
                .orderByAsc(DynamicSkillRegistry::getId));
    }

    /**
     * 按 toolCode 查询技能。
     */
    public Optional<DynamicSkillRegistry> findByToolCode(String toolCode) {
        if (isBlank(toolCode)) {
            return Optional.empty();
        }
        DynamicSkillRegistry registry = dynamicSkillRegistryMapper.selectOne(new LambdaQueryWrapper<DynamicSkillRegistry>()
                .eq(DynamicSkillRegistry::getToolCode, toolCode)
                .last("limit 1"));
        return Optional.ofNullable(registry);
    }

    /**
     * 按 toolCode 查询在线技能。
     */
    public Optional<DynamicSkillRegistry> findOnlineByToolCode(String toolCode) {
        if (isBlank(toolCode)) {
            return Optional.empty();
        }
        DynamicSkillRegistry registry = dynamicSkillRegistryMapper.selectOne(new LambdaQueryWrapper<DynamicSkillRegistry>()
                .eq(DynamicSkillRegistry::getToolCode, toolCode)
                .eq(DynamicSkillRegistry::getStatus, "ONLINE")
                .last("limit 1"));
        return Optional.ofNullable(registry);
    }

    /**
     * 把动态技能映射为 ToolSpec，供编排器与前端目录统一消费。
     */
    public ToolSpec toToolSpec(DynamicSkillRegistry registry) {
        List<String> triggerKeywords = splitCsv(registry.getTriggerKeywords());
        List<String> acceptedFileTypes = splitCsv(registry.getAcceptedFileTypes());
        Map<String, Object> schema = parseJsonMap(registry.getParametersSchema());
        return ToolSpec.builder()
                .name(registry.getToolCode())
                // 前端快捷技能区优先展示 toolName/displayName，避免回退到 tool_code。
                .toolName(defaultString(registry.getToolName(), registry.getToolCode()))
                .displayName(defaultString(registry.getToolName(), registry.getToolCode()))
                .description(defaultString(registry.getDescription(), registry.getToolName()))
                .triggerKeywords(triggerKeywords)
                .inputMode(defaultString(registry.getInputMode(), "PARAMS_ONLY"))
                .outputMode(defaultString(registry.getOutputMode(), "MIXED"))
                .uploadRequired(registry.getUploadRequired() != null && registry.getUploadRequired() == 1)
                .acceptedFileTypes(acceptedFileTypes)
                .maxFiles(registry.getMaxFiles())
                .parametersSchema(schema)
                .build();
    }

    /**
     * 解析技能默认参数草稿。
     */
    public Map<String, Object> parseDraftArgsTemplate(DynamicSkillRegistry registry, String query) {
        Map<String, Object> parsed = parseJsonMap(registry.getDraftArgsTemplate());
        if (!parsed.containsKey("query")) {
            parsed.put("query", query == null ? "" : query);
        }
        return parsed;
    }

    private Map<String, Object> parseJsonMap(String raw) {
        if (isBlank(raw)) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception ex) {
            return new java.util.LinkedHashMap<>();
        }
    }

    private List<String> splitCsv(String csv) {
        if (isBlank(csv)) {
            return Collections.emptyList();
        }
        return List.of(csv.split(",")).stream()
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .toList();
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "ONLINE" : status.trim().toUpperCase(Locale.ROOT);
        if (!"ONLINE".equals(normalized) && !"OFFLINE".equals(normalized)) {
            return "ONLINE";
        }
        return normalized;
    }

    private String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
