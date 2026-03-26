package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.config.LlmConfigProperties;
import com.ai4kb.backend.engine.model.openai.OpenAiChatRequest;
import com.ai4kb.backend.engine.model.openai.OpenAiChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * LLM 网关客户端。
 * 统一封装对 `/chat/completions` 的调用，并兼容“工具调用非流式”与“纯对话流式”两种模式。
 */
public class LlmClient {

    private final LlmConfigProperties config;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    /**
     * 发起一次对话请求并返回标准响应流。
     * 当请求包含 tools 时，为兼容 tool_call 解析，会自动切换为单包返回。
     */
    public Flux<OpenAiChatResponse> chatStream(OpenAiChatRequest request) {
        boolean hasTools = request.getTools() != null && !request.getTools().isEmpty();
        request.setModel(config.getModel());
        request.setStream(!hasTools);
        try {
            log.info("LLM request payload: {}", objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            log.warn("Failed to serialize LLM request payload", e);
        }

        WebClient webClient = webClientBuilder.baseUrl(config.getBaseUrl()).build();

        if (hasTools) {
            return webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class).flatMap(body -> {
                                log.error("LLM non-stream error: status={}, body={}", response.statusCode(), body);
                                return Mono.error(new RuntimeException("LLM request failed: " + body));
                            }))
                    .bodyToMono(OpenAiChatResponse.class)
                    .flux();
        }

        return webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("LLM stream error: status={}, body={}", response.statusCode(), body);
                            return Mono.error(new RuntimeException("LLM request failed: " + body));
                        }))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(ServerSentEvent::data)
                .filter(data -> !"[DONE]".equals(data.trim()))
                .map(data -> {
                    try {
                        return objectMapper.readValue(data, OpenAiChatResponse.class);
                    } catch (Exception e) {
                        log.error("Failed to parse SSE data from LLM: {}", data, e);
                        return null;
                    }
                });
    }
}
