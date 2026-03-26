package com.ai4kb.backend.rag.controller;

import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import com.ai4kb.backend.rag.processor.ChatProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatProcessor chatProcessor;

    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody Map<String, Object> body) {
        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            question = (String) body.get("query");
        }
        Object streamObj = body.get("stream");
        boolean stream = streamObj instanceof Boolean ? (Boolean) streamObj : true;
        AuthenticatedUser user = AuthContextHolder.get();
        if (user == null || user.getUsername() == null) {
            throw new IllegalStateException("认证上下文缺失");
        }
        return chatProcessor.process(user.getUsername(), question, stream);
    }
}
