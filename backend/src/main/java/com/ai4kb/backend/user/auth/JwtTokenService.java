package com.ai4kb.backend.user.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtTokenService {
    private static final String HMAC_ALGO = "HmacSHA256";
    private final ObjectMapper objectMapper;
    private final String jwtSecret;
    private final long ttlSeconds;

    public JwtTokenService(
            ObjectMapper objectMapper,
            @Value("${ai4kb.auth.jwt-secret:ai4kb-dev-jwt-secret-change-me}") String jwtSecret,
            @Value("${ai4kb.auth.token-ttl-seconds:28800}") long ttlSeconds
    ) {
        this.objectMapper = objectMapper;
        this.jwtSecret = jwtSecret;
        this.ttlSeconds = ttlSeconds;
    }

    public String generateToken(Long userId, String username, String role) {
        try {
            long iat = Instant.now().getEpochSecond();
            long exp = iat + ttlSeconds;
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", String.valueOf(userId));
            payload.put("uid", userId);
            payload.put("username", username);
            payload.put("role", role);
            payload.put("iat", iat);
            payload.put("exp", exp);
            String headerPart = base64UrlEncode(objectMapper.writeValueAsBytes(header));
            String payloadPart = base64UrlEncode(objectMapper.writeValueAsBytes(payload));
            String signaturePart = sign(headerPart + "." + payloadPart);
            return headerPart + "." + payloadPart + "." + signaturePart;
        } catch (Exception e) {
            throw new IllegalStateException("生成 token 失败", e);
        }
    }

    public AuthenticatedUser parseToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("token 格式非法");
            }
            String signedData = parts[0] + "." + parts[1];
            String expectedSign = sign(signedData);
            if (!constantTimeEquals(expectedSign, parts[2])) {
                throw new IllegalArgumentException("token 签名非法");
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> payload = objectMapper.readValue(payloadBytes, new TypeReference<>() {});
            Long exp = asLong(payload.get("exp"));
            if (exp == null || Instant.now().getEpochSecond() >= exp) {
                throw new IllegalArgumentException("token 已过期");
            }
            Long userId = asLong(payload.get("uid"));
            String username = asString(payload.get("username"));
            String role = asString(payload.get("role"));
            if (userId == null || username == null || role == null) {
                throw new IllegalArgumentException("token 载荷不完整");
            }
            return AuthenticatedUser.builder().userId(userId).username(username).role(role).build();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("token 解析失败", e);
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    private String sign(String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        SecretKeySpec secretKeySpec = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
        mac.init(secretKeySpec);
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(raw);
    }

    private String base64UrlEncode(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
