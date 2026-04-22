# 建筑能源智能管理与运营优化系统

## 项目简介

这是一个基于微服务架构的建筑能源智能管理与运营优化系统，旨在通过AI技术实现建筑能耗数据的智能分析、预测和优化建议。系统采用前后端分离架构，集成了Spring Boot后端、Vue.js前端和AI智能分析网关。

## 技术栈

### 后端技术

- **Spring Boot 3.2.5** - 后端框架
- **Java 17** - 编程语言
- **PostgreSQL 15** - 数据库
- **JPA/Hibernate** - ORM框架

### 前端技术

- **Vue.js 3.5.13** - 前端框架
- **TypeScript** - 类型安全
- **Vite 6.0** - 构建工具
- **ECharts 5.6** - 数据可视化

### AI服务

- **FastAPI** - AI网关服务
- **Ollama** - 本地AI模型
- **RAGFlow** - 检索增强生成

### 部署运维

- **Docker & Docker Compose** - 容器化部署
- **PostgreSQL** - 数据存储
- **Maven** - Java依赖管理

## 系统架构

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Vue.js前端    │◄──►│ Spring Boot后端 │◄──►│ PostgreSQL数据库 │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                        │                        │
         ▼                        ▼                        ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   ECharts图表   │    │    AI分析网关   │    │   数据导入服务   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

## 功能模块

### 1. 数据管理

- 多类型能耗数据导入（电力、水、天然气、太阳能等）
- 数据清洗和预处理
- 批量数据导入导出

### 2. 智能查询

- 多维度能耗数据查询
- 时间范围筛选
- 建筑类型过滤
- 实时数据统计

### 3. 统计分析

- 能耗趋势分析
- 能效比(COP)计算
- 异常检测分析
- 排名统计

### 4. AI智能分析

- 自然语言查询
- 智能能耗预测
- 优化建议生成
- 报表自动生成

### 5. 可视化展示

- 实时数据图表
- 趋势分析图
- 排名对比图
- 异常检测可视化

## 快速开始

### 环境要求

- Docker & Docker Compose
- Java 17+
- Node.js 18+

### 一键部署

```bash
# 克隆项目
git clone <repository-url>
cd secretTreasureofSweetieQi

# 复制环境配置文件
cp .env.example.backend .env.backend
cp .env.example.db .env.db
cp .env.example.aiapi .env.aiapi

# 启动所有服务
docker-compose up -d
```

### 服务访问

- **前端应用**: http://localhost:3000
- **后端API**: http://localhost:8080/api
- **AI网关**: http://localhost:8000
- **数据库**: localhost:5432

## 项目结构

```
secretTreasureofSweetieQi/
├── backend/                 # Spring Boot后端服务
│   ├── src/main/java/      # Java源代码
│   ├── src/main/resources/ # 配置文件
│   └── Dockerfile          # 后端Docker配置
├── frontend/               # Vue.js前端应用
│   ├── src/               # Vue组件和逻辑
│   └── package.json       # 前端依赖配置
├── ai/                    # AI智能分析服务
│   └── mvp-ollama-gateway/ # FastAPI AI网关
├── db/                    # 数据库配置
│   ├── init.sql          # 数据库初始化脚本
│   └── Dockerfile        # 数据库Docker配置
├── data/                  # 示例数据文件
│   └── csv/              # CSV格式能耗数据
└── compose.yaml          # Docker Compose配置
```

## API接口文档

### 核心接口

#### 1. 系统健康检查

```http
GET /api/system/health
```

#### 2. 能耗数据查询

```http
POST /api/energy/query
Content-Type: application/json

{
  "energyType": "electricity",
  "buildingIds": ["building_001"],
  "startTime": "2024-01-01 00:00:00",
  "endTime": "2024-01-31 23:59:59"
}
```

#### 3. 统计分析

```http
POST /api/statistics/analyze
Content-Type: application/json

{
  "analysisType": "trend",
  "energyType": "electricity",
  "timeRange": "month"
}
```

#### 4. AI智能对话

```http
POST /api/ai/chat
Content-Type: application/json

{
  "message": "分析一下本月电力消耗情况",
  "context": "building_001"
}
```

## 数据模型

系统支持多种能源类型的数据管理：

- **电力数据** (electricity_data)
- **用水数据** (water_data)
- **天然气数据** (gas_data)
- **太阳能数据** (solar_data)
- **热水数据** (hot_water_data)
- **冷冻水数据** (chilled_water_data)
- **蒸汽数据** (steam_data)
- **灌溉数据** (irrigation_data)
- **气象数据** (weather_data)

## 开发指南

### 后端开发

```bash
cd backend
mvn spring-boot:run
```

### 前端开发

```bash
cd frontend
npm install
npm run dev
```

### AI服务开发

```bash
cd ai/mvp-ollama-gateway
pip install -r requirements.txt
python -m app.main
```

## 部署说明

### 生产环境部署

1. 配置环境变量文件
2. 构建Docker镜像
3. 使用Docker Compose部署
4. 配置反向代理和SSL证书

### 监控和日志

- 应用日志存储在 `./logs/` 目录
- 数据库日志通过PostgreSQL配置
- 使用Docker内置健康检查

## 贡献指南

1. Fork 本项目
2. 创建功能分支
3. 提交代码变更
4. 发起 Pull Request

## 联系方式

如有问题或建议，请通过以下方式联系：

- 邮箱: [ltqltq2022@163.com]

---

_建筑能源智能管理与运营优化系统 - 致力于绿色建筑和节能减排_
