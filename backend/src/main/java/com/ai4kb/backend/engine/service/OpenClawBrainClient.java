package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.config.BrainOpenClawProperties;
import com.ai4kb.backend.engine.model.Message;
import com.ai4kb.backend.engine.model.openai.OpenAiChatRequest;
import com.ai4kb.backend.engine.model.openai.OpenAiChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai4kb.brain", name = "provider", havingValue = "openclaw")
public class OpenClawBrainClient implements BrainClient {

    private final BrainOpenClawProperties config;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    @Override
    public Flux<OpenAiChatResponse> chatStream(OpenAiChatRequest request) {
        boolean hasTools = request.getTools() != null && !request.getTools().isEmpty();
        request.setModel(resolveModel(request.getModel()));
        request.setStream(!hasTools);
        boolean useDirectLlm = StringUtils.hasText(config.getLlmBaseUrl());
        if (useDirectLlm) {
            sanitizeMessages(request);
            if (hasTools) {
                request.setTools(null);
            }
        }
        try {
            log.info("OpenClaw request payload: {}", objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            log.warn("Failed to serialize OpenClaw request payload", e);
        }
        WebClient webClient = webClientBuilder.baseUrl(useDirectLlm ? config.getLlmBaseUrl() : config.getBaseUrl()).build();
        if (hasTools) {
            return webClient.post()
                    .uri("/chat/completions")
                    .headers(headers -> applyHeaders(headers, useDirectLlm))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class).flatMap(body -> {
                                log.error("OpenClaw non-stream error: status={}, body={}", response.statusCode(), body);
                                return Mono.error(new RuntimeException("OpenClaw request failed: " + body));
                            }))
                    .bodyToMono(OpenAiChatResponse.class)
                    .flux();
        }
        return webClient.post()
                .uri("/chat/completions")
                .headers(headers -> applyHeaders(headers, useDirectLlm))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("OpenClaw stream error: status={}, body={}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("OpenClaw request failed: " + body));
                        }))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(ServerSentEvent::data)
                .filter(data -> !"[DONE]".equals(data.trim()))
                .map(data -> {
                    try {
                        return objectMapper.readValue(data, OpenAiChatResponse.class);
                    } catch (Exception e) {
                        log.error("Failed to parse SSE data from OpenClaw: {}", data, e);
                        return null;
                    }
                });
    }

    private void applyHeaders(org.springframework.http.HttpHeaders headers, boolean useDirectLlm) {
        if (StringUtils.hasText(config.getApiKey())) {
            headers.setBearerAuth(config.getApiKey());
        }
        if (useDirectLlm) {
            return;
        }
        if (StringUtils.hasText(config.getAgentId())) {
            headers.set("X-OpenClaw-Agent-Id", config.getAgentId());
        }
        if (StringUtils.hasText(config.getScopes())) {
            headers.set("X-OpenClaw-Scopes", config.getScopes());
        }
    }

    private String resolveModel(String fallbackModel) {
        if (StringUtils.hasText(config.getModel())) {
            return config.getModel();
        }
        return fallbackModel;
    }

    private void sanitizeMessages(OpenAiChatRequest request) {
        List<Message> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Message message : messages) {
            if (message == null || message.getContent() == null) {
                continue;
            }
            message.setContent(stripControlTokens(message.getContent()));
        }
        Message tail = messages.get(messages.size() - 1);
        if (tail == null || !StringUtils.hasText(tail.getRole()) || !"user".equalsIgnoreCase(tail.getRole())) {
            messages.add(Message.builder().role("user").content("请继续").build());
        }
    }

    private String stripControlTokens(String text) {
        String cleaned = text
                .replace("<|im_start|>", "")
                .replace("<|im_end|>", "")
                .replace("<|im_start", "")
                .replace("<|im_end", "");
        return cleaned.trim();
    }
}
