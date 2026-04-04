-- =====================================================
-- 建筑能源智能管理系统 - 数据库初始化脚本
-- 数据库: PostgreSQL
-- 创建数据库后, JPA会自动创建表(ddl-auto=update)
-- 此脚本仅供手动初始化或参考使用
-- =====================================================

-- 1. 创建数据库 (需要以superuser身份执行)
-- CREATE DATABASE energy_management OWNER myprotgre;

-- 2. 电力能耗表
CREATE TABLE IF NOT EXISTS electricity_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    electricity_kwh DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_elec_building ON electricity_data(building_id);
CREATE INDEX IF NOT EXISTS idx_elec_time ON electricity_data(monitor_time);
CREATE INDEX IF NOT EXISTS idx_elec_type ON electricity_data(building_type);

-- 3. 用水数据表
CREATE TABLE IF NOT EXISTS water_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    water_m3 DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_water_building ON water_data(building_id);
CREATE INDEX IF NOT EXISTS idx_water_time ON water_data(monitor_time);

-- 4. 天然气数据表
CREATE TABLE IF NOT EXISTS gas_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    gas_therms DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_gas_building ON gas_data(building_id);
CREATE INDEX IF NOT EXISTS idx_gas_time ON gas_data(monitor_time);

-- 5. 蒸汽数据表
CREATE TABLE IF NOT EXISTS steam_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    steam_lbs DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_steam_building ON steam_data(building_id);
CREATE INDEX IF NOT EXISTS idx_steam_time ON steam_data(monitor_time);

-- 6. 冷冻水数据表
CREATE TABLE IF NOT EXISTS chilledwater_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    chilledwater_tonhours DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_cw_building ON chilledwater_data(building_id);
CREATE INDEX IF NOT EXISTS idx_cw_time ON chilledwater_data(monitor_time);

-- 7. 热水数据表
CREATE TABLE IF NOT EXISTS hotwater_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    hotwater_kbtu DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_hw_building ON hotwater_data(building_id);
CREATE INDEX IF NOT EXISTS idx_hw_time ON hotwater_data(monitor_time);

-- 8. 光伏发电数据表
CREATE TABLE IF NOT EXISTS solar_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    solar_kwh DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_solar_building ON solar_data(building_id);
CREATE INDEX IF NOT EXISTS idx_solar_time ON solar_data(monitor_time);

-- 9. 灌溉用水数据表
CREATE TABLE IF NOT EXISTS irrigation_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    irrigation_gallon DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_irr_building ON irrigation_data(building_id);
CREATE INDEX IF NOT EXISTS idx_irr_time ON irrigation_data(monitor_time);

-- 10. 气象数据表
CREATE TABLE IF NOT EXISTS weather_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    temperature_f DOUBLE PRECISION,
    humidity_pct DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_wea_building ON weather_data(building_id);
CREATE INDEX IF NOT EXISTS idx_wea_time ON weather_data(monitor_time);

-- =====================================================
-- 常用查询示例
-- =====================================================

-- 查看各表数据量
-- SELECT 'electricity_data' as table_name, COUNT(*) as row_count FROM electricity_data
-- UNION ALL SELECT 'water_data', COUNT(*) FROM water_data
-- UNION ALL SELECT 'gas_data', COUNT(*) FROM gas_data
-- UNION ALL SELECT 'steam_data', COUNT(*) FROM steam_data
-- UNION ALL SELECT 'chilledwater_data', COUNT(*) FROM chilledwater_data
-- UNION ALL SELECT 'hotwater_data', COUNT(*) FROM hotwater_data
-- UNION ALL SELECT 'solar_data', COUNT(*) FROM solar_data
-- UNION ALL SELECT 'irrigation_data', COUNT(*) FROM irrigation_data
-- UNION ALL SELECT 'weather_data', COUNT(*) FROM weather_data;

-- COP计算示例 (按月)
-- SELECT DATE_TRUNC('month', c.monitor_time) as period,
--        c.building_id,
--        SUM(c.chilledwater_tonhours) as cooling_output,
--        SUM(e.electricity_kwh) as electric_input,
--        CASE WHEN SUM(e.electricity_kwh) > 0
--             THEN (SUM(c.chilledwater_tonhours) * 3.517) / SUM(e.electricity_kwh)
--             ELSE 0 END as cop
-- FROM chilledwater_data c
-- JOIN electricity_data e ON c.building_id = e.building_id AND c.monitor_time = e.monitor_time
-- GROUP BY period, c.building_id
-- ORDER BY period;
