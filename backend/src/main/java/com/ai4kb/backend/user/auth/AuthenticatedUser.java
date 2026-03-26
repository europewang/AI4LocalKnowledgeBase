package com.ai4kb.backend.user.auth;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthenticatedUser {
    private Long userId;
    private String username;
    private String role;
}
