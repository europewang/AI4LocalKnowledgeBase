package com.ai4kb.backend.knowledge.controller;

import com.ai4kb.backend.user.client.RagFlowClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
@Slf4j
/**
 * 文档与图片透传控制器。
 * 负责按文档/图片 ID 从 RAGFlow 拉取二进制内容并返回给前端预览。
 */
public class DocumentController {

    private final RagFlowClient ragFlowClient;

    /**
     * 获取图片内容。
     */
    @GetMapping("/image/{imageId}")
    public Mono<ResponseEntity<byte[]>> getImage(@PathVariable String imageId) {
        return ragFlowClient.getImage(imageId)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(bytes))
                .doOnError(e -> log.error("Error getting image {}: {}", imageId, e.getMessage()))
                .onErrorResume(e -> Mono.just(ResponseEntity.notFound().build()));
    }

    /**
     * 获取文档内容（当前按 PDF 内联返回）。
     */
    @GetMapping("/get/{docId}")
    public Mono<ResponseEntity<byte[]>> getDocument(@PathVariable String docId) {
        log.info("Requesting document {}", docId);
        return ragFlowClient.getDocument(docId)
                .map(bytes -> {
                    log.info("Received document {}, size: {}", docId, bytes.length);
                    return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + docId + ".pdf\"")
                        .body(bytes);
                })
                .doOnError(e -> {
                    log.error("Error getting document {}: {}", docId, e.getMessage());
                    e.printStackTrace();
                })
                .onErrorResume(e -> Mono.just(ResponseEntity.notFound().build()));
    }
}
