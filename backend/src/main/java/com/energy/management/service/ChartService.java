package com.energy.management.service;

import com.energy.management.dto.ChartData;
import com.energy.management.dto.ChartRequest;
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

/**
 * 可视化图表数据服务
 * 生成柱状图、折线图、饼图三种图表数据, 前端可直接渲染
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartService {

    @PersistenceContext
    private EntityManager entityManager;

    /** 能源类型 -> [表名, 值列, 单位, 中文名] */
    private static final Map<String, String[]> ENERGY_MAP = Map.of(
            "electricity", new String[]{"electricity_data", "electricity_kwh", "kWh", "电力能耗"},
            "water",       new String[]{"water_data", "water_m3", "m³", "用水量"},
            "gas",         new String[]{"gas_data", "gas_therms", "therms", "天然气"},
            "steam",       new String[]{"steam_data", "steam_lbs", "lbs", "蒸汽"},
            "chilledwater",new String[]{"chilledwater_data", "chilledwater_tonhours", "ton-hours", "冷冻水"},
            "hotwater",    new String[]{"hotwater_data", "hotwater_kbtu", "kBtu", "热水"},
            "solar",       new String[]{"solar_data", "solar_kwh", "kWh", "光伏发电"},
            "irrigation",  new String[]{"irrigation_data", "irrigation_gallon", "gallon", "灌溉用水"}
    );

    private static final List<String> VALID_GRANULARITIES =
            List.of("hour", "day", "week", "month", "year");

    /**
     * 根据请求生成可视化数据
     */
    @Transactional(readOnly = true)
    public ChartData generateChart(ChartRequest request) {
        validate(request);
        String dimension = request.getDimension() == null ? "time" : request.getDimension();

        return switch (dimension) {
            case "time"     -> timeSeriesChart(request);
            case "building" -> buildingRankingChart(request);
            case "type"     -> buildingTypePieChart(request);
            default -> throw new IllegalArgumentException(
                    "不支持的维度: " + dimension + " (可选: time/building/type)");
        };
    }

    /**
     * 按时段聚合: 折线图或柱状图
     * chartType=line 时为单条折线 (按时间求和); chartType=bar 同样可用
     */
    private ChartData timeSeriesChart(ChartRequest req) {
        String[] info = getEnergyInfo(req.getEnergyType());
        String table = info[0], valueCol = info[1], unit = info[2], cnName = info[3];
        String granularity = VALID_GRANULARITIES.contains(req.getGranularity())
                ? req.getGranularity() : "day";

        StringBuilder sql = new StringBuilder(String.format(
                "SELECT DATE_TRUNC('%s', monitor_time) AS period, SUM(%s) AS total " +
                "FROM %s WHERE 1=1 ", granularity, valueCol, table));
        appendCommonFilters(sql, req);
        sql.append(" GROUP BY period ORDER BY period");

        Query query = entityManager.createNativeQuery(sql.toString());
        bindCommonParams(query, req);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<String> categories = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Object[] row : rows) {
            categories.add(row[0].toString());
            values.add(toDouble(row[1]));
        }

        String chartType = normalizeTimeChartType(req.getChartType());

        return ChartData.builder()
                .chartType(chartType)
                .title(cnName + "时段趋势 (" + granularity + ")")
                .xAxisLabel("时间")
                .yAxisLabel(cnName + " (" + unit + ")")
                .unit(unit)
                .categories(categories)
                .series(List.of(ChartData.Series.builder()
                        .name(cnName)
                        .data(values)
                        .build()))
                .build();
    }

    /**
     * 按建筑聚合: 柱状图 (Top-N)
     */
    private ChartData buildingRankingChart(ChartRequest req) {
        String[] info = getEnergyInfo(req.getEnergyType());
        String table = info[0], valueCol = info[1], unit = info[2], cnName = info[3];
        int topN = (req.getTopN() == null || req.getTopN() <= 0) ? 10 : Math.min(req.getTopN(), 100);

        StringBuilder sql = new StringBuilder(String.format(
                "SELECT building_id, SUM(%s) AS total FROM %s WHERE 1=1 ", valueCol, table));
        appendCommonFilters(sql, req);
        sql.append(" GROUP BY building_id ORDER BY total DESC LIMIT ").append(topN);

        Query query = entityManager.createNativeQuery(sql.toString());
        bindCommonParams(query, req);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<String> categories = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Object[] row : rows) {
            categories.add(row[0] == null ? "" : row[0].toString());
            values.add(toDouble(row[1]));
        }

        String chartType = "pie".equalsIgnoreCase(req.getChartType()) ? "pie" : "bar";

        List<ChartData.PiePoint> pieData = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            pieData.add(ChartData.PiePoint.builder()
                    .name(categories.get(i)).value(values.get(i)).build());
        }

        String title = "pie".equals(chartType)
                ? cnName + " Top" + topN + " 建筑占比"
                : cnName + " Top" + topN + " 建筑排名";

        return ChartData.builder()
                .chartType(chartType)
                .title(title)
                .xAxisLabel("建筑")
                .yAxisLabel(cnName + " (" + unit + ")")
                .unit(unit)
                .categories(categories)
                .series(List.of(ChartData.Series.builder()
                        .name(cnName).data(values).build()))
                .pieData(pieData)
                .build();
    }

    /**
     * 按建筑类型聚合: 饼图
     */
    private ChartData buildingTypePieChart(ChartRequest req) {
        String[] info = getEnergyInfo(req.getEnergyType());
        String table = info[0], valueCol = info[1], unit = info[2], cnName = info[3];

        StringBuilder sql = new StringBuilder(String.format(
                "SELECT building_type, SUM(%s) AS total FROM %s WHERE 1=1 ", valueCol, table));
        appendCommonFilters(sql, req);
        sql.append(" GROUP BY building_type ORDER BY total DESC");

        Query query = entityManager.createNativeQuery(sql.toString());
        bindCommonParams(query, req);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<ChartData.PiePoint> pieData = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Object[] row : rows) {
            String name = row[0] == null ? "unknown" : row[0].toString();
            double value = toDouble(row[1]);
            pieData.add(ChartData.PiePoint.builder().name(name).value(value).build());
            categories.add(name);
            values.add(value);
        }

        String chartType = req.getChartType() == null ? "pie" : req.getChartType().toLowerCase();
        if (!List.of("pie", "bar").contains(chartType)) chartType = "pie";

        return ChartData.builder()
                .chartType(chartType)
                .title(cnName + "按建筑类型占比")
                .xAxisLabel("建筑类型")
                .yAxisLabel(cnName + " (" + unit + ")")
                .unit(unit)
                .categories(categories)
                .series(List.of(ChartData.Series.builder()
                        .name(cnName).data(values).build()))
                .pieData(pieData)
                .build();
    }

    // ==================== 工具方法 ====================

    private void validate(ChartRequest req) {
        if (req.getEnergyType() == null || req.getEnergyType().isBlank()) {
            throw new IllegalArgumentException("请指定能源类型");
        }
        if (!ENERGY_MAP.containsKey(req.getEnergyType())) {
            throw new IllegalArgumentException(
                    "不支持的能源类型: " + req.getEnergyType() + ", 可选: " + ENERGY_MAP.keySet());
        }
        if (req.getChartType() != null
                && !List.of("bar", "line", "pie").contains(req.getChartType().toLowerCase())) {
            throw new IllegalArgumentException("不支持的图表类型 (可选: bar/line/pie)");
        }
    }

    private String[] getEnergyInfo(String energyType) {
        return ENERGY_MAP.get(energyType);
    }

    private String normalizeTimeChartType(String raw) {
        if (raw == null) return "line";
        String lower = raw.toLowerCase();
        return List.of("line", "bar").contains(lower) ? lower : "line";
    }

    private void appendCommonFilters(StringBuilder sql, ChartRequest req) {
        if (req.getBuildingId() != null && !req.getBuildingId().isBlank()) {
            sql.append(" AND building_id = :buildingId ");
        }
        if (req.getBuildingType() != null && !req.getBuildingType().isBlank()) {
            sql.append(" AND building_type = :buildingType ");
        }
        if (req.getStartTime() != null) {
            sql.append(" AND monitor_time >= :startTime ");
        }
        if (req.getEndTime() != null) {
            sql.append(" AND monitor_time <= :endTime ");
        }
    }

    private void bindCommonParams(Query query, ChartRequest req) {
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

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof BigDecimal bd) return bd.doubleValue();
        if (val instanceof Number num) return num.doubleValue();
        return Double.parseDouble(val.toString());
    }
}
