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
import java.util.Collections;
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

        String copSql = String.format(
                "SELECT DATE_TRUNC('%s', c.monitor_time) as period, " +
                "c.building_id, " +
                "SUM(c.chilledwater_tonhours) as cooling_output, " +
                "SUM(e.electricity_kwh) as electric_input, " +
                "CASE WHEN SUM(e.electricity_kwh) > 0 " +
                "  THEN (SUM(c.chilledwater_tonhours) * 3.517) / SUM(e.electricity_kwh) " +
                "  ELSE 0 END as cop " +
                "FROM chilledwater_data c " +
                "JOIN electricity_data e ON c.building_id = e.building_id " +
                "  AND c.monitor_time = e.monitor_time " +
                "WHERE 1=1 %s " +
                "GROUP BY period, c.building_id " +
                "ORDER BY period, c.building_id",
                granularity, buildCopWhereClause(request)
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
     * 数据异常分析 (Z-score方法)
     * |Z-score| > 2 视为异常, > 3 视为严重异常
     */
    private StatisticsResult anomalyAnalysis(StatisticsRequest request) {
        String[] tableInfo = getTableInfo(request.getEnergyType());
        String tableName = tableInfo[0];
        String valueCol = tableInfo[1];
        String unit = tableInfo[2];

        // 计算均值和标准差
        String statsSql = String.format(
                "SELECT AVG(%s), STDDEV(%s), COUNT(*) FROM %s WHERE 1=1 %s",
                valueCol, valueCol, tableName, buildWhereClause(request)
        );
        Query statsQuery = entityManager.createNativeQuery(statsSql);
        setQueryParams(statsQuery, request);
        Object[] statsRow = (Object[]) statsQuery.getSingleResult();

        double mean = toDouble(statsRow[0]);
        double stdDev = toDouble(statsRow[1]);
        long totalCount = toLong(statsRow[2]);

        if (stdDev == 0) {
            return StatisticsResult.builder()
                    .analysisType("anomaly")
                    .summary(SummaryInfo.builder()
                            .avgValue(mean).stdDev(0).recordCount(totalCount).unit(unit).build())
                    .anomalies(Collections.emptyList())
                    .build();
        }

        // 查找Z-score > 2的异常数据
        String anomalySql = String.format(
                "SELECT id, building_id, monitor_time, %s, " +
                "(%s - %f) / %f as z_score " +
                "FROM %s WHERE 1=1 %s " +
                "AND ABS((%s - %f) / %f) > 2 " +
                "ORDER BY ABS((%s - %f) / %f) DESC LIMIT 500",
                valueCol,
                valueCol, mean, stdDev,
                tableName, buildWhereClause(request),
                valueCol, mean, stdDev,
                valueCol, mean, stdDev
        );

        Query anomalyQuery = entityManager.createNativeQuery(anomalySql);
        setQueryParams(anomalyQuery, request);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = anomalyQuery.getResultList();
        List<AnomalyRecord> anomalies = rows.stream()
                .map(row -> {
                    double value = toDouble(row[3]);
                    double zScore = toDouble(row[4]);
                    String anomalyType;
                    if (zScore > 3) anomalyType = "严重偏高";
                    else if (zScore > 2) anomalyType = "偏高";
                    else if (zScore < -3) anomalyType = "严重偏低";
                    else anomalyType = "偏低";

                    return AnomalyRecord.builder()
                            .dataId(toLong(row[0]))
                            .buildingId(row[1].toString())
                            .monitorTime(row[2].toString())
                            .value(value)
                            .mean(mean)
                            .stdDev(stdDev)
                            .zScore(zScore)
                            .anomalyType(anomalyType)
                            .build();
                })
                .collect(Collectors.toList());

        SummaryInfo summary = SummaryInfo.builder()
                .totalValue(anomalies.size())
                .avgValue(mean)
                .stdDev(stdDev)
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
            sb.append(" AND c.building_id = :buildingId");
        }
        if (req.getStartTime() != null) {
            sb.append(" AND c.monitor_time >= :startTime");
        }
        if (req.getEndTime() != null) {
            sb.append(" AND c.monitor_time <= :endTime");
        }
        return sb.toString();
    }

    private void setQueryParams(Query query, StatisticsRequest req) {
        String sql = query.unwrap(org.hibernate.query.NativeQuery.class).getQueryString();
        if (req.getBuildingId() != null && !req.getBuildingId().isBlank() && sql.contains(":buildingId")) {
            query.setParameter("buildingId", req.getBuildingId());
        }
        if (req.getBuildingType() != null && !req.getBuildingType().isBlank() && sql.contains(":buildingType")) {
            query.setParameter("buildingType", req.getBuildingType());
        }
        if (req.getStartTime() != null && sql.contains(":startTime")) {
            query.setParameter("startTime", req.getStartTime());
        }
        if (req.getEndTime() != null && sql.contains(":endTime")) {
            query.setParameter("endTime", req.getEndTime());
        }
    }

    private void setCopQueryParams(Query query, StatisticsRequest req) {
        setQueryParams(query, req);
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
