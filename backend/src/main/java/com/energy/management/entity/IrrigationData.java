package com.energy.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 灌溉用水数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "irrigation_data", indexes = {
    @Index(name = "idx_irr_building", columnList = "building_id"),
    @Index(name = "idx_irr_time", columnList = "monitor_time")
})
public class IrrigationData extends BaseEnergyData {

    /** 灌溉用水量 (gallon) */
    @Column(name = "irrigation_gallon")
    private Double irrigationGallon;
}
