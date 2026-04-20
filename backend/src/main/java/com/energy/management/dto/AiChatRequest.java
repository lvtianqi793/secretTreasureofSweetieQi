package com.energy.management.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.List;

/**
 * AI对话请求
 */
@Data
public class AiChatRequest {
    /** 用户自然语言问题 */
    private String question;
    /**
     * 对话历史 (可选, 用于多轮对话)
     * 兼容前端字段名 messages / history
     */
    @JsonAlias({"messages"})
    private List<ChatMessage> history;
}
