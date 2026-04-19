package com.energy.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用水数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "water_data", indexes = {
    @Index(name = "idx_water_building", columnList = "building_id"),
    @Index(name = "idx_water_time", columnList = "monitor_time")
})
public class WaterData extends BaseEnergyData {

    /** 用水量 (m³) */
    @Column(name = "water_m3")
    private Double waterM3;
}
