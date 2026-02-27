package com.ai4kb.backend.processor.impl;

import com.ai4kb.backend.processor.ChatProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@ConditionalOnProperty(name = "ai4kb.processor.mode", havingValue = "agent")
public class MockAgentProcessor implements ChatProcessor {

    @Override
    public Flux<String> process(String username, String query, boolean stream) {
        log.info("MockAgentProcessor received query from {}: {}", username, query);
        // Simulate an agent response
        // Note: The frontend expects JSON string in data: field for SSE
        return Flux.just(
            "{\"answer\": \"This is a mock response from the Agent Processor (Phase 2 Preview). I am pretending to think...\"}",
            "{\"answer\": \"Processing your request: " + query + "\"}",
            "[DONE]"
        );
    }
}
