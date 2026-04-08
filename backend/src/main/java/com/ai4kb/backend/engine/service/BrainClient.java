package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.model.openai.OpenAiChatRequest;
import com.ai4kb.backend.engine.model.openai.OpenAiChatResponse;
import reactor.core.publisher.Flux;

public interface BrainClient {
    Flux<OpenAiChatResponse> chatStream(OpenAiChatRequest request);
}
