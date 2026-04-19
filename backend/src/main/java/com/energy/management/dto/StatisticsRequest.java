package com.energy.management.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统计分析请求
 */
@Data
public class StatisticsRequest {

    /** 能源类型 */
    private String energyType;

    /** 建筑编号 (可选, 不填则全部) */
    private String buildingId;

    /** 建筑类型 (可选) */
    private String buildingType;

    /** 起始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /**
     * 统计粒度: hour, day, week, month, year
     */
    private String granularity = "day";

    /**
     * 统计类型: summary(时段汇总), cop(COP计算), anomaly(异常分析)
     */
    private String analysisType = "summary";
}
