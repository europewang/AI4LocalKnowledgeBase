package com.ai4kb.backend.engine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai4kb.brain.openclaw")
public class BrainOpenClawProperties {
    private String baseUrl;
    private String apiKey;
    private String agentId;
    private String scopes;
    private String model;
    private Integer timeoutMs;
    private String llmBaseUrl;
    private String managedBaseUrl;
    private String managedChatPath;
    private String managedApiKey;
    private String ragSearchUrl;
    private String skillCatalogUrl;
    private String skillExecuteUrl;
    private String workflowSpecUrl;
}
