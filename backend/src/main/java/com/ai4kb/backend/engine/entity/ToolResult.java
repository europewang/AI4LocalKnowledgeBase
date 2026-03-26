package com.ai4kb.backend.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_tool_result")
public class ToolResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    private String toolCallId;

    private String toolName;

    private String argsDigest;

    private String resultDigest;

    private String status; // SUCCESS / FAIL

    private String error;

    private LocalDateTime createdAt;
}
