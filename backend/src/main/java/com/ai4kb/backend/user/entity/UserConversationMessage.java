package com.ai4kb.backend.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_conversation_message")
public class UserConversationMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private Long userId;
    private String role;
    private String content;
    @TableField("message_payload")
    private String messagePayload;
    private LocalDateTime createdAt;
}
