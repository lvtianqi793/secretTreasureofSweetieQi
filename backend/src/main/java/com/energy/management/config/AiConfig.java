package com.energy.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI模型配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.api")
public class AiConfig {
    private String baseUrl = "http://localhost:11434/v1";
    private String apiKey = "";
    private String model = "qwen2.5:7b";
    private int timeout = 120;
    private int maxTokens = 4096;
}
