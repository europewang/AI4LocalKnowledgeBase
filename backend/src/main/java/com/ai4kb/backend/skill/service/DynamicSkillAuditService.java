package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.entity.DynamicSkillCallAudit;
import com.ai4kb.backend.skill.mapper.DynamicSkillCallAuditMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
/**
 * 动态技能调用审计服务。
 * 提供调用开始、成功、失败写入与审计查询能力。
 */
public class DynamicSkillAuditService {

    private final DynamicSkillCallAuditMapper dynamicSkillCallAuditMapper;

    /**
     * 写入调用开始审计记录。
     */
    public DynamicSkillCallAudit startAudit(String conversationId,
                                            String toolCallId,
                                            String toolCode,
                                            String toolName,
                                            Long userId,
                                            String username,
                                            String userRole,
                                            String requestPayload) {
        DynamicSkillCallAudit audit = new DynamicSkillCallAudit();
        audit.setTraceId("trace-" + UUID.randomUUID());
        audit.setConversationId(conversationId);
        audit.setToolCallId(toolCallId);
        audit.setToolCode(toolCode);
        audit.setToolName(toolName);
        audit.setUserId(userId);
        audit.setUsername(username);
        audit.setUserRole(userRole);
        audit.setRequestPayload(requestPayload);
        audit.setStatus("RUNNING");
        audit.setStartedAt(LocalDateTime.now());
        audit.setCreatedAt(LocalDateTime.now());
        dynamicSkillCallAuditMapper.insert(audit);
        return audit;
    }

    /**
     * 标记调用成功。
     */
    public void finishSuccess(Long id, String responsePayload, long latencyMs) {
        DynamicSkillCallAudit audit = dynamicSkillCallAuditMapper.selectById(id);
        if (audit == null) {
            return;
        }
        audit.setStatus("SUCCESS");
        audit.setResponsePayload(responsePayload);
        audit.setLatencyMs(latencyMs);
        audit.setFinishedAt(LocalDateTime.now());
        dynamicSkillCallAuditMapper.updateById(audit);
    }

    /**
     * 标记调用失败。
     */
    public void finishFailed(Long id, String errorMessage, String responsePayload, long latencyMs) {
        DynamicSkillCallAudit audit = dynamicSkillCallAuditMapper.selectById(id);
        if (audit == null) {
            return;
        }
        audit.setStatus("FAILED");
        audit.setErrorMessage(errorMessage);
        audit.setResponsePayload(responsePayload);
        audit.setLatencyMs(latencyMs);
        audit.setFinishedAt(LocalDateTime.now());
        dynamicSkillCallAuditMapper.updateById(audit);
    }

    /**
     * 查询最近审计记录。
     */
    public List<DynamicSkillCallAudit> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return dynamicSkillCallAuditMapper.selectList(new LambdaQueryWrapper<DynamicSkillCallAudit>()
                .orderByDesc(DynamicSkillCallAudit::getId)
                .last("limit " + safeLimit));
    }

    public AuditPageResult pageAudits(AuditQuery query) {
        AuditQuery safeQuery = query == null ? new AuditQuery() : query;
        int safePage = Math.max(1, safeQuery.page());
        int safePageSize = Math.max(1, Math.min(safeQuery.pageSize(), 100));
        int offset = (safePage - 1) * safePageSize;
        LambdaQueryWrapper<DynamicSkillCallAudit> wrapper = new LambdaQueryWrapper<DynamicSkillCallAudit>()
                .orderByDesc(DynamicSkillCallAudit::getCreatedAt)
                .orderByDesc(DynamicSkillCallAudit::getId);
        if (safeQuery.startTime() != null) {
            wrapper.ge(DynamicSkillCallAudit::getCreatedAt, safeQuery.startTime());
        }
        if (safeQuery.endTime() != null) {
            wrapper.le(DynamicSkillCallAudit::getCreatedAt, safeQuery.endTime());
        }
        if (safeQuery.userId() != null) {
            wrapper.eq(DynamicSkillCallAudit::getUserId, safeQuery.userId());
        }
        if (safeQuery.toolCallId() != null && !safeQuery.toolCallId().isBlank()) {
            wrapper.like(DynamicSkillCallAudit::getToolCallId, safeQuery.toolCallId().trim());
        }
        if (safeQuery.username() != null && !safeQuery.username().isBlank()) {
            wrapper.like(DynamicSkillCallAudit::getUsername, safeQuery.username().trim());
        }
        if (safeQuery.status() != null && !safeQuery.status().isBlank()) {
            wrapper.eq(DynamicSkillCallAudit::getStatus, safeQuery.status().trim().toUpperCase(Locale.ROOT));
        }
        if (safeQuery.toolCode() != null && !safeQuery.toolCode().isBlank()) {
            wrapper.eq(DynamicSkillCallAudit::getToolCode, safeQuery.toolCode().trim());
        }
        long total = dynamicSkillCallAuditMapper.selectCount(wrapper);
        // 使用手工分页，规避分页插件在部分运行链路下不生效的问题。
        wrapper.last("limit " + safePageSize + " offset " + offset);
        List<DynamicSkillCallAudit> items = dynamicSkillCallAuditMapper.selectList(wrapper);
        return new AuditPageResult(
                items == null ? List.of() : items,
                total,
                safePage,
                safePageSize,
                safePage * safePageSize < total
        );
    }

    public AuditFilterOptions listFilterOptions(int cap) {
        int safeCap = Math.max(20, Math.min(cap, 1000));
        List<String> statuses = listDistinctStrings("status", safeCap, true);
        List<String> usernames = listDistinctStrings("username", safeCap, false);
        List<String> toolCallIds = listDistinctStrings("tool_call_id", safeCap, false);
        List<String> toolCodes = listDistinctStrings("tool_code", safeCap, false);
        return new AuditFilterOptions(statuses, usernames, toolCallIds, toolCodes);
    }

    /**
     * 删除指定时间点之前的审计记录，用于审计数据定期清理。
     */
    public int deleteBefore(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return dynamicSkillCallAuditMapper.delete(new LambdaQueryWrapper<DynamicSkillCallAudit>()
                .lt(DynamicSkillCallAudit::getCreatedAt, cutoff));
    }

    private List<String> listDistinctStrings(String columnName, int cap, boolean upperCase) {
        QueryWrapper<DynamicSkillCallAudit> wrapper = new QueryWrapper<DynamicSkillCallAudit>()
                .select("distinct " + columnName)
                .isNotNull(columnName)
                .ne(columnName, "")
                .last("limit " + cap);
        List<Object> values = dynamicSkillCallAuditMapper.selectObjs(wrapper);
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            result.add(upperCase ? text.toUpperCase(Locale.ROOT) : text);
        }
        return result.stream().filter(Objects::nonNull).distinct().toList();
    }

    public record AuditQuery(
            int page,
            int pageSize,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String toolCallId,
            String username,
            Long userId,
            String status,
            String toolCode
    ) {
        public AuditQuery() {
            this(1, 20, null, null, null, null, null, null, null);
        }
    }

    public record AuditPageResult(
            List<DynamicSkillCallAudit> items,
            long total,
            int page,
            int pageSize,
            boolean hasMore
    ) {
    }

    public record AuditFilterOptions(
            List<String> statuses,
            List<String> usernames,
            List<String> toolCallIds,
            List<String> toolCodes
    ) {
    }
}
