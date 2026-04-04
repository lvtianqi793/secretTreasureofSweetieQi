package com.energy.management.service;

import com.energy.management.config.AiConfig;
import com.energy.management.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * AI大模型服务 - 对接OpenAI兼容API (Ollama / 通义千问 / ChatGPT等)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final OkHttpClient httpClient;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    /**
     * 发送消息到AI模型并获取回复
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @param history      对话历史 (可选)
     * @return AI回复文本
     */
    public String chat(String systemPrompt, String userMessage, List<ChatMessage> history) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", aiConfig.getModel());
            requestBody.put("max_tokens", aiConfig.getMaxTokens());
            requestBody.put("temperature", 0.1); // 低温度保证SQL生成稳定性

            ArrayNode messages = requestBody.putArray("messages");

            // 系统提示词
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                ObjectNode sysMsg = messages.addObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
            }

            // 对话历史
            if (history != null) {
                for (ChatMessage msg : history) {
                    ObjectNode histMsg = messages.addObject();
                    histMsg.put("role", msg.getRole());
                    histMsg.put("content", msg.getContent());
                }
            }

            // 当前用户消息
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("AI request: {}", jsonBody);

            Request request = new Request.Builder()
                    .url(aiConfig.getBaseUrl() + "/chat/completions")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer " + aiConfig.getApiKey())
                    .post(RequestBody.create(jsonBody, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "no body";
                    log.error("AI API error: {} - {}", response.code(), errBody);
                    throw new RuntimeException("AI API调用失败: " + response.code() + " - " + errBody);
                }

                String responseBody = response.body().string();
                log.debug("AI response: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode choices = root.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    return choices.get(0).get("message").get("content").asText();
                }

                throw new RuntimeException("AI返回格式异常: " + responseBody);
            }
        } catch (IOException e) {
            log.error("AI API通信异常", e);
            throw new RuntimeException("AI服务通信失败: " + e.getMessage(), e);
        }
    }

    /**
     * 简单对话 (无历史)
     */
    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, null);
    }
}
