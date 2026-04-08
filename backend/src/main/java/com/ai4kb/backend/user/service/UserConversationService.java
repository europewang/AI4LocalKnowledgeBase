package com.ai4kb.backend.user.service;

import com.ai4kb.backend.user.entity.UserConversation;
import com.ai4kb.backend.user.entity.UserConversationMessage;
import com.ai4kb.backend.user.mapper.UserConversationMapper;
import com.ai4kb.backend.user.mapper.UserConversationMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserConversationService {
    private final UserConversationMapper userConversationMapper;
    private final UserConversationMessageMapper userConversationMessageMapper;

    @Value("${ai4kb.conversation-retention-days:90}")
    private int conversationRetentionDays;

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

    public ConversationPageResult listUserConversations(Long userId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        long offset = (long) (safePage - 1) * safePageSize;
        Long totalCount = userConversationMapper.selectCount(new LambdaQueryWrapper<UserConversation>()
                .eq(UserConversation::getUserId, userId));
        long total = totalCount == null ? 0L : totalCount;
        // 手工拼接 limit/offset，避免分页插件未启用时出现“返回全量+total=0”的问题。
        List<UserConversation> records = userConversationMapper.selectList(new LambdaQueryWrapper<UserConversation>()
                .eq(UserConversation::getUserId, userId)
                .orderByDesc(UserConversation::getUpdatedAt)
                .last("limit " + safePageSize + " offset " + Math.max(0L, offset)));
        records.forEach(this::fillRetentionMeta);
        return new ConversationPageResult(
                records,
                total,
                safePage,
                safePageSize,
                offset + records.size() < total,
                safeRetentionDays()
        );
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

    public MessagePageResult listConversationMessages(Long userId, String conversationId, Long beforeId, int limit) {
        requireOwnedConversation(userId, conversationId);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<UserConversationMessage> wrapper = new LambdaQueryWrapper<UserConversationMessage>()
                .eq(UserConversationMessage::getConversationId, conversationId)
                .orderByDesc(UserConversationMessage::getId);
        if (beforeId != null && beforeId > 0) {
            wrapper.lt(UserConversationMessage::getId, beforeId);
        }
        wrapper.last("limit " + safeLimit);
        List<UserConversationMessage> descRecords = userConversationMessageMapper.selectList(wrapper);
        List<UserConversationMessage> items = new ArrayList<>(descRecords == null ? List.of() : descRecords);
        Collections.reverse(items);
        if (items.isEmpty()) {
            return new MessagePageResult(List.of(), false, null, safeLimit);
        }
        Long earliestId = items.get(0).getId();
        Long olderCount = userConversationMessageMapper.selectCount(new LambdaQueryWrapper<UserConversationMessage>()
                .eq(UserConversationMessage::getConversationId, conversationId)
                .lt(UserConversationMessage::getId, earliestId));
        boolean hasMore = olderCount != null && olderCount > 0;
        return new MessagePageResult(items, hasMore, hasMore ? earliestId : null, safeLimit);
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

    public int deleteBefore(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        List<UserConversation> expired = userConversationMapper.selectList(new LambdaQueryWrapper<UserConversation>()
                .lt(UserConversation::getUpdatedAt, cutoff)
                .select(UserConversation::getId));
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        List<String> conversationIds = expired.stream().map(UserConversation::getId).toList();
        userConversationMessageMapper.delete(new LambdaQueryWrapper<UserConversationMessage>()
                .in(UserConversationMessage::getConversationId, conversationIds));
        return userConversationMapper.delete(new LambdaQueryWrapper<UserConversation>()
                .in(UserConversation::getId, conversationIds));
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

    private void fillRetentionMeta(UserConversation conversation) {
        if (conversation == null) {
            return;
        }
        int keepDays = safeRetentionDays();
        LocalDateTime base = conversation.getUpdatedAt() != null ? conversation.getUpdatedAt() : conversation.getCreatedAt();
        if (base == null) {
            base = LocalDateTime.now();
        }
        LocalDateTime expireAt = base.plusDays(keepDays);
        long days = ChronoUnit.DAYS.between(LocalDate.now(), expireAt.toLocalDate());
        conversation.setExpireAt(expireAt);
        conversation.setRemainingDays((int) Math.max(0, days));
    }

    private int safeRetentionDays() {
        return Math.max(1, conversationRetentionDays);
    }

    public record ConversationPageResult(
            List<UserConversation> items,
            long total,
            int page,
            int pageSize,
            boolean hasMore,
            int retentionDays
    ) {
    }

    public record MessagePageResult(
            List<UserConversationMessage> items,
            boolean hasMore,
            Long nextBeforeId,
            int pageSize
    ) {
    }
}
