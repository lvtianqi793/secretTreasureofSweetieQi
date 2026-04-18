package com.energy.management.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图表数据请求
 */
@Data
public class ChartRequest {

    /** 能源类型: electricity, water, gas, steam, chilledwater, hotwater, solar, irrigation */
    private String energyType;

    /** 图表类型: bar(柱状), line(折线), pie(饼图) */
    private String chartType = "line";

    /**
     * 聚合维度:
     *   time     - 按时段汇总 (配合 granularity), 适合折线/柱状
     *   building - 按建筑排名, 适合柱状/饼图
     *   type     - 按建筑类型占比, 适合饼图
     */
    private String dimension = "time";

    /** 建筑编号 (可选) */
    private String buildingId;

    /** 建筑类型 (可选) */
    private String buildingType;

    /** 起始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 时段粒度 (dimension=time 时生效): hour/day/week/month/year */
    private String granularity = "day";

    /** 返回的最大条目数 (dimension=building/type 时生效) */
    private Integer topN = 10;
}
