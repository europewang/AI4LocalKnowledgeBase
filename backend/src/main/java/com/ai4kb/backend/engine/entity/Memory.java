package com.ai4kb.backend.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_memory")
public class Memory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;
    
    private Long userId;

    private String memoryType; // SUMMARY, DECISION, FACT, TOOL_RESULT

    private String content;

    private Float salience;

    private Boolean pinned;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
