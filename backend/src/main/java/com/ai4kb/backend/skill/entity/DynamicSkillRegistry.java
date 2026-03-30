package com.ai4kb.backend.skill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_skill_registry")
/**
 * 动态技能注册实体。
 * 持久化协议地址、工具描述、参数 Schema 与上线状态，用于运行时动态发现与调用。
 */
public class DynamicSkillRegistry {
    @TableId(type = IdType.AUTO)
    private Long id;
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
    private Integer uploadRequired;
    private String acceptedFileTypes;
    private Integer maxFiles;
    private String parametersSchema;
    private String draftArgsTemplate;
    private String status;
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastOnlineAt;
}
