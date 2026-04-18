package com.energy.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用可视化图表数据 (前端 ECharts / Chart.js 可直接渲染)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartData {

    /** 图表类型: bar, line, pie */
    private String chartType;

    /** 图表标题 */
    private String title;

    /** X轴标签 (柱状/折线) */
    private String xAxisLabel;

    /** Y轴标签 (柱状/折线) */
    private String yAxisLabel;

    /** 计量单位 */
    private String unit;

    /** X轴类目 (时间段 / 建筑名), 饼图时为空 */
    private List<String> categories;

    /** 数据系列 */
    private List<Series> series;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Series {
        /** 系列名 */
        private String name;
        /** 数据点 (与 categories 对齐, 饼图时 name+value 使用下方 pieData) */
        private List<Double> data;
    }

    /** 饼图数据 (与 series 互斥) */
    private List<PiePoint> pieData;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PiePoint {
        private String name;
        private double value;
    }
}
