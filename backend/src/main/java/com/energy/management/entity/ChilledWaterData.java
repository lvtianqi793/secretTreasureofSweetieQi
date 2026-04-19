package com.energy.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 冷冻水能耗数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "chilledwater_data", indexes = {
    @Index(name = "idx_cw_building", columnList = "building_id"),
    @Index(name = "idx_cw_time", columnList = "monitor_time")
})
public class ChilledWaterData extends BaseEnergyData {

    /** 冷冻水用量 (ton-hours) */
    @Column(name = "chilledwater_tonhours")
    private Double chilledwaterTonhours;
}
