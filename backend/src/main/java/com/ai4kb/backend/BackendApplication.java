package com.ai4kb.backend;

import com.ai4kb.backend.engine.config.LlmConfigProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("com.ai4kb.backend.**.mapper")
@EnableConfigurationProperties(LlmConfigProperties.class)
/**
 * Backend 应用启动入口。
 * 负责装配 Spring Boot 上下文、Mapper 扫描与 LLM 配置绑定。
 */
public class BackendApplication {
    /**
     * 启动主程序。
     */
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
