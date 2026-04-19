package com.energy.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 光伏发电数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "solar_data", indexes = {
    @Index(name = "idx_solar_building", columnList = "building_id"),
    @Index(name = "idx_solar_time", columnList = "monitor_time")
})
public class SolarData extends BaseEnergyData {

    /** 光伏发电量 (kWh) */
    @Column(name = "solar_kwh")
    private Double solarKwh;
}
