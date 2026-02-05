package com.ai4kb.backend.processor;

import reactor.core.publisher.Flux;

public interface ChatProcessor {
    /**
     * Process chat request
     * @param username Current user
     * @param query User question
     * @return SSE Stream
     */
    Flux<String> process(String username, String query);
}
