package com.energy.management.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 天然气能耗数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "gas_data", indexes = {
    @Index(name = "idx_gas_building", columnList = "building_id"),
    @Index(name = "idx_gas_time", columnList = "monitor_time")
})
public class GasData extends BaseEnergyData {

    /** 天然气用量 (therms) */
    @Column(name = "gas_therms")
    private Double gasTherms;
}
