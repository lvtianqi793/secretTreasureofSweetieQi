# 数据库初始化部署指南

## 概述

本系统实现了自动化的数据库初始化和数据导入功能。当PostgreSQL容器启动时，会自动执行以下操作：

1. **创建表结构** - 执行`init.sql`脚本创建所有数据表
2. **导入CSV数据** - 自动导入挂载的CSV文件到对应数据表
3. **记录导入日志** - 在`import_log`表中记录导入结果

## 目录结构

```
db/
├── Dockerfile          # 数据库初始化容器配置
├── init.sql            # 数据库表结构初始化脚本
└── import-data.sh      # CSV数据自动导入脚本

data/
└── csv/                # CSV数据文件目录（需要手动创建）
    ├── electricity.csv
    ├── water.csv
    ├── gas.csv
    ├── steam.csv
    ├── chilledwater.csv
    ├── hotwater.csv
    ├── solar.csv
    ├── irrigation.csv
    └── weather.csv
```

## 部署步骤

### 1. 创建数据目录

在项目根目录创建数据目录结构：

```bash
mkdir -p data/csv
```

**重要提醒**：CSV数据文件已被添加到`.gitignore`中，不会进入Git仓库。建议：

- 将大型CSV文件放在项目外部管理
- 或者使用Git LFS（大文件存储）管理数据文件
- 生产环境建议从外部存储加载数据

### 保留空目录结构

为了确保目录结构存在，可以创建一个空文件：

```bash
touch data/csv/.gitkeep
```

### 2. 准备CSV文件

将你的CSV数据文件放入`data/csv/`目录。文件名应包含以下关键词以便自动识别：

- `electricity` - 电力数据
- `water` - 用水数据
- `gas` - 天然气数据
- `steam` - 蒸汽数据
- `chilledwater` - 冷冻水数据
- `hotwater` - 热水数据
- `solar` - 太阳能数据
- `irrigation` - 灌溉数据
- `weather` - 天气数据

### 3. CSV文件格式要求

#### 标准能耗数据格式（电力、用水、天然气等）

```csv
building_id,building_type,monitor_time,value_column
building_001,office,2024-01-01 00:00,123.45
building_002,residential,2024-01-01 00:00,67.89
```

#### 天气数据格式

```csv
building_id,building_type,monitor_time,temperature_f,humidity_percent,wind_speed_mph,solar_radiation_wm2
weather_station_001,weather,2024-01-01 00:00,68.5,65.2,8.3,450.7
```

### 4. 启动服务

```bash
# 构建并启动所有服务
docker-compose up --build
```

### 5. 验证数据导入

容器启动后，可以通过以下方式验证数据导入：

1. **查看容器日志**：

   ```bash
   docker logs energy-management-postgres-db-1
   ```

2. **连接数据库查询**：

   ```bash
   # 连接到PostgreSQL
   psql -h localhost -p 5432 -U postgres -d energy_management

   # 查看表数据量
   SELECT table_name, COUNT(*) FROM (
       SELECT 'electricity_data' as table_name FROM electricity_data
       UNION ALL SELECT 'water_data' FROM water_data
       UNION ALL SELECT 'gas_data' FROM gas_data
       UNION ALL SELECT 'steam_data' FROM steam_data
       UNION ALL SELECT 'chilledwater_data' FROM chilledwater_data
       UNION ALL SELECT 'hotwater_data' FROM hotwater_data
       UNION ALL SELECT 'solar_data' FROM solar_data
       UNION ALL SELECT 'irrigation_data' FROM irrigation_data
       UNION ALL SELECT 'weather_data' FROM weather_data
   ) t GROUP BY table_name;

   # 查看导入日志
   SELECT * FROM import_log ORDER BY import_time DESC;
   ```

## 工作原理

### 数据库初始化流程

1. **容器启动**：PostgreSQL容器启动时，会自动执行`/docker-entrypoint-initdb.d/`目录下的脚本
2. **表结构创建**：`init.sql`脚本首先执行，创建所有数据表和索引
3. **数据导入**：`import-data.sh`脚本随后执行，导入挂载的CSV文件
4. **日志记录**：导入过程会记录到`import_log`表中

### 文件识别逻辑

脚本会根据CSV文件名中的关键词自动识别对应的数据表：

- `*electricity*` → `electricity_data`表
- `*water*` → `water_data`表
- `*gas*` → `gas_data`表
- 等等...

## 注意事项

1. **数据持久化**：数据库数据会持久化在`postgres-data`卷中
2. **CSV文件更新**：修改CSV文件后需要重启数据库容器才能重新导入
3. **文件编码**：确保CSV文件使用UTF-8编码
4. **时间格式**：时间列应使用标准格式（如`2024-01-01 00:00`）
5. **只读挂载**：CSV目录以只读方式挂载，防止容器修改源文件

## 故障排除

### 常见问题

1. **CSV文件未导入**：检查文件名是否包含正确的关键词
2. **导入失败**：检查CSV文件格式和分隔符
3. **权限问题**：确保CSV文件有读取权限
4. **编码问题**：确保CSV文件使用UTF-8编码

### 日志查看

```bash
# 查看数据库容器日志
docker logs [容器名]

# 查看导入详细日志
psql -h localhost -p 5432 -U postgres -d energy_management -c "SELECT * FROM import_log;"
```
