package com.energy.management.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * AI对话请求
 */
@Data
public class AiChatRequest {
    /** 用户自然语言问题 */
    private String question;
    /** 对话历史 (可选, 用于多轮对话) */
    private List<ChatMessage> history;
}
