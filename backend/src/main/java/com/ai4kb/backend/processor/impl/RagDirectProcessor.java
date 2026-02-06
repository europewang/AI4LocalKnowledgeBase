package com.ai4kb.backend.processor.impl;

import com.ai4kb.backend.client.RagFlowClient;
import com.ai4kb.backend.entity.Permission;
import com.ai4kb.backend.entity.User;
import com.ai4kb.backend.mapper.PermissionMapper;
import com.ai4kb.backend.mapper.UserMapper;
import com.ai4kb.backend.processor.ChatProcessor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagDirectProcessor implements ChatProcessor {

    private final RagFlowClient ragFlowClient;
    private final PermissionMapper permissionMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    // Simple in-memory cache for conversation IDs: username -> conversationId
    private final Map<String, String> userConversationCache = new ConcurrentHashMap<>();

    private record DatasetSelection(List<String> datasetIds, boolean usedFallback, boolean anyAllowedVisible) {}

    @Override
    public Flux<String> process(String username, String query, boolean stream) {
        // 注意：Controller 返回 TEXT_EVENT_STREAM 时，Spring 会自动把 Flux<String> 包装成 SSE（自动加 data: 前缀与空行），
        // 所以这里每个元素只返回“纯 JSON 字符串”或 “[DONE]”，不要手动拼接 data: ...\n\n。
        // 1. Check User
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            return Flux.just(
                    _buildPayload("User not found", null),
                    "[DONE]"
            );
        }

        // 2. Get Allowed Datasets
        List<Permission> permissions = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, user.getId())
                .eq(Permission::getResourceType, "DATASET"));
        
        List<String> datasetIds = permissions.stream()
                .map(Permission::getResourceId)
                .collect(Collectors.toList());

        if (datasetIds.isEmpty()) {
            return Flux.just(
                    _buildPayload("You have no permission to access any knowledge base.", null),
                    "[DONE]"
            );
        }

        // 3. Filter ready datasets by querying RAGFlow (must have parsed chunks/documents)
        String conversationName = "chat_" + username + "_" + System.currentTimeMillis();
        return ragFlowClient.listDatasets(1, 100)
                .map(all -> {
                    java.util.Set<String> allowed = new java.util.HashSet<>(datasetIds);
                    java.util.List<String> readyIds = new java.util.ArrayList<>();
                    boolean anyAllowedVisible = false;
                    JsonNode arr = all.path("data");
                    if (!arr.isArray()) {
                        return new DatasetSelection(datasetIds, true, false);
                    }
                    for (JsonNode n : arr) {
                        String id = n.path("id").asText("");
                        if (!allowed.contains(id)) {
                            continue;
                        }
                        anyAllowedVisible = true;
                        int chunks = n.path("chunk_count").asInt(0);
                        int docs = n.path("document_count").asInt(0);
                        if (chunks > 0 && docs > 0) {
                            readyIds.add(id);
                        }
                    }
                    return new DatasetSelection(readyIds, false, anyAllowedVisible);
                })
                .flatMapMany(sel -> {
                    if (sel.datasetIds().isEmpty()) {
                        String msg;
                        if (sel.usedFallback()) {
                            msg = "RAGFlow datasets API response shape unexpected, please check backend log.";
                        } else if (!sel.anyAllowedVisible()) {
                            msg = "Your permitted knowledge base does not exist in RAGFlow. Please re-grant permissions.";
                        } else {
                            msg = "No ready dataset to chat (waiting for parsing).";
                        }
                        String payload = _buildPayload(msg, null);
                        return Flux.just(payload, "[DONE]");
                    }
                    return ragFlowClient.createConversation(conversationName, sel.datasetIds())
                            .flatMapMany(conversationId -> {
                                log.info("Created conversation {} for user {}", conversationId, username);
                                if (stream) {
                                    return _convertRagFlowStreamToAnswer(ragFlowClient.chatStream(conversationId, query))
                                            .onErrorResume(ex -> {
                                                String payload = _buildPayload("Stream failed: " + (ex.getMessage() == null ? "unknown" : ex.getMessage()), null);
                                                return Flux.just(payload, "[DONE]");
                                            });
                                } else {
                                    return ragFlowClient.chatCompletion(conversationId, query)
                                            .flatMapMany(resp -> {
                                                String answer = _extractAnswer(resp);
                                                JsonNode reference = _extractReference(resp);
                                                String payload = _buildPayload(answer, reference);
                                                return Flux.just(payload, "[DONE]");
                                            });
                                }
                            });
                })
                .onErrorResume(ex -> {
                    log.error("Chat failed for user={}", username, ex);
                    String safeMsg = "Chat failed: " + (ex.getMessage() == null ? "unknown" : ex.getMessage());
                    String payload = _buildPayload(safeMsg, null);
                    return Flux.just(payload, "[DONE]");
                });
    }

    private Flux<String> _convertRagFlowStreamToAnswer(Flux<String> upstream) {
        return upstream
                .flatMapIterable(chunk -> Arrays.asList(chunk.split("\n")))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .handle((String data, SynchronousSink<String> sink) -> {
                    // RAGFlow 上游流式数据有两种常见形态：
                    // 1) 标准 SSE 行：data: {json}
                    // 2) WebClient 已经剥离了 data: 前缀，只剩 {json}
                    // 这里做一次归一化，保证后续 JSON 解析一致。
                    String normalized = data.startsWith("data:") ? data.substring(5).trim() : data;
                    if (normalized.equals("[DONE]")) {
                        sink.complete();
                        return;
                    }
                    try {
                        JsonNode json = objectMapper.readTree(normalized);
                        JsonNode directAnswer = json.path("answer");
                        if (directAnswer.isTextual()) {
                            sink.next(json.toString());
                            return;
                        }
                        JsonNode choices = json.path("choices");
                        if (choices.isArray() && !choices.isEmpty()) {
                            JsonNode delta = choices.get(0).path("delta");
                            JsonNode content = delta.path("content");
                            if (content.isTextual()) {
                                String payload = _buildPayload(content.asText(""), null);
                                sink.next(payload);
                                return;
                            }
                            JsonNode deltaAnswer = delta.path("answer");
                            if (deltaAnswer.isTextual()) {
                                String payload = _buildPayload(deltaAnswer.asText(""), null);
                                sink.next(payload);
                                return;
                            }
                            JsonNode message = choices.get(0).path("message").path("content");
                            if (message.isTextual()) {
                                String payload = _buildPayload(message.asText(""), null);
                                sink.next(payload);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                })
                .concatWithValues("[DONE]");
    }

    private String _extractAnswer(JsonNode resp) {
        JsonNode choices = resp.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText("");
            }
        }
        return "";
    }

    private JsonNode _extractReference(JsonNode resp) {
        JsonNode choices = resp.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode ref = choices.get(0).path("message").path("reference");
            if (!ref.isMissingNode() && !ref.isNull()) {
                return ref;
            }
        }
        return null;
    }

    private String _buildPayload(String answer, JsonNode reference) {
        try {
            String refJson = (reference == null) ? "null" : reference.toString();
            return "{\"answer\":" + objectMapper.writeValueAsString(answer) + ",\"reference\":" + refJson + "}";
        } catch (JsonProcessingException e) {
            return "{\"answer\":\"serialization failed\",\"reference\":null}";
        }
    }
}
