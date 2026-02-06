package com.ai4kb.backend.controller;

import com.ai4kb.backend.processor.ChatProcessor;
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
    public Flux<String> chat(@RequestHeader("X-User-Name") String username,
                             @RequestBody Map<String, Object> body) {
        String question = (String) body.get("question");
        if (question == null || question.isBlank()) {
            question = (String) body.get("query");
        }
        Object streamObj = body.get("stream");
        boolean stream = streamObj instanceof Boolean ? (Boolean) streamObj : true;
        return chatProcessor.process(username, question, stream);
    }
}
