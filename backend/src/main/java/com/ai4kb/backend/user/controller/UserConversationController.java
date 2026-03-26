package com.ai4kb.backend.user.controller;

import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import com.ai4kb.backend.user.entity.UserConversation;
import com.ai4kb.backend.user.entity.UserConversationMessage;
import com.ai4kb.backend.user.service.UserConversationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/conversations")
@RequiredArgsConstructor
public class UserConversationController {
    private final UserConversationService userConversationService;

    @PostMapping
    public UserConversation createConversation(@RequestBody(required = false) CreateConversationRequest request) {
        AuthenticatedUser user = requireAuthenticated();
        String title = request == null ? null : request.getTitle();
        return userConversationService.createConversation(user.getUserId(), title);
    }

    @GetMapping
    public List<UserConversation> listConversations() {
        AuthenticatedUser user = requireAuthenticated();
        return userConversationService.listUserConversations(user.getUserId());
    }

    @GetMapping("/{conversationId}/messages")
    public List<UserConversationMessage> listMessages(@PathVariable String conversationId) {
        AuthenticatedUser user = requireAuthenticated();
        return userConversationService.listConversationMessages(user.getUserId(), conversationId);
    }

    @PostMapping("/{conversationId}/messages")
    public UserConversationMessage appendMessage(@PathVariable String conversationId, @RequestBody SaveMessageRequest request) {
        AuthenticatedUser user = requireAuthenticated();
        String content = request == null ? "" : request.getContent();
        String role = request == null ? "user" : request.getRole();
        String messagePayload = request == null ? "" : request.getMessagePayload();
        userConversationService.ensureConversation(user.getUserId(), conversationId, request == null ? null : request.getConversationTitle());
        return userConversationService.appendMessage(user.getUserId(), conversationId, role, content, messagePayload);
    }

    @PutMapping("/{conversationId}")
    public UserConversation renameConversation(@PathVariable String conversationId, @RequestBody(required = false) RenameConversationRequest request) {
        AuthenticatedUser user = requireAuthenticated();
        String title = request == null ? null : request.getTitle();
        return userConversationService.renameConversation(user.getUserId(), conversationId, title);
    }

    @DeleteMapping("/{conversationId}")
    public Map<String, Object> deleteConversation(@PathVariable String conversationId) {
        AuthenticatedUser user = requireAuthenticated();
        userConversationService.deleteConversation(user.getUserId(), conversationId);
        return Map.of("code", 0, "message", "ok");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalState(IllegalStateException ex) {
        return Map.of("code", 400, "message", ex.getMessage());
    }

    private AuthenticatedUser requireAuthenticated() {
        AuthenticatedUser user = AuthContextHolder.get();
        if (user == null) {
            throw new IllegalStateException("未登录");
        }
        return user;
    }

    @Data
    public static class CreateConversationRequest {
        private String title;
    }

    @Data
    public static class SaveMessageRequest {
        private String role;
        private String content;
        private String conversationTitle;
        private String messagePayload;
    }

    @Data
    public static class RenameConversationRequest {
        private String title;
    }
}
