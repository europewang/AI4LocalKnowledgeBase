package com.ai4kb.backend.engine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai4kb.llm")
/**
 * LLM 配置属性。
 * 对应 `ai4kb.llm.*` 配置项，用于注入模型网关地址、密钥与默认模型名。
 */
public class LlmConfigProperties {
    private String baseUrl;
    private String apiKey;
    private String model;
}
