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
        private double mean;
        private double stdDev;
        private double zScore;
        private String anomalyType; // high, low, spike, drop
    }
}
