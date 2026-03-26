package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.model.ConversationState;
import com.ai4kb.backend.engine.model.ToolCallDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
/**
 * 会话状态仓储服务（Redis）。
 * 统一管理会话状态与工具草稿两类短期数据，并通过 TTL 控制生命周期。
 */
public class ConversationService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String STATE_PREFIX = "conv:";
    private static final String DRAFT_PREFIX = "tool_draft:";
    private static final Duration EXPIRATION = Duration.ofDays(7);

    /**
     * 保存会话状态快照，键格式：conv:{conversationId}:state。
     */
    public void saveState(ConversationState state) {
        String key = STATE_PREFIX + state.getConversationId() + ":state";
        redisTemplate.opsForValue().set(key, state, EXPIRATION);
    }

    /**
     * 读取会话状态快照，不存在时返回 null。
     */
    public ConversationState getState(String conversationId) {
        String key = STATE_PREFIX + conversationId + ":state";
        return (ConversationState) redisTemplate.opsForValue().get(key);
    }

    /**
     * 保存工具审批草稿，键格式：tool_draft:{toolCallId}。
     */
    public void saveDraft(ToolCallDraft draft) {
        String key = DRAFT_PREFIX + draft.getToolCallId();
        redisTemplate.opsForValue().set(key, draft, EXPIRATION);
    }

    /**
     * 读取工具审批草稿，不存在时返回 null。
     */
    public ToolCallDraft getDraft(String toolCallId) {
        String key = DRAFT_PREFIX + toolCallId;
        return (ToolCallDraft) redisTemplate.opsForValue().get(key);
    }
}
