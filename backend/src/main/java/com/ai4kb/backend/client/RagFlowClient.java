package com.ai4kb.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
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
                .map(json -> json.path("data").path("id").asText());
    }

    public Flux<String> chatStream(String conversationId, String message) {
        // Using OpenAI compatible endpoint
        return webClient.post()
                .uri(baseUrl + "/api/v1/chats_openai/" + conversationId + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "default", // RAGFlow might ignore this or require a valid model name
                        "messages", List.of(Map.of("role", "user", "content", message)),
                        "stream", true,
                        "extra_body", Map.of("reference", true)
                ))
                .retrieve()
                .bodyToFlux(String.class);
    }
}
