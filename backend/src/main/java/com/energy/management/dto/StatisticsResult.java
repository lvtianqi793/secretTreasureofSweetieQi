package com.energy.management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统计分析结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsResult {

    /** 统计类型 */
    private String analysisType;

    /** 汇总信息 */
    private SummaryInfo summary;

    /** 时间序列数据 (用于图表) */
    private List<TimeSeriesPoint> timeSeries;

    /** COP计算结果 (仅COP分析时返回) */
    private List<CopResult> copResults;

    /** 异常检测结果 (仅异常分析时返回) */
    private List<AnomalyRecord> anomalies;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryInfo {
        private double totalValue;
        private double avgValue;
        private double maxValue;
        private double minValue;
        private double stdDev;
        private long recordCount;
        private String unit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSeriesPoint {
        private String timePeriod;
        private double value;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CopResult {
        private String timePeriod;
        private String buildingId;
        private double coolingOutput;   // 制冷量 (ton-hours)
        private double electricInput;   // 电力输入 (kWh)
        private double cop;             // COP = coolingOutput * 3.517 / electricInput
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnomalyRecord {
        private Long dataId;
        private String buildingId;
        private String monitorTime;
        private double value;
        /** 基线 (IQR法下为该建筑的中位数 Q2) */
        private double mean;
        /** 波动幅度 (IQR法下为 Q3-Q1) */
        private double stdDev;
        /** 偏离度 (IQR法下为距离 Q1/Q3 的 IQR 倍数) */
        private double zScore;
        /** 异常分类: 统计异常 / 数据质量 */
        private String category;
        /** 具体类型: 严重偏高/偏高/严重偏低/偏低/负值/缺失值 */
        private String anomalyType;
    }
}
