package com.ai4kb.backend.skill.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SkillFileRecord {
    private String fileId;
    private String toolCallId;
    private String fileName;
    private String contentType;
    private long size;
    private String absolutePath;
    private String role;
    private LocalDateTime createdAt;
}
