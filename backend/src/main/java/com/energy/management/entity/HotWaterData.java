package com.energy.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 热水能耗数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "hotwater_data", indexes = {
    @Index(name = "idx_hw_building", columnList = "building_id"),
    @Index(name = "idx_hw_time", columnList = "monitor_time")
})
public class HotWaterData extends BaseEnergyData {

    /** 热水用量 (kBtu) */
    @Column(name = "hotwater_kbtu")
    private Double hotwaterKbtu;
}
