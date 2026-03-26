package com.ai4kb.backend.rag.processor.impl;

import com.ai4kb.backend.rag.processor.ChatProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@ConditionalOnProperty(name = "ai4kb.processor.mode", havingValue = "agent")
/**
 * Agent 模式占位处理器。
 * 在 `ai4kb.processor.mode=agent` 时启用，用于返回可视化联调的模拟流式结果。
 */
public class MockAgentProcessor implements ChatProcessor {

    @Override
    /**
     * 返回固定 mock 响应，保持与前端 SSE 载荷格式一致。
     */
    public Flux<String> process(String username, String query, boolean stream) {
        log.info("MockAgentProcessor received query from {}: {}", username, query);
        // 约定：返回 JSON 字符串，由上层按 SSE data 透传
        return Flux.just(
            "{\"answer\": \"This is a mock response from the Agent Processor (Phase 2 Preview). I am pretending to think...\"}",
            "{\"answer\": \"Processing your request: " + query + "\"}",
            "[DONE]"
        );
    }
}
