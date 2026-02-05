package com.ai4kb.backend.processor.impl;

import com.ai4kb.backend.client.RagFlowClient;
import com.ai4kb.backend.entity.Permission;
import com.ai4kb.backend.entity.User;
import com.ai4kb.backend.mapper.PermissionMapper;
import com.ai4kb.backend.mapper.UserMapper;
import com.ai4kb.backend.processor.ChatProcessor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagDirectProcessor implements ChatProcessor {

    private final RagFlowClient ragFlowClient;
    private final PermissionMapper permissionMapper;
    private final UserMapper userMapper;

    // Simple in-memory cache for conversation IDs: username -> conversationId
    private final Map<String, String> userConversationCache = new ConcurrentHashMap<>();

    @Override
    public Flux<String> process(String username, String query) {
        // 1. Check User
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            return Flux.error(new RuntimeException("User not found: " + username));
        }

        // 2. Get Allowed Datasets
        List<Permission> permissions = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, user.getId())
                .eq(Permission::getResourceType, "DATASET"));
        
        List<String> datasetIds = permissions.stream()
                .map(Permission::getResourceId)
                .collect(Collectors.toList());

        if (datasetIds.isEmpty()) {
            return Flux.just("data: {\"answer\": \"You have no permission to access any knowledge base.\"}");
        }

        // 3. Get or Create Conversation
        // For simplicity, we create a new conversation if datasets changed or not cached.
        // In real world, we might want to update the conversation.
        // Here we just check if we have a conversation for this user. 
        // Note: If permissions changed, we should probably create a new one. 
        // For this demo, we assume permissions don't change often or we just create new one for simplicity logic.
        
        // Let's create a new conversation for each request for safety (Phase 1), 
        // or reuse if we can. RAGFlow conversations are persistent.
        // If we reuse, we must ensure the dataset_ids are updated.
        // RagFlow API might not support updating dataset_ids of an existing conversation easily (need to check).
        // So safe bet: Create a new conversation (Assistant) for this session.
        // To avoid spamming, maybe we can reuse if we knew how to update.
        // Let's go with creating new one for now, as performance optimization is Phase 3.
        
        String conversationName = "chat_" + username + "_" + System.currentTimeMillis();
        
        return ragFlowClient.createConversation(conversationName, datasetIds)
                .flatMapMany(conversationId -> {
                    log.info("Created conversation {} for user {}", conversationId, username);
                    return ragFlowClient.chatStream(conversationId, query);
                });
    }
}
