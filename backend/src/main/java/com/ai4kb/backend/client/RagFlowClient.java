package com.ai4kb.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class RagFlowClient {

    private final WebClient webClient;
    
    @Value("${ragflow.base-url}")
    private String baseUrl;
    
    @Value("${ragflow.api-key}")
    private String apiKey;

    @Value("${ragflow.llm-model:deepseek-r1-distill-qwen-14b@Xinference}")
    private String llmModel;

    public RagFlowClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public Mono<JsonNode> listDatasets(int page, int pageSize) {
        return webClient.get()
                .uri(baseUrl + "/api/v1/datasets?page=" + page + "&page_size=" + pageSize)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<String> createConversation(String name, List<String> datasetIds) {
        return webClient.post()
                .uri(baseUrl + "/api/v1/chats")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", name,
                        "dataset_ids", datasetIds
                ))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(json -> {
                    if (json.has("code") && json.path("code").asInt(0) != 0) {
                        int code = json.path("code").asInt(-1);
                        String msg = json.path("message").asText("");
                        return Mono.error(new RuntimeException("RAGFlow createConversation failed: code=" + code + (msg.isEmpty() ? "" : (", message=" + msg))));
                    }
                    String id = json.path("data").path("id").asText("");
                    if (id.isEmpty()) {
                        return Mono.error(new RuntimeException("RAGFlow createConversation returned empty id"));
                    }
                    return Mono.just(id);
                });
    }

    public Mono<JsonNode> chatCompletion(String conversationId, String message) {
        return webClient.post()
                .uri(baseUrl + "/api/v1/chats_openai/" + conversationId + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", llmModel,
                        "messages", List.of(Map.of("role", "user", "content", message)),
                        "stream", false,
                        "extra_body", Map.of("reference", true)
                ))
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        resp -> resp.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException(
                                        "RAGFlow chatCompletion failed: status=" + resp.statusCode() + ", body=" + body
                                ))
                        )
                )
                .bodyToMono(JsonNode.class);
    }

    public Flux<String> chatStream(String conversationId, String message) {
        return webClient.post()
                .uri(baseUrl + "/api/v1/chats_openai/" + conversationId + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "model", llmModel,
                        "messages", List.of(Map.of("role", "user", "content", message)),
                        "stream", true,
                        "extra_body", Map.of("reference", true)
                ))
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        resp -> resp.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException(
                                        "RAGFlow chatStream failed: status=" + resp.statusCode() + ", body=" + body
                                ))
                        )
                )
                .bodyToFlux(String.class);
    }
}
