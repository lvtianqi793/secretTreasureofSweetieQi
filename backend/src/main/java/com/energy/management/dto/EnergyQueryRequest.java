package com.energy.management.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 能耗数据多条件查询请求
 */
@Data
public class EnergyQueryRequest {

    /** 能源类型: electricity, water, gas, steam, chilledwater, hotwater, solar, irrigation */
    private String energyType;

    /** 建筑编号 (支持模糊匹配) */
    private String buildingId;

    /** 建筑类型 */
    private String buildingType;

    /** 查询起始时间 */
    private LocalDateTime startTime;

    /** 查询结束时间 */
    private LocalDateTime endTime;

    /** 最小值 */
    private Double minValue;

    /** 最大值 */
    private Double maxValue;

    /** 页码 (从1开始) */
    private Integer page = 1;

    /** 每页大小 */
    private Integer pageSize = 20;

    /** 排序字段: monitor_time, value */
    private String sortBy = "monitor_time";

    /** 排序方向: asc, desc */
    private String sortOrder = "desc";
}
