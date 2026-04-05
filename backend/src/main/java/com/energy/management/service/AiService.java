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
            // 拼接prompt: 系统提示 + 历史对话 + 当前问题
            StringBuilder promptBuilder = new StringBuilder();

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                promptBuilder.append("[系统指令]\n").append(systemPrompt).append("\n\n");
            }

            if (history != null) {
                for (ChatMessage msg : history) {
                    if ("user".equals(msg.getRole())) {
                        promptBuilder.append("[用户] ").append(msg.getContent()).append("\n");
                    } else {
                        promptBuilder.append("[助手] ").append(msg.getContent()).append("\n");
                    }
                }
            }

            promptBuilder.append("[用户] ").append(userMessage).append("\n[助手] ");

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("prompt", promptBuilder.toString());

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("AI request: {}", jsonBody.substring(0, Math.min(500, jsonBody.length())));

            Request request = new Request.Builder()
                    .url(aiConfig.getBaseUrl() + "/generate?Content-Type=application/json")
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "no body";
                    log.error("AI API error: {} - {}", response.code(), errBody);
                    throw new RuntimeException("AI API调用失败: " + response.code() + " - " + errBody);
                }

                // 流式响应: 每行一个JSON, 拼接所有response字段直到done=true
                String rawBody = response.body().string();
                log.debug("AI raw response length: {}", rawBody.length());

                StringBuilder fullResponse = new StringBuilder();
                for (String line : rawBody.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        if (node.has("response")) {
                            fullResponse.append(node.get("response").asText());
                        }
                        if (node.has("done") && node.get("done").asBoolean()) {
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("跳过无法解析的行: {}", line);
                    }
                }

                String result = fullResponse.toString().trim();
                if (result.isEmpty()) {
                    throw new RuntimeException("AI返回内容为空, 原始响应: " + rawBody.substring(0, Math.min(200, rawBody.length())));
                }

                log.debug("AI final response: {}", result.substring(0, Math.min(200, result.length())));
                return result;
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
