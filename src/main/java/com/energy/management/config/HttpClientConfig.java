package com.energy.management.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient(AiConfig aiConfig) {
        return new OkHttpClient.Builder()
                .connectTimeout(aiConfig.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(aiConfig.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(aiConfig.getTimeout(), TimeUnit.SECONDS)
                .build();
    }
}
