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
 * AI大模型服务 - 对接本地LLM三个独立接口:
 * /generate/chat         - 运维知识问答
 * /generate/generatesql  - 自然语言生成SQL
 * /generate/analyse      - 数据分析
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
     * 运维知识问答 → /generate/chat
     */
    public String chatOps(String prompt, List<ChatMessage> history) {
        return callAi("/generate/chat", prompt, history);
    }

    /**
     * 生成SQL → /generate/generatesql
     */
    public String generateSql(String prompt, List<ChatMessage> history) {
        return callAi("/generate/generatesql", prompt, history);
    }

    /**
     * 数据分析 → /generate/analyse
     */
    public String analyse(String prompt) {
        return callAi("/generate/analyse", prompt, null);
    }

    /**
     * MCP Agent 智能问答 → /generate/agent
     * 由 FastAPI 侧的 Ollama tool-calling + MCP 协议自主决定是否调用工具查真实数据。
     * 历史通过结构化 messages 字段透传，由 FastAPI 按多轮对话注入 Ollama，保证跨轮记忆。
     */
    public String chatAgent(String prompt, List<ChatMessage> history) {
        return callAiStructured("/generate/agent", prompt, history);
    }

    /**
     * 结构化调用：prompt 为当前轮用户输入；history 作为 messages[] 字段独立发送，
     * 不再拼接到 prompt 里。适用于 FastAPI 需要多轮对话上下文的场景 (如 MCP agent)。
     */
    private String callAiStructured(String endpoint, String prompt, List<ChatMessage> history) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("prompt", prompt);

            if (history != null && !history.isEmpty()) {
                ArrayNode arr = requestBody.putArray("messages");
                for (ChatMessage msg : history) {
                    if (msg == null || msg.getRole() == null || msg.getContent() == null) continue;
                    String role = msg.getRole();
                    if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) continue;
                    ObjectNode item = arr.addObject();
                    item.put("role", role);
                    item.put("content", msg.getContent());
                }
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("AI request [{}] (structured): {}", endpoint, jsonBody.substring(0, Math.min(500, jsonBody.length())));

            Request request = new Request.Builder()
                    .url(aiConfig.getBaseUrl() + endpoint)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "no body";
                    log.error("AI API error [{}]: {} - {}", endpoint, response.code(), errBody);
                    throw new RuntimeException("AI API调用失败: " + response.code() + " - " + errBody);
                }
                String rawBody = response.body().string();
                StringBuilder fullResponse = new StringBuilder();
                for (String line : rawBody.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("data: ")) line = line.substring(6).trim();
                    else if (line.startsWith("data:")) line = line.substring(5).trim();
                    if (line.isEmpty() || line.equals("[DONE]")) continue;
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        if (node.has("response")) fullResponse.append(node.get("response").asText());
                        if (node.has("done") && node.get("done").asBoolean()) break;
                    } catch (Exception e) {
                        log.warn("跳过无法解析的行: {}", line);
                    }
                }
                String result = fullResponse.toString().trim();
                if (result.isEmpty()) {
                    throw new RuntimeException("AI返回内容为空, 原始响应: " + rawBody.substring(0, Math.min(200, rawBody.length())));
                }
                return result;
            }
        } catch (IOException e) {
            log.error("AI API通信异常 [{}]", endpoint, e);
            throw new RuntimeException("AI服务通信失败: " + e.getMessage(), e);
        }
    }

    /**
     * 统一调用本地LLM
     *
     * @param endpoint 接口路径, 如 /generate/chat
     * @param prompt   提示词内容
     * @param history  对话历史 (可选)
     * @return AI回复文本
     */
    private String callAi(String endpoint, String prompt, List<ChatMessage> history) {
        try {
            // 如果有历史对话, 拼接到prompt前面
            StringBuilder promptBuilder = new StringBuilder();
            if (history != null && !history.isEmpty()) {
                for (ChatMessage msg : history) {
                    if ("user".equals(msg.getRole())) {
                        promptBuilder.append("[用户] ").append(msg.getContent()).append("\n");
                    } else {
                        promptBuilder.append("[助手] ").append(msg.getContent()).append("\n");
                    }
                }
            }
            promptBuilder.append(prompt);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("prompt", promptBuilder.toString());

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("AI request [{}]: {}", endpoint, jsonBody.substring(0, Math.min(500, jsonBody.length())));

            Request request = new Request.Builder()
                    .url(aiConfig.getBaseUrl() + endpoint)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "no body";
                    log.error("AI API error [{}]: {} - {}", endpoint, response.code(), errBody);
                    throw new RuntimeException("AI API调用失败: " + response.code() + " - " + errBody);
                }

                String rawBody = response.body().string();
                log.debug("AI raw response length [{}]: {}", endpoint, rawBody.length());

                // 流式响应: 每行一个JSON, 拼接所有response字段直到done=true
                StringBuilder fullResponse = new StringBuilder();
                for (String line : rawBody.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    // 去掉SSE的 "data: " 前缀
                    if (line.startsWith("data: ")) {
                        line = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        line = line.substring(5).trim();
                    }
                    if (line.isEmpty() || line.equals("[DONE]")) continue;
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

                log.debug("AI final response [{}]: {}", endpoint, result.substring(0, Math.min(200, result.length())));
                return result;
            }
        } catch (IOException e) {
            log.error("AI API通信异常 [{}]", endpoint, e);
            throw new RuntimeException("AI服务通信失败: " + e.getMessage(), e);
        }
    }
}