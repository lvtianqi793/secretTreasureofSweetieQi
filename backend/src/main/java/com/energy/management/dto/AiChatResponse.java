package com.energy.management.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI对话响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {
    /** AI回复文本 */
    private String answer;
    /** 是否执行了数据库查询 */
    private boolean queryExecuted;
    /** 执行的SQL (调试用, 生产环境可隐藏) */
    private String executedSql;
    /** 查询到的数据 (如果有) */
    private List<Map<String, Object>> queryData;
    /** 图表类型建议: bar, line, pie, table, none */
    private String suggestedChart;
}
