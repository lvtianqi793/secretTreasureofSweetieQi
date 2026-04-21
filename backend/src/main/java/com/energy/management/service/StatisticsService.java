package com.energy.management.service;

import com.energy.management.dto.StatisticsRequest;
import com.energy.management.dto.StatisticsResult;
import com.energy.management.dto.StatisticsResult.AnomalyRecord;
import com.energy.management.dto.StatisticsResult.CopResult;
import com.energy.management.dto.StatisticsResult.SummaryInfo;
import com.energy.management.dto.StatisticsResult.TimeSeriesPoint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统计分析服务 - 实现3类核心统计:
 * 1. 时段汇总 (summary)
 * 2. COP计算 (cop)
 * 3. 数据异常分析 (anomaly)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    @PersistenceContext
    private EntityManager entityManager;

    /** 能源类型 -> 表名 + 值列名 映射 */
    private static final Map<String, String[]> ENERGY_TABLE_MAP = Map.of(
            "electricity", new String[]{"electricity_data", "electricity_kwh", "kWh"},
            "water",       new String[]{"water_data", "water_m3", "m³"},
            "gas",         new String[]{"gas_data", "gas_therms", "therms"},
            "steam",       new String[]{"steam_data", "steam_lbs", "lbs"},
            "chilledwater",new String[]{"chilledwater_data", "chilledwater_tonhours", "ton-hours"},
            "hotwater",    new String[]{"hotwater_data", "hotwater_kbtu", "kBtu"},
            "solar",       new String[]{"solar_data", "solar_kwh", "kWh"},
            "irrigation",  new String[]{"irrigation_data", "irrigation_gallon", "gallon"}
    );

    /** 统计粒度 -> PostgreSQL DATE_TRUNC格式 */
    private static final Map<String, String> GRANULARITY_MAP = Map.of(
            "hour", "hour", "day", "day", "week", "week", "month", "month", "year", "year"
    );

    /**
     * 执行统计分析
     */
    @Transactional(readOnly = true)
    public StatisticsResult analyze(StatisticsRequest request) {
        return switch (request.getAnalysisType()) {
            case "summary" -> timeSummaryAnalysis(request);
            case "cop" -> copAnalysis(request);
            case "anomaly" -> anomalyAnalysis(request);
            default -> throw new IllegalArgumentException("不支持的分析类型: " + request.getAnalysisType());
        };
    }

    /**
     * 时段汇总分析
     */
    private StatisticsResult timeSummaryAnalysis(StatisticsRequest request) {
        String[] tableInfo = getTableInfo(request.getEnergyType());
        String tableName = tableInfo[0];
        String valueCol = tableInfo[1];
        String unit = tableInfo[2];
        String granularity = GRANULARITY_MAP.getOrDefault(request.getGranularity(), "day");

        // 1. 总体汇总
        String summarySql = String.format(
                "SELECT SUM(%s), AVG(%s), MAX(%s), MIN(%s), STDDEV(%s), COUNT(*) " +
                "FROM %s WHERE 1=1 %s",
                valueCol, valueCol, valueCol, valueCol, valueCol,
                tableName, buildWhereClause(request)
        );

        Query summaryQuery = entityManager.createNativeQuery(summarySql);
        setQueryParams(summaryQuery, request);
        Object[] summaryRow = (Object[]) summaryQuery.getSingleResult();

        SummaryInfo summary = SummaryInfo.builder()
                .totalValue(toDouble(summaryRow[0]))
                .avgValue(toDouble(summaryRow[1]))
                .maxValue(toDouble(summaryRow[2]))
                .minValue(toDouble(summaryRow[3]))
                .stdDev(toDouble(summaryRow[4]))
                .recordCount(toLong(summaryRow[5]))
                .unit(unit)
                .build();

        // 2. 时间序列数据
        String timeSeriesSql = String.format(
                "SELECT DATE_TRUNC('%s', monitor_time) as period, SUM(%s) as total_value, COUNT(*) as cnt " +
                "FROM %s WHERE 1=1 %s " +
                "GROUP BY period ORDER BY period",
                granularity, valueCol, tableName, buildWhereClause(request)
        );

        Query tsQuery = entityManager.createNativeQuery(timeSeriesSql);
        setQueryParams(tsQuery, request);

        @SuppressWarnings("unchecked")
        List<Object[]> tsRows = tsQuery.getResultList();
        List<TimeSeriesPoint> timeSeries = tsRows.stream()
                .map(row -> TimeSeriesPoint.builder()
                        .timePeriod(row[0].toString())
                        .value(toDouble(row[1]))
                        .count(toLong(row[2]))
                        .build())
                .collect(Collectors.toList());

        return StatisticsResult.builder()
                .analysisType("summary")
                .summary(summary)
                .timeSeries(timeSeries)
                .build();
    }

    /**
     * COP计算 (制冷性能系数)
     * COP = 制冷量(ton-hours × 3.517 kW/ton) / 电力消耗(kWh)
     */
    private StatisticsResult copAnalysis(StatisticsRequest request) {
        String granularity = GRANULARITY_MAP.getOrDefault(request.getGranularity(), "day");
        String where = buildCopWhereClause(request);

        // 先按粒度桶各自聚合, 再按 (building_id, 桶) 对齐, 避免精确时间戳 JOIN 空集
        String copSql = String.format(
                "WITH cool AS (" +
                "  SELECT building_id, " +
                "         DATE_TRUNC('%s', monitor_time) AS period, " +
                "         SUM(chilledwater_tonhours) AS cooling_th " +
                "  FROM chilledwater_data WHERE 1=1 %s " +
                "  GROUP BY building_id, period" +
                "), elec AS (" +
                "  SELECT building_id, " +
                "         DATE_TRUNC('%s', monitor_time) AS period, " +
                "         SUM(electricity_kwh) AS elec_kwh " +
                "  FROM electricity_data WHERE 1=1 %s " +
                "  GROUP BY building_id, period" +
                ") " +
                "SELECT cool.period, cool.building_id, " +
                "       cool.cooling_th AS cooling_output, " +
                "       elec.elec_kwh   AS electric_input, " +
                "       CASE WHEN elec.elec_kwh > 0 " +
                "            THEN (cool.cooling_th * 3.517) / elec.elec_kwh " +
                "            ELSE 0 END AS cop " +
                "FROM cool JOIN elec USING (building_id, period) " +
                "ORDER BY cool.period, cool.building_id",
                granularity, where, granularity, where
        );

        Query query = entityManager.createNativeQuery(copSql);
        setCopQueryParams(query, request);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<CopResult> copResults = rows.stream()
                .map(row -> CopResult.builder()
                        .timePeriod(row[0].toString())
                        .buildingId(row[1].toString())
                        .coolingOutput(toDouble(row[2]))
                        .electricInput(toDouble(row[3]))
                        .cop(toDouble(row[4]))
                        .build())
                .collect(Collectors.toList());

        // 总体COP汇总
        double totalCooling = copResults.stream().mapToDouble(CopResult::getCoolingOutput).sum();
        double totalElectric = copResults.stream().mapToDouble(CopResult::getElectricInput).sum();
        double overallCop = totalElectric > 0 ? (totalCooling * 3.517) / totalElectric : 0;

        SummaryInfo summary = SummaryInfo.builder()
                .totalValue(overallCop)
                .avgValue(copResults.stream().mapToDouble(CopResult::getCop).average().orElse(0))
                .maxValue(copResults.stream().mapToDouble(CopResult::getCop).max().orElse(0))
                .minValue(copResults.stream().mapToDouble(CopResult::getCop).filter(v -> v > 0).min().orElse(0))
                .recordCount(copResults.size())
                .unit("COP")
                .build();

        return StatisticsResult.builder()
                .analysisType("cop")
                .summary(summary)
                .copResults(copResults)
                .build();
    }

    /**
     * 数据异常分析 - 每建筑 IQR (Tukey 箱线图法) + 数据质量规则
     *
     * 统计异常 (IQR 法, 每建筑独立基线):
     *   基线 = 该建筑中位数 Q2, 波动幅度 = Q3 - Q1
     *   偏离度 = 距离 Q1/Q3 的 IQR 倍数
     *   1.5x < 偏离 <= 3x  → 偏高/偏低
     *   偏离 > 3x          → 严重偏高/严重偏低
     *   (每建筑至少 100 条数据才参与, 否则 IQR 不可靠)
     *
     * 数据质量 (规则法):
     *   值 < 0     → 负值 (能耗不可能为负)
     *   值 IS NULL → 缺失值
     */
    private StatisticsResult anomalyAnalysis(StatisticsRequest request) {
        String[] tableInfo = getTableInfo(request.getEnergyType());
        String tableName = tableInfo[0];
        String valueCol = tableInfo[1];
        String unit = tableInfo[2];

        String where = buildWhereClause(request);

        // 总记录数 (用于汇总)
        String countSql = "SELECT COUNT(*) FROM " + tableName + " WHERE 1=1 " + where;
        Query countQuery = entityManager.createNativeQuery(countSql);
        setQueryParams(countQuery, request);
        long totalCount = toLong(countQuery.getSingleResult());

        List<AnomalyRecord> anomalies = new ArrayList<>();

        // ========== 1. 统计异常 (每建筑 IQR) ==========
        String iqrSql = String.format(
                "WITH filtered AS ( " +
                "  SELECT id, building_id, monitor_time, %s AS val " +
                "  FROM %s WHERE 1=1 %s AND %s IS NOT NULL " +
                "), stats AS ( " +
                "  SELECT building_id, " +
                "         percentile_cont(0.25) WITHIN GROUP (ORDER BY val) AS q1, " +
                "         percentile_cont(0.50) WITHIN GROUP (ORDER BY val) AS q2, " +
                "         percentile_cont(0.75) WITHIN GROUP (ORDER BY val) AS q3, " +
                "         COUNT(*) AS cnt " +
                "  FROM filtered " +
                "  GROUP BY building_id " +
                "  HAVING COUNT(*) >= 100 " +
                ") " +
                "SELECT f.id, f.building_id, f.monitor_time, f.val, " +
                "       s.q2 AS baseline, " +
                "       (s.q3 - s.q1) AS iqr, " +
                "       CASE WHEN f.val > s.q3 THEN (f.val - s.q3) / (s.q3 - s.q1) " +
                "            ELSE (s.q1 - f.val) / (s.q3 - s.q1) END AS score, " +
                "       CASE WHEN f.val > s.q3 THEN 'high' ELSE 'low' END AS direction " +
                "FROM filtered f " +
                "JOIN stats s ON f.building_id = s.building_id " +
                "WHERE (s.q3 - s.q1) > 0 " +
                "  AND ( f.val > s.q3 + 1.5 * (s.q3 - s.q1) " +
                "     OR f.val < s.q1 - 1.5 * (s.q3 - s.q1) ) " +
                "ORDER BY score DESC " +
                "LIMIT 500",
                valueCol, tableName, where, valueCol
        );
        Query iqrQuery = entityManager.createNativeQuery(iqrSql);
        setQueryParams(iqrQuery, request);

        @SuppressWarnings("unchecked")
        List<Object[]> iqrRows = iqrQuery.getResultList();
        for (Object[] row : iqrRows) {
            double value = toDouble(row[3]);
            double baseline = toDouble(row[4]);
            double iqr = toDouble(row[5]);
            double score = toDouble(row[6]);
            String direction = row[7] != null ? row[7].toString() : "high";
            String type;
            if ("high".equals(direction)) {
                type = score > 3 ? "严重偏高" : "偏高";
            } else {
                type = score > 3 ? "严重偏低" : "偏低";
            }
            anomalies.add(AnomalyRecord.builder()
                    .dataId(toLong(row[0]))
                    .buildingId(row[1].toString())
                    .monitorTime(row[2].toString())
                    .value(value)
                    .mean(baseline)
                    .stdDev(iqr)
                    .zScore(score)
                    .category("统计异常")
                    .anomalyType(type)
                    .build());
        }

        // ========== 2. 数据质量 (负值 / 缺失) ==========
        String qualitySql = String.format(
                "SELECT id, building_id, monitor_time, %s AS val, " +
                "       CASE WHEN %s IS NULL THEN '缺失值' ELSE '负值' END AS qtype " +
                "FROM %s WHERE 1=1 %s " +
                "  AND (%s IS NULL OR %s < 0) " +
                "ORDER BY monitor_time DESC " +
                "LIMIT 200",
                valueCol, valueCol, tableName, where, valueCol, valueCol
        );
        Query qualityQuery = entityManager.createNativeQuery(qualitySql);
        setQueryParams(qualityQuery, request);

        @SuppressWarnings("unchecked")
        List<Object[]> qualityRows = qualityQuery.getResultList();
        for (Object[] row : qualityRows) {
            double value = row[3] == null ? 0.0 : toDouble(row[3]);
            String qtype = row[4] != null ? row[4].toString() : "负值";
            anomalies.add(AnomalyRecord.builder()
                    .dataId(toLong(row[0]))
                    .buildingId(row[1].toString())
                    .monitorTime(row[2] != null ? row[2].toString() : "")
                    .value(value)
                    .mean(0)
                    .stdDev(0)
                    .zScore(0)
                    .category("数据质量")
                    .anomalyType(qtype)
                    .build());
        }

        // 汇总
        long stats异常 = anomalies.stream().filter(a -> "统计异常".equals(a.getCategory())).count();
        long quality异常 = anomalies.stream().filter(a -> "数据质量".equals(a.getCategory())).count();
        log.info("异常检测完成: 统计异常={}, 数据质量={}, 总记录={}", stats异常, quality异常, totalCount);

        SummaryInfo summary = SummaryInfo.builder()
                .totalValue(anomalies.size())
                .avgValue(stats异常)
                .stdDev(quality异常)
                .recordCount(totalCount)
                .unit(unit)
                .build();

        return StatisticsResult.builder()
                .analysisType("anomaly")
                .summary(summary)
                .anomalies(anomalies)
                .build();
    }

    // ==================== 工具方法 ====================

    private String[] getTableInfo(String energyType) {
        String[] info = ENERGY_TABLE_MAP.get(energyType);
        if (info == null) {
            throw new IllegalArgumentException("不支持的能源类型: " + energyType
                    + ", 可选值: " + ENERGY_TABLE_MAP.keySet());
        }
        return info;
    }

    private String buildWhereClause(StatisticsRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.getBuildingId() != null && !req.getBuildingId().isBlank()) {
            sb.append(" AND building_id = :buildingId");
        }
        if (req.getBuildingType() != null && !req.getBuildingType().isBlank()) {
            sb.append(" AND building_type = :buildingType");
        }
        if (req.getStartTime() != null) {
            sb.append(" AND monitor_time >= :startTime");
        }
        if (req.getEndTime() != null) {
            sb.append(" AND monitor_time <= :endTime");
        }
        return sb.toString();
    }

    private String buildCopWhereClause(StatisticsRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.getBuildingId() != null && !req.getBuildingId().isBlank()) {
            sb.append(" AND building_id = :buildingId");
        }
        if (req.getStartTime() != null) {
            sb.append(" AND monitor_time >= :startTime");
        }
        if (req.getEndTime() != null) {
            sb.append(" AND monitor_time <= :endTime");
        }
        return sb.toString();
    }

    private void setQueryParams(Query query, StatisticsRequest req) {
        if (req.getBuildingId() != null && !req.getBuildingId().isBlank()) {
            query.setParameter("buildingId", req.getBuildingId());
        }
        if (req.getBuildingType() != null && !req.getBuildingType().isBlank()) {
            query.setParameter("buildingType", req.getBuildingType());
        }
        if (req.getStartTime() != null) {
            query.setParameter("startTime", req.getStartTime());
        }
        if (req.getEndTime() != null) {
            query.setParameter("endTime", req.getEndTime());
        }
    }

    private void setCopQueryParams(Query query, StatisticsRequest req) {
        if (req.getBuildingId() != null && !req.getBuildingId().isBlank()) {
            query.setParameter("buildingId", req.getBuildingId());
        }
        if (req.getStartTime() != null) {
            query.setParameter("startTime", req.getStartTime());
        }
        if (req.getEndTime() != null) {
            query.setParameter("endTime", req.getEndTime());
        }
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof BigDecimal bd) return bd.doubleValue();
        if (val instanceof Number num) return num.doubleValue();
        return Double.parseDouble(val.toString());
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number num) return num.longValue();
        return Long.parseLong(val.toString());
    }

    /**
     * 获取所有支持的能源类型
     */
    public List<String> getSupportedEnergyTypes() {
        return new ArrayList<>(ENERGY_TABLE_MAP.keySet());
    }
}
