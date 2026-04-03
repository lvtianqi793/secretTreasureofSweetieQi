package com.energy.management.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 气象数据 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "weather_data", indexes = {
    @Index(name = "idx_wea_building", columnList = "building_id"),
    @Index(name = "idx_wea_time", columnList = "monitor_time")
})
public class WeatherData extends BaseEnergyData {

    /** 温度 (°F) */
    @Column(name = "temperature_f")
    private Double temperatureF;

    /** 湿度 (%RH) */
    @Column(name = "humidity_pct")
    private Double humidityPct;
}
