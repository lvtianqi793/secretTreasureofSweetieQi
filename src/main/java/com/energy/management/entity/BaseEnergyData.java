package com.energy.management.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 能耗数据基类 - 所有能耗表的公共字段
 */
@Data
@MappedSuperclass
public abstract class BaseEnergyData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 建筑编号 */
    @Column(name = "building_id", nullable = false, length = 100)
    private String buildingId;

    /** 建筑类型 */
    @Column(name = "building_type", nullable = false, length = 50)
    private String buildingType;

    /** 监测时间 */
    @Column(name = "monitor_time", nullable = false)
    private LocalDateTime monitorTime;
}
