package com.energy.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 电力能耗数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "electricity_data", indexes = {
    @Index(name = "idx_elec_building", columnList = "building_id"),
    @Index(name = "idx_elec_time", columnList = "monitor_time"),
    @Index(name = "idx_elec_type", columnList = "building_type")
})
public class ElectricityData extends BaseEnergyData {

    /** 电力能耗 (kWh) */
    @Column(name = "electricity_kwh")
    private Double electricityKwh;
}
