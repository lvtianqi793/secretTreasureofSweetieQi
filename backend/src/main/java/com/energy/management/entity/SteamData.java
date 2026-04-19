package com.energy.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 蒸汽能耗数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "steam_data", indexes = {
    @Index(name = "idx_steam_building", columnList = "building_id"),
    @Index(name = "idx_steam_time", columnList = "monitor_time")
})
public class SteamData extends BaseEnergyData {

    /** 蒸汽用量 (lbs) */
    @Column(name = "steam_lbs")
    private Double steamLbs;
}
