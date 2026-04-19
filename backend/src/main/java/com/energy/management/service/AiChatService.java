package com.energy.management.service;

import com.energy.management.dto.AiChatRequest;
import com.energy.management.dto.AiChatResponse;
import com.energy.management.dto.ChatMessage;
import com.energy.management.util.DatabaseSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * AI智能对话服务 - 核心业务流程:
 * 1. 接收用户自然语言问题
 * 2. 调用 /generate/generatesql 获取SQL
 * 3. 执行SQL查询数据库
 * 4. 调用 /generate/analyse 分析查询结果
 * 5. 返回最终结果给前端
 *
 * 运维知识问答: 直接调用 /generate/chat
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    /** SQL安全白名单: 仅允许SELECT查询 */
    private static final Pattern UNSAFE_SQL_PATTERN = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|EXEC|EXECUTE|GRANT|REVOKE)\\b"
    );

    /** SQL最大返回行数 */
    private static final int MAX_ROWS = 1000;

    /**
     * 处理用户AI对话请求 (核心流程)
     */
    @Transactional(readOnly = true)
    public AiChatResponse chat(AiChatRequest request) {
        String question = request.getQuestion();
        log.info("收到用户问题: {}", question);

        try {
            // === 第一步: 调用 /generate/generatesql 生成SQL ===
            String sqlPrompt = DatabaseSchema.SQL_EXTRACTION_PROMPT + "\n\n用户问题: " + question;
            String aiFirstResponse = aiService.generateSql(sqlPrompt, request.getHistory());

            log.debug("AI第一轮回复: {}", aiFirstResponse);

            // 解析AI返回的JSON
            JsonNode parsed = parseAiJson(aiFirstResponse);

            boolean needQuery = parsed.has("needQuery") && parsed.get("needQuery").asBoolean();
            String chartType = parsed.has("chartType") ? parsed.get("chartType").asText("none") : "none";

            if (!needQuery) {
                // 不需要查询数据库, 直接返回AI回答 (运维知识问答)
                String answer = parsed.has("answer") ? parsed.get("answer").asText() : aiFirstResponse;
                return new AiChatResponse(answer, false, null, null, chartType);
            }

            // === 第二步: 提取SQL并执行 ===
            String sql = parsed.get("sql").asText();
            log.info("AI生成的SQL: {}", sql);

            // 安全校验
            if (!validateSql(sql)) {
                return new AiChatResponse(
                        "检测到不安全的SQL操作，已拒绝执行。系统仅支持数据查询操作。",
                        false, sql, null, "none"
                );
            }

            // 执行SQL查询
            List<Map<String, Object>> queryResults = executeNativeQuery(sql);
            log.info("查询返回 {} 条记录", queryResults.size());

            // === 第三步: 调用 /generate/analyse 分析查询结果 ===
            String resultJson = objectMapper.writeValueAsString(queryResults);
            // 截断过长的结果 (避免超出token限制)
            if (resultJson.length() > 8000) {
                resultJson = resultJson.substring(0, 8000) + "...(数据已截断, 共" + queryResults.size() + "条)";
            }

            String analysisPrompt = String.format(
                    DatabaseSchema.ANALYSIS_PROMPT_TEMPLATE,
                    question, sql, resultJson
            );

            String analysisResponse;
            try {
                analysisResponse = aiService.analyse(analysisPrompt);
            } catch (Exception analyseEx) {
                log.warn("AI分析接口调用失败，降级到运维知识接口: {}", analyseEx.getMessage());
                // 降级: 将查询到的数据传递给运维知识接口
                String dataSummaryPrompt = String.format(
                        "用户问题: %s\n\n" +
                        "已执行的SQL: %s\n\n" +
                        "查询到的数据(共%d条):\n%s\n\n" +
                        "请基于你的建筑能源运维知识，对上述数据进行分析和解读，回答用户的问题。",
                        question, sql, queryResults.size(), resultJson
                );
                analysisResponse = aiService.chatOps(dataSummaryPrompt, null) +
                        "\n\n(注: AI数据分析服务暂不可用，以上为基于运维知识库的分析)";
            }

            return new AiChatResponse(
                    analysisResponse,
                    true,
                    sql,
                    queryResults.size() > 200 ? queryResults.subList(0, 200) : queryResults,
                    chartType
            );

        } catch (Exception e) {
            log.error("AI对话处理异常", e);
            // 降级: 尝试用运维知识接口直接回答
            try {
                String fallbackAnswer = aiService.chatOps(
                        "用户问题: " + question + "\n\n请基于你的建筑能源运维知识直接回答, 不需要查询数据库。",
                        null
                );
                return new AiChatResponse(
                        fallbackAnswer + "\n\n(注: 数据查询暂时不可用, 以上为基于知识库的回答)",
                        false, null, null, "none"
                );
            } catch (Exception fallbackEx) {
                return new AiChatResponse(
                        "处理您的问题时遇到异常: " + e.getMessage() + "\n请尝试换一种方式提问。",
                        false, null, null, "none"
                );
            }
        }
    }

    /**
     * 运维知识问答 (调用 /generate/chat, 不涉及数据库查询)
     */
    public AiChatResponse opsChat(AiChatRequest request) {
        try {
            String answer = aiService.chatOps(request.getQuestion(), request.getHistory());
            return new AiChatResponse(answer, false, null, null, "none");
        } catch (Exception e) {
            log.error("运维问答异常", e);
            return new AiChatResponse("运维助手暂时不可用: " + e.getMessage(),
                    false, null, null, "none");
        }
    }

    /**
     * 解析AI返回的JSON (兼容markdown代码块包裹)
     */
    private JsonNode parseAiJson(String raw) {
        try {
            String cleaned = raw.trim();
            // 去除markdown代码块标记
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            // 尝试提取JSON对象
            int braceStart = cleaned.indexOf('{');
            int braceEnd = cleaned.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                cleaned = cleaned.substring(braceStart, braceEnd + 1);
            }

            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("AI返回非标准JSON, 尝试兜底处理: {}", raw);
            // 兜底: 当作纯文本回答
            var node = objectMapper.createObjectNode();
            node.put("needQuery", false);
            node.put("answer", raw);
            node.put("chartType", "none");
            return node;
        }
    }

    /**
     * SQL安全校验
     */
    private boolean validateSql(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String upper = sql.toUpperCase().trim();
        // 必须以SELECT开头
        if (!upper.startsWith("SELECT")) {
            log.warn("SQL安全拦截: 非SELECT语句 - {}", sql);
            return false;
        }
        // 检查是否包含危险关键词
        if (UNSAFE_SQL_PATTERN.matcher(sql).find()) {
            log.warn("SQL安全拦截: 包含危险操作 - {}", sql);
            return false;
        }
        return true;
    }

    /**
     * 执行原生SQL查询, 返回List<Map>结构
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executeNativeQuery(String sql) {
        // 强制加上LIMIT限制
        String safeSql = sql.trim();
        if (safeSql.endsWith(";")) {
            safeSql = safeSql.substring(0, safeSql.length() - 1);
        }
        String upperSql = safeSql.toUpperCase();
        if (!upperSql.contains("LIMIT")) {
            safeSql = safeSql + " LIMIT " + MAX_ROWS;
        }

        Query query = entityManager.createNativeQuery(safeSql);

        // 获取结果 (Hibernate返回Object[]列表)
        List<Object[]> rawResults = query.getResultList();
        if (rawResults.isEmpty()) {
            return Collections.emptyList();
        }

        // 从SQL中提取列名
        List<String> columnNames = extractColumnNames(safeSql);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Object row : rawResults) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (row instanceof Object[] cols) {
                for (int i = 0; i < cols.length; i++) {
                    String colName = i < columnNames.size() ? columnNames.get(i) : "col_" + i;
                    map.put(colName, cols[i]);
                }
            } else {
                // 单列查询
                String colName = columnNames.isEmpty() ? "value" : columnNames.get(0);
                map.put(colName, row);
            }
            results.add(map);
        }
        return results;
    }

    /**
     * 从SQL中提取列名 (简化版解析)
     */
    private List<String> extractColumnNames(String sql) {
        List<String> names = new ArrayList<>();
        try {
            String upper = sql.toUpperCase();
            int selectIdx = upper.indexOf("SELECT") + 6;
            int fromIdx = findMainFromIndex(upper);
            if (fromIdx <= selectIdx) return names;

            String selectClause = sql.substring(selectIdx, fromIdx).trim();
            // 处理DISTINCT
            if (selectClause.toUpperCase().startsWith("DISTINCT")) {
                selectClause = selectClause.substring(8).trim();
            }

            String[] parts = splitRespectingParentheses(selectClause);
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;

                // 检查AS别名
                String upperPart = part.toUpperCase();
                int asIdx = findAsKeyword(upperPart);
                if (asIdx > 0) {
                    String alias = part.substring(asIdx + 3).trim().replaceAll("[\"']", "");
                    names.add(alias);
                } else {
                    // 取最后一个点号后的名称, 或函数后的名称
                    String name = part;
                    int dotIdx = name.lastIndexOf('.');
                    if (dotIdx >= 0) {
                        name = name.substring(dotIdx + 1);
                    }
                    name = name.replaceAll("[\"'`]", "").trim();
                    names.add(name);
                }
            }
        } catch (Exception e) {
            log.debug("列名解析异常, 使用默认列名", e);
        }
        return names;
    }

    /** 查找主FROM子句位置 (跳过子查询中的FROM) */
    private int findMainFromIndex(String upperSql) {
        int depth = 0;
        for (int i = 0; i < upperSql.length() - 4; i++) {
            char c = upperSql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && upperSql.startsWith("FROM", i)
                    && (i == 0 || !Character.isLetterOrDigit(upperSql.charAt(i - 1)))
                    && (i + 4 >= upperSql.length() || !Character.isLetterOrDigit(upperSql.charAt(i + 4)))) {
                return i;
            }
        }
        return upperSql.length();
    }

    /** 按逗号拆分但不拆括号内的逗号 */
    private String[] splitRespectingParentheses(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts.toArray(new String[0]);
    }

    /** 查找AS关键字位置 (不在括号内) */
    private int findAsKeyword(String upper) {
        int depth = 0;
        for (int i = 0; i < upper.length() - 3; i++) {
            char c = upper.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && upper.startsWith(" AS ", i)) {
                return i;
            }
        }
        return -1;
    }
}