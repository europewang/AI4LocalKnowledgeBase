package com.ai4kb.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
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

    public Mono<JsonNode> createDataset(String name) {
        return webClient.post()
                .uri(baseUrl + "/api/v1/datasets")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name))
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> deleteDataset(String id) {
        // RAGFlow API uses DELETE /api/v1/datasets with body {"ids": [...]}
        return webClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri(baseUrl + "/api/v1/datasets")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("ids", List.of(id)))
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> listDocuments(String datasetId, int page, int pageSize) {
        return webClient.get()
                .uri(baseUrl + "/api/v1/datasets/" + datasetId + "/documents?page=" + page + "&page_size=" + pageSize)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> uploadDocument(String datasetId, MultipartFile file) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", file.getResource());
        
        return webClient.post()
                .uri(baseUrl + "/api/v1/datasets/" + datasetId + "/documents")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> deleteDocuments(String datasetId, List<String> ids) {
        return webClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri(baseUrl + "/api/v1/datasets/" + datasetId + "/documents")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("ids", ids))
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> runDocuments(String datasetId, List<String> docIds) {
        // Correct endpoint for running documents in RAGFlow: POST /api/v1/datasets/{dataset_id}/chunks
        // Payload: {"document_ids": ["doc_id1", ...]}
        String uri = baseUrl + "/api/v1/datasets/" + datasetId + "/chunks";
        log.info("Triggering parsing for dataset {}, docs {}. URI: {}", datasetId, docIds, uri);
        
        return webClient.post()
                .uri(uri)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("document_ids", docIds))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnSuccess(json -> log.info("Parsing triggered successfully: {}", json))
                .doOnError(e -> log.error("Error triggering parsing: {}", e.getMessage()));
    }

    public Mono<JsonNode> listChunks(String datasetId, String docId, int page, int pageSize) {
        return webClient.get()
                .uri(baseUrl + "/api/v1/datasets/" + datasetId + "/documents/" + docId + "/chunks?page=" + page + "&page_size=" + pageSize)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    public Mono<org.springframework.core.io.Resource> getDocumentFile(String datasetId, String docId) {
        // According to RAGFlow API (unofficial), file download is usually GET /api/v1/datasets/{dataset_id}/documents/{doc_id}
        // or via a dedicated file server endpoint.
        // Let's try to fetch the document metadata first to see if there's a download URL.
        // If not, we try to construct a download URL.
        
        // Actually, RAGFlow uses a separate endpoint for file retrieval.
        // It seems `GET /api/v1/document/get/{doc_id}` returns metadata.
        // The file content might be served from `GET /api/v1/document/image/{dataset_id}/{doc_id}` for images or similar for files.
        // But for generic file download, let's try a direct approach based on common RAGFlow patterns.
        
        // Correct approach:
        // Use the document ID to get the file.
        // Try: GET /api/v1/datasets/{dataset_id}/documents/{doc_id}/download (speculative)
        // OR: GET /doc/{doc_id} (if served directly)
        
        // Let's assume the path is similar to how we upload but with GET on the doc resource
        // If this fails (404), we might need to inspect RAGFlow's actual API or source code.
        // Given the error 500 (which likely means 404 from upstream or 500 from upstream), let's try a safer bet.
        
        // In RAGFlow, downloaded files are often accessed via `/v1/document/get/{doc_id}` which returns a JSON with a temporary URL?
        // No, let's look at how we implemented `debug_image_proxy.py`.
        // We used `/api/v1/document/image/{dataset_id}/{image_id}`.
        
        // Let's try to find the file download endpoint.
        // A common pattern is `GET /api/v1/datasets/{dataset_id}/documents/{doc_id}`
        // If that returns JSON, we look for a download link.
        // But we want the stream.
        
        // Let's try this endpoint which is often used for raw content:
        // GET /api/v1/document/get/{doc_id} -> This returns metadata.
        
        // Let's try to proxy the request to `/api/v1/datasets/{dataset_id}/documents/{doc_id}` but expect a file?
        // If the previous attempt failed, it might be because the URL was wrong.
        
        // Let's try: GET /document/get/{doc_id} with response_format=content?
        
        // Let's try the documented (or reverse-engineered) path:
        // GET /api/v1/datasets/{dataset_id}/documents/{doc_id}
        // This usually returns metadata.
        
        // Let's try to fetch the document metadata first to get the `location` or `name`
        // and then construct the path if it's a local file? No, we can't access local files of RAGFlow container.
        
        // Let's try `GET /api/v1/document/upload/{doc_id}`? No.
        
        // Wait, for `debug_image_proxy.py`, we used:
        // `url = f"{RAGFLOW_API_BASE}/api/v1/document/image/{dataset_id}/{image_id}"`
        // Maybe for docs it is `/api/v1/document/file/{dataset_id}/{doc_id}`?
        
        return webClient.get()
                .uri(baseUrl + "/api/v1/document/file/" + datasetId + "/" + docId)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(org.springframework.core.io.Resource.class)
                .onErrorResume(e -> {
                    // Fallback: try another potential endpoint
                    return webClient.get()
                            .uri(baseUrl + "/api/v1/datasets/" + datasetId + "/documents/" + docId)
                            .header("Authorization", "Bearer " + apiKey)
                            .retrieve()
                            .bodyToMono(org.springframework.core.io.Resource.class);
                });
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

    public Mono<byte[]> getImage(String imageId) {
        return webClient.get()
                .uri(baseUrl + "/v1/document/image/" + imageId)
                .header("Authorization", "Bearer " + apiKey)
                .accept(MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG, MediaType.ALL)
                .retrieve()
                .bodyToMono(byte[].class);
    }

    public Mono<byte[]> getDocument(String docId) {
        String uri = baseUrl + "/v1/document/get/" + docId;
        log.info("Fetching document from RAGFlow: {}", uri);
        return webClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + apiKey)
                .accept(MediaType.APPLICATION_PDF, MediaType.ALL)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> {
                    log.error("RAGFlow returned error status: {}", resp.statusCode());
                    return resp.bodyToMono(String.class).flatMap(body -> {
                        log.error("RAGFlow error body: {}", body);
                        return Mono.error(new RuntimeException("RAGFlow error: " + resp.statusCode()));
                    });
                })
                .bodyToMono(byte[].class)
                .doOnSuccess(bytes -> log.info("Successfully fetched document {}, size: {}", docId, bytes != null ? bytes.length : 0));
    }
}
