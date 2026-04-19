#!/bin/bash
# 数据导入脚本 - 自动导入挂载的CSV文件到数据库

set -e

# ANSI颜色代码定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "开始导入CSV数据..."
echo "PostgreSQL服务已就绪，开始导入数据"

# CSV文件目录
CSV_DIR="/data/csv"

# 检查CSV目录是否存在
if [ ! -d "$CSV_DIR" ]; then
    echo "CSV目录不存在: $CSV_DIR，跳过数据导入"
    exit 0
fi

# 查找所有CSV文件
CSV_FILES=$(find "$CSV_DIR" -name "*.csv" | sort)

if [ -z "$CSV_FILES" ]; then
    echo "未找到CSV文件，跳过数据导入"
    exit 0
fi

echo "找到以下CSV文件:"
echo "$CSV_FILES"

# 导入函数
import_csv() {
    local file_path="$1"
    local file_name="$(basename "$file_path")"
    local table_name=""
    local value_column=""
    
    echo "处理文件: $file_name"
    
    # 根据文件名确定表名和值列名
    case "$file_name" in
        *electricity*)
            table_name="electricity_data"
            value_column="electricity_kwh"
            ;;
        *chilledwater*)
            table_name="chilledwater_data"
            value_column="chilledwater_tonhours"
            ;;
        *hotwater*)
            table_name="hotwater_data"
            value_column="hotwater_kbtu"
            ;;
        *water*)
            table_name="water_data"
            value_column="water_m3"
            ;;
        *gas*)
            table_name="gas_data"
            value_column="gas_therms"
            ;;
        *steam*)
            table_name="steam_data"
            value_column="steam_lbs"
            ;;
        *solar*)
            table_name="solar_data"
            value_column="solar_kwh"
            ;;
        *irrigation*)
            table_name="irrigation_data"
            value_column="irrigation_gallon"
            ;;
        *weather*)
            table_name="weather_data"
            # 天气数据需要特殊处理
            import_weather_data "$file_path"
            return
            ;;
        *)
            echo "无法识别的文件类型: $file_name，跳过"
            return
            ;;
    esac
    
    # 导入标准能耗数据
    import_energy_data "$file_path" "$table_name" "$value_column" "$file_name"
}

# 导入标准能耗数据函数
import_energy_data() {
    local file_path="$1"
    local table_name="$2"
    local value_column="$3"
    local file_name="$4"
    
    echo "导入数据到表: $table_name"
    
    # 使用COPY命令导入数据
    psql -U postgres -d energy_management -c "
        COPY $table_name(building_id, building_type, monitor_time, $value_column) 
        FROM '$file_path' 
        WITH (FORMAT csv, HEADER true, DELIMITER ',');
    "
    
    # 记录导入日志
    local imported_rows=$(psql -U postgres -d energy_management -t -c "
        SELECT COUNT(*) FROM $table_name;
    " | tr -d ' ')
    
    psql -U postgres -d energy_management -c "
        INSERT INTO import_log(file_name, table_name, rows_imported, status) 
        VALUES ('$file_name', '$table_name', $imported_rows, 'success');
    "
    
    echo "导入完成: $file_name -> $table_name ($imported_rows 行)"
}

# 导入天气数据函数（特殊处理）
import_weather_data() {
    local file_path="$1"
    local file_name="$(basename "$file_path")"
    
    echo "导入天气数据: $file_name"
    
    # 天气数据需要特殊处理，因为列结构不同
    psql -U postgres -d energy_management -c "
        COPY weather_data(building_id, building_type, monitor_time, temperature_f, humidity_percent, wind_speed_mph, solar_radiation_wm2) 
        FROM '$file_path' 
        WITH (FORMAT csv, HEADER true, DELIMITER ',');
    "
    
    # 记录导入日志
    local imported_rows=$(psql -U postgres -d energy_management -t -c "
        SELECT COUNT(*) FROM weather_data;
    " | tr -d ' ')
    
    psql -U postgres -d energy_management -c "
        INSERT INTO import_log(file_name, table_name, rows_imported, status) 
        VALUES ('$file_name', 'weather_data', $imported_rows, 'success');
    "
    
    echo "天气数据导入完成: $file_name -> weather_data ($imported_rows 行)"
}

# 主导入循环
for csv_file in $CSV_FILES; do
    import_csv "$csv_file"
done

echo "所有CSV数据导入完成"

# 显示导入统计
psql -U postgres -d energy_management -c "
    SELECT table_name, COUNT(*) as row_count 
    FROM (
        SELECT 'electricity_data' as table_name FROM electricity_data
        UNION ALL SELECT 'water_data' FROM water_data
        UNION ALL SELECT 'gas_data' FROM gas_data
        UNION ALL SELECT 'steam_data' FROM steam_data
        UNION ALL SELECT 'chilledwater_data' FROM chilledwater_data
        UNION ALL SELECT 'hotwater_data' FROM hotwater_data
        UNION ALL SELECT 'solar_data' FROM solar_data
        UNION ALL SELECT 'irrigation_data' FROM irrigation_data
        UNION ALL SELECT 'weather_data' FROM weather_data
    ) t 
    GROUP BY table_name 
    ORDER BY table_name;
"

echo -e "${GREEN}数据导入脚本执行完毕${NC}"

# 创建导入完成标记文件，用于健康检查
echo "创建导入完成标记文件..."
if [ ! -d "/data/status" ]; then
    echo "警告: /data/status 目录不存在，尝试创建"
    mkdir -p /data/status
fi
 
if touch /data/status/data_import_complete; then
    echo -e "${GREEN}导入完成标记文件创建成功${NC}"
else
    echo -e "${RED}警告: 无法创建导入完成标记文件，但数据导入已完成${NC}"
fi