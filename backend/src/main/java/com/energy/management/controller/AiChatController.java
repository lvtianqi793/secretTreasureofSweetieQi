package com.energy.management.controller;

import com.energy.management.dto.AiChatRequest;
import com.energy.management.dto.AiChatResponse;
import com.energy.management.dto.ApiResponse;
import com.energy.management.service.AiChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI智能对话接口
 * 提供自然语言数据查询和运维知识问答两大核心能力
 */
@Tag(name = "AI智能对话", description = "大模型驱动的自然语言查询与智慧运维")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * 智能对话 (数据查询 + 分析)
     * 流程: 用户问题 -> AI生成SQL -> 执行查询 -> AI分析结果 -> 返回
     */
    @Operation(summary = "AI数据查询对话", description = "支持自然语言查询能耗数据并获得AI分析")
    @PostMapping("/chat")
    public ApiResponse<AiChatResponse> chat(@RequestBody AiChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ApiResponse.error(400, "请输入您的问题");
        }
        AiChatResponse response = aiChatService.chat(request);
        return ApiResponse.success(response);
    }

    /**
     * 运维知识问答 (不查数据库, 纯知识对话)
     * 适用于: 设备故障排查、运维规范指导、概念解释等
     */
    @Operation(summary = "运维知识问答", description = "基于领域知识库的运维问题解答")
    @PostMapping("/ops")
    public ApiResponse<AiChatResponse> opsChat(@RequestBody AiChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ApiResponse.error(400, "请输入您的问题");
        }
        AiChatResponse response = aiChatService.opsChat(request);
        return ApiResponse.success(response);
    }
}
