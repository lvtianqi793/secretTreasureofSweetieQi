-- =====================================================
-- 建筑能源智能管理系统 - 数据库初始化脚本
-- 数据库: PostgreSQL
-- 此脚本在容器启动时自动执行，创建所有表结构
-- =====================================================

-- 1. 电力能耗表
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

-- 2. 用水数据表
CREATE TABLE IF NOT EXISTS water_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    water_m3 DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_water_building ON water_data(building_id);
CREATE INDEX IF NOT EXISTS idx_water_time ON water_data(monitor_time);

-- 3. 天然气数据表
CREATE TABLE IF NOT EXISTS gas_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    gas_therms DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_gas_building ON gas_data(building_id);
CREATE INDEX IF NOT EXISTS idx_gas_time ON gas_data(monitor_time);

-- 4. 蒸汽数据表
CREATE TABLE IF NOT EXISTS steam_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    steam_lbs DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_steam_building ON steam_data(building_id);
CREATE INDEX IF NOT EXISTS idx_steam_time ON steam_data(monitor_time);

-- 5. 冷冻水数据表
CREATE TABLE IF NOT EXISTS chilledwater_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    chilledwater_tonhours DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_chilledwater_building ON chilledwater_data(building_id);
CREATE INDEX IF NOT EXISTS idx_chilledwater_time ON chilledwater_data(monitor_time);

-- 6. 热水数据表
CREATE TABLE IF NOT EXISTS hotwater_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    hotwater_kbtu DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_hotwater_building ON hotwater_data(building_id);
CREATE INDEX IF NOT EXISTS idx_hotwater_time ON hotwater_data(monitor_time);

-- 7. 太阳能数据表
CREATE TABLE IF NOT EXISTS solar_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    solar_kwh DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_solar_building ON solar_data(building_id);
CREATE INDEX IF NOT EXISTS idx_solar_time ON solar_data(monitor_time);

-- 8. 灌溉数据表
CREATE TABLE IF NOT EXISTS irrigation_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    irrigation_gallon DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_irrigation_building ON irrigation_data(building_id);
CREATE INDEX IF NOT EXISTS idx_irrigation_time ON irrigation_data(monitor_time);

-- 9. 天气数据表
CREATE TABLE IF NOT EXISTS weather_data (
    id BIGSERIAL PRIMARY KEY,
    building_id VARCHAR(100) NOT NULL,
    building_type VARCHAR(50) NOT NULL,
    monitor_time TIMESTAMP NOT NULL,
    temperature_f DOUBLE PRECISION,
    humidity_percent DOUBLE PRECISION,
    wind_speed_mph DOUBLE PRECISION,
    solar_radiation_wm2 DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_weather_building ON weather_data(building_id);
CREATE INDEX IF NOT EXISTS idx_weather_time ON weather_data(monitor_time);

-- 创建数据导入日志表
CREATE TABLE IF NOT EXISTS import_log (
    id BIGSERIAL PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    import_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    rows_imported BIGINT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'success'
);

-- 输出初始化完成信息
DO $$
BEGIN
    RAISE NOTICE '数据库表结构初始化完成';
END $$;