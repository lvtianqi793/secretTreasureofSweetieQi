package com.energy.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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

    /** 环境温度 (°C) */
    @Column(name = "temperature_c")
    private Double temperatureC;

    /** 湿度 (%RH) */
    @Column(name = "humidity_pct")
    private Double humidityPct;

    /** 风速 (m/s) */
    @Column(name = "wind_speed_ms")
    private Double windSpeedMs;
}
