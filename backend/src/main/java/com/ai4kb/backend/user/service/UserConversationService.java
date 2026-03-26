package com.ai4kb.backend.user.service;

import com.ai4kb.backend.user.entity.UserConversation;
import com.ai4kb.backend.user.entity.UserConversationMessage;
import com.ai4kb.backend.user.mapper.UserConversationMapper;
import com.ai4kb.backend.user.mapper.UserConversationMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserConversationService {
    private final UserConversationMapper userConversationMapper;
    private final UserConversationMessageMapper userConversationMessageMapper;

    public UserConversation createConversation(Long userId, String title) {
        UserConversation conversation = new UserConversation();
        conversation.setId("conv-" + UUID.randomUUID());
        conversation.setUserId(userId);
        conversation.setTitle(normalizeTitle(title));
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        userConversationMapper.insert(conversation);
        return conversation;
    }

    public List<UserConversation> listUserConversations(Long userId) {
        return userConversationMapper.selectList(new LambdaQueryWrapper<UserConversation>()
                .eq(UserConversation::getUserId, userId)
                .orderByDesc(UserConversation::getUpdatedAt));
    }

    public UserConversation ensureConversation(Long userId, String conversationId, String fallbackTitle) {
        UserConversation exists = userConversationMapper.selectById(conversationId);
        if (exists != null) {
            if (!exists.getUserId().equals(userId)) {
                throw new IllegalStateException("会话无访问权限");
            }
            return exists;
        }
        UserConversation conversation = new UserConversation();
        conversation.setId(conversationId);
        conversation.setUserId(userId);
        conversation.setTitle(normalizeTitle(fallbackTitle));
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        userConversationMapper.insert(conversation);
        return conversation;
    }

    public UserConversation requireOwnedConversation(Long userId, String conversationId) {
        UserConversation conversation = userConversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalStateException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalStateException("会话无访问权限");
        }
        return conversation;
    }

    public UserConversationMessage appendMessage(Long userId, String conversationId, String role, String content, String messagePayload) {
        requireOwnedConversation(userId, conversationId);
        UserConversationMessage message = new UserConversationMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role == null ? "user" : role);
        message.setContent(content == null ? "" : content);
        message.setMessagePayload(messagePayload == null ? "" : messagePayload);
        message.setCreatedAt(LocalDateTime.now());
        userConversationMessageMapper.insert(message);
        userConversationMapper.updateById(buildTouchConversation(conversationId));
        return message;
    }

    public List<UserConversationMessage> listConversationMessages(Long userId, String conversationId) {
        requireOwnedConversation(userId, conversationId);
        return userConversationMessageMapper.selectList(new LambdaQueryWrapper<UserConversationMessage>()
                .eq(UserConversationMessage::getConversationId, conversationId)
                .orderByAsc(UserConversationMessage::getId));
    }

    public UserConversation renameConversation(Long userId, String conversationId, String title) {
        UserConversation conversation = requireOwnedConversation(userId, conversationId);
        conversation.setTitle(normalizeTitle(title));
        conversation.setUpdatedAt(LocalDateTime.now());
        userConversationMapper.updateById(conversation);
        return conversation;
    }

    public void deleteConversation(Long userId, String conversationId) {
        requireOwnedConversation(userId, conversationId);
        userConversationMessageMapper.delete(new LambdaQueryWrapper<UserConversationMessage>()
                .eq(UserConversationMessage::getConversationId, conversationId));
        userConversationMapper.deleteById(conversationId);
    }

    private UserConversation buildTouchConversation(String conversationId) {
        UserConversation conversation = new UserConversation();
        conversation.setId(conversationId);
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "新建会话";
        }
        return title.length() > 120 ? title.substring(0, 120) : title;
    }
}
