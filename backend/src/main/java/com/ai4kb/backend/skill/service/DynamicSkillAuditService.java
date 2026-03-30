package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.entity.DynamicSkillCallAudit;
import com.ai4kb.backend.skill.mapper.DynamicSkillCallAuditMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
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
}
