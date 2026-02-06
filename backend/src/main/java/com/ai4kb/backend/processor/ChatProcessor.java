package com.ai4kb.backend.processor;

import reactor.core.publisher.Flux;

public interface ChatProcessor {
    /**
     * Process chat request
     * @param username Current user
     * @param query User question
     * @param stream Whether to stream response (backend may downgrade to non-stream)
     * @return SSE Stream
     */
    Flux<String> process(String username, String query, boolean stream);
}
