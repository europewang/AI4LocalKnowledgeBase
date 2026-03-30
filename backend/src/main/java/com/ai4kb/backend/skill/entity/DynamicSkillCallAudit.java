package com.ai4kb.backend.skill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_skill_call_audit")
/**
 * 动态技能调用审计实体。
 * 记录技能调用请求、调用人、调用状态与错误信息，支持管理员检索追踪。
 */
public class DynamicSkillCallAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String conversationId;
    private String toolCallId;
    private String toolCode;
    private String toolName;
    private Long userId;
    private String username;
    private String userRole;
    private String requestPayload;
    private String responsePayload;
    private String status;
    private String errorMessage;
    private Long latencyMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
