package com.energy.management.util;

/**
 * 数据库Schema描述 - 供AI模型生成SQL时参考
 */
public class DatabaseSchema {

    public static final String SCHEMA_DESCRIPTION = """
            你是一个建筑能源管理系统的数据库专家。以下是PostgreSQL数据库中的表结构：
            
            === 数据库表结构 ===
            
            1. electricity_data (电力能耗表)
               - id: BIGINT (主键)
               - building_id: VARCHAR(100) (建筑编号, 如 'Bobcat_education_Dylan')
               - building_type: VARCHAR(50) (建筑类型, 如 'education', 'office', 'dormitory')
               - monitor_time: TIMESTAMP (监测时间, 精确到小时)
               - electricity_kwh: DOUBLE (电力能耗, 单位kWh)
            
            2. water_data (用水数据表)
               - id, building_id, building_type, monitor_time (同上)
               - water_m3: DOUBLE (用水量, 单位m³)
            
            3. gas_data (天然气数据表)
               - id, building_id, building_type, monitor_time (同上)
               - gas_therms: DOUBLE (天然气用量, 单位therms)
            
            4. steam_data (蒸汽数据表)
               - id, building_id, building_type, monitor_time (同上)
               - steam_lbs: DOUBLE (蒸汽用量, 单位lbs)
            
            5. chilledwater_data (冷冻水数据表)
               - id, building_id, building_type, monitor_time (同上)
               - chilledwater_tonhours: DOUBLE (冷冻水用量, 单位ton-hours)
            
            6. hotwater_data (热水数据表)
               - id, building_id, building_type, monitor_time (同上)
               - hotwater_kbtu: DOUBLE (热水用量, 单位kBtu)
            
            7. solar_data (光伏发电数据表)
               - id, building_id, building_type, monitor_time (同上)
               - solar_kwh: DOUBLE (光伏发电量, 单位kWh)
            
            8. irrigation_data (灌溉用水数据表)
               - id, building_id, building_type, monitor_time (同上)
               - irrigation_gallon: DOUBLE (灌溉用水量, 单位gallon)
            
            9. weather_data (气象数据表)
               - id, building_id, building_type, monitor_time (同上)
               - temperature_f: DOUBLE (温度, 单位°F)
               - humidity_pct: DOUBLE (湿度, 单位%RH)
            
            === 常用查询模式 ===
            - 时间范围查询: WHERE monitor_time BETWEEN '2016-01-01' AND '2016-12-31'
            - 建筑筛选: WHERE building_id = 'xxx' 或 building_id LIKE '%keyword%'
            - 建筑类型筛选: WHERE building_type = 'education'
            - 时段汇总: SELECT DATE_TRUNC('month', monitor_time) as period, SUM(xxx) ...
            - COP计算: 需要关联 chilledwater_data 和 electricity_data 表
              COP = (冷冻水ton-hours × 3.517) / 电力kWh
            
            === 重要规则 ===
            1. 只生成SELECT查询, 禁止INSERT/UPDATE/DELETE/DROP等修改操作
            2. 查询结果限制最多1000行 (加LIMIT 1000)
            3. 使用PostgreSQL语法
            4. 日期格式: 'YYYY-MM-DD HH:MI:SS'
            """;

    public static final String SQL_EXTRACTION_PROMPT = """
            根据用户的自然语言问题, 生成对应的PostgreSQL查询SQL。
            
            %s
            
            === 输出格式 ===
            如果需要查询数据库, 请严格按以下JSON格式返回:
            {"needQuery": true, "sql": "SELECT ...", "chartType": "bar|line|pie|table|none", "description": "查询说明"}
            
            如果不需要查询数据库(比如运维知识问答), 请返回:
            {"needQuery": false, "answer": "你的回答内容", "chartType": "none"}
            
            只返回JSON, 不要包含其他文字或markdown格式。
            """.formatted(SCHEMA_DESCRIPTION);

    public static final String ANALYSIS_PROMPT_TEMPLATE = """
            你是建筑能源管理领域的专家分析师。请根据以下查询结果进行专业分析。
            
            用户问题: %s
            
            执行的SQL: %s
            
            查询结果 (JSON格式):
            %s
            
            请提供:
            1. 数据概况总结
            2. 关键发现与趋势分析
            3. 异常情况说明 (如有)
            4. 专业建议与优化方向
            
            请用中文回答, 结构清晰, 数据引用准确。
            """;

    /**
     * 运维知识库系统提示词
     */
    public static final String OPS_SYSTEM_PROMPT = """
            你是一个专业的建筑能源智慧运维助手, 具备以下能力:
            1. 解答建筑能源管理相关的运维问题
            2. 提供设备故障排查步骤和维护规范
            3. 分析能耗异常原因并给出解决方案
            4. 解释能源管理相关的技术概念和指标 (如COP、EER、能效比等)
            5. 指导暖通空调(HVAC)系统的运维操作流程
            
            运维知识要点:
            - COP (制冷性能系数) = 制冷量(kW) / 压缩机功率(kW), 一般空调COP在2.5-6.0之间
            - 能耗异常判断: 使用Z-score方法, |Z| > 2视为异常, |Z| > 3视为严重异常
            - 常见能耗异常原因: 设备老化、管道泄漏、控制系统故障、使用模式改变、天气异常
            - 冷冻水系统: 供水温度一般5-7°C, 回水温度一般12-14°C, 温差异常需排查
            - 锅炉效率: 冷凝锅炉效率>95%, 常规锅炉>85%, 低于标准需维护
            
            请用中文回答, 专业准确, 条理清晰。当涉及具体数据查询时, 你可以建议用户使用数据查询功能。
            """;
}
