# app/mcp_server/tools.py
"""
MCP 工具集 —— 将 Spring Boot 后端 REST 接口包装为 LLM 可调用的 MCP Tool。

设计原则：
  1. 工具入参使用扁平化原始类型（str/float/int/bool），便于 LLM 构造调用；
  2. 时间字段接受 "yyyy-MM-dd HH:mm:ss" 格式（后端 JacksonConfig 已兼容解析）；
  3. 工具函数内部通过 call_backend 统一回调 Spring Boot，避免在 Python 端重复实现业务逻辑；
  4. None 值不会被序列化到请求体（Spring 侧按 required=false 处理）。
"""

from pathlib import Path
from typing import Any, Dict, Optional

from app.config import settings
from app.mcp_server.server import mcp, call_backend, download_backend


# 导出文件落盘目录（项目根目录下 exports/）
EXPORTS_DIR = Path(__file__).resolve().parent.parent.parent / "exports"
EXPORTS_DIR.mkdir(parents=True, exist_ok=True)


def _download_url(filename: str) -> str:
    """生成 exports/ 下文件的公开下载 URL（供用户点击下载）"""
    host = settings.API_HOST
    if host in ("0.0.0.0", "", None):
        host = "localhost"
    return f"http://{host}:{settings.API_PORT}/exports/{filename}"


def _compact(d: Dict[str, Any]) -> Dict[str, Any]:
    """去除值为 None 或空字符串的键，保持请求体干净"""
    return {k: v for k, v in d.items() if v is not None and v != ""}


# ----------------------------------------------------------------------------
# 1. 多条件能耗数据查询
# ----------------------------------------------------------------------------
@mcp.tool()
async def query_energy_data(
    energy_type: str,
    building_id: Optional[str] = None,
    building_type: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    min_value: Optional[float] = None,
    max_value: Optional[float] = None,
    page: int = 1,
    page_size: int = 20,
    sort_by: str = "monitor_time",
    sort_order: str = "desc",
) -> Dict[str, Any]:
    """
    按多条件分页查询能耗监测记录。

    Args:
        energy_type: 能源类型，必填。取值 electricity/water/gas/steam/chilledwater/hotwater/solar/irrigation
        building_id: 建筑编号（支持模糊匹配）
        building_type: 建筑类型
        start_time: 起始时间，格式 "yyyy-MM-dd HH:mm:ss"
        end_time: 结束时间，同上
        min_value: 数值下限
        max_value: 数值上限
        page: 页码，从 1 开始
        page_size: 每页条数
        sort_by: 排序字段（monitor_time 或 value）
        sort_order: 排序方向（asc/desc）

    Returns:
        分页结果，包含 records/total/page/pageSize 等字段
    """
    body = _compact({
        "energyType": energy_type,
        "buildingId": building_id,
        "buildingType": building_type,
        "startTime": start_time,
        "endTime": end_time,
        "minValue": min_value,
        "maxValue": max_value,
        "page": page,
        "pageSize": page_size,
        "sortBy": sort_by,
        "sortOrder": sort_order,
    })
    return await call_backend("POST", "/energy/query", json_body=body)


# ----------------------------------------------------------------------------
# 2. 时段汇总统计
# ----------------------------------------------------------------------------
@mcp.tool()
async def summary_statistics(
    energy_type: str,
    building_id: Optional[str] = None,
    building_type: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    granularity: str = "day",
) -> Dict[str, Any]:
    """
    指定能源类型与时间范围的时段汇总统计（总量、均值、峰谷等）。

    Args:
        energy_type: 能源类型
        building_id: 建筑编号
        building_type: 建筑类型
        start_time: 起始时间 "yyyy-MM-dd HH:mm:ss"
        end_time: 结束时间 "yyyy-MM-dd HH:mm:ss"
        granularity: 聚合粒度 hour/day/week/month/year

    Returns:
        StatisticsResult 结构：metric summaries + 分组数据
    """
    body = _compact({
        "energyType": energy_type,
        "buildingId": building_id,
        "buildingType": building_type,
        "startTime": start_time,
        "endTime": end_time,
        "granularity": granularity,
        "analysisType": "summary",
    })
    return await call_backend("POST", "/statistics/analyze", json_body=body)


# ----------------------------------------------------------------------------
# 3. COP（制冷性能系数）计算
# ----------------------------------------------------------------------------
@mcp.tool()
async def calculate_cop(
    building_id: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    granularity: str = "month",
) -> Dict[str, Any]:
    """
    计算制冷机组 COP（= 冷冻水产冷量 / 电力耗能）。

    Args:
        building_id: 建筑编号，留空则汇总所有建筑
        start_time: 起始时间
        end_time: 结束时间
        granularity: 粒度，默认 month

    Returns:
        按粒度分组的 COP 值列表
    """
    body = _compact({
        "energyType": "chilledwater",
        "buildingId": building_id,
        "startTime": start_time,
        "endTime": end_time,
        "granularity": granularity,
        "analysisType": "cop",
    })
    return await call_backend("POST", "/statistics/analyze", json_body=body)


# ----------------------------------------------------------------------------
# 4. 异常检测
# ----------------------------------------------------------------------------
@mcp.tool()
async def detect_anomaly(
    energy_type: str,
    building_id: Optional[str] = None,
    building_type: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
) -> Dict[str, Any]:
    """
    基于统计阈值（如 3σ）检测能耗异常点。

    Args:
        energy_type: 能源类型
        building_id: 建筑编号
        building_type: 建筑类型
        start_time: 起始时间
        end_time: 结束时间

    Returns:
        异常记录列表及相关阈值信息
    """
    body = _compact({
        "energyType": energy_type,
        "buildingId": building_id,
        "buildingType": building_type,
        "startTime": start_time,
        "endTime": end_time,
        "analysisType": "anomaly",
    })
    return await call_backend("POST", "/statistics/analyze", json_body=body)


# ----------------------------------------------------------------------------
# 5. 生成图表数据
# ----------------------------------------------------------------------------
@mcp.tool()
async def generate_chart(
    energy_type: str,
    chart_type: str = "line",
    dimension: str = "time",
    building_id: Optional[str] = None,
    building_type: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    granularity: str = "day",
    top_n: int = 10,
) -> Dict[str, Any]:
    """
    生成可视化图表所需的结构化数据（categories + series）。

    Args:
        energy_type: 能源类型
        chart_type: 图表类型 line/bar/pie
        dimension: 聚合维度 time(按时段) / building(按建筑排名) / type(按建筑类型占比)
        building_id: 建筑编号（仅 dimension=time 有效）
        building_type: 建筑类型过滤
        start_time: 起始时间
        end_time: 结束时间
        granularity: 时段粒度（dimension=time 有效）
        top_n: 返回最大条目数（dimension=building/type 有效）

    Returns:
        ChartData 结构 { chartType, title, categories[], series[], unit }
    """
    body = _compact({
        "energyType": energy_type,
        "chartType": chart_type,
        "dimension": dimension,
        "buildingId": building_id,
        "buildingType": building_type,
        "startTime": start_time,
        "endTime": end_time,
        "granularity": granularity,
        "topN": top_n,
    })
    return await call_backend("POST", "/statistics/chart", json_body=body)


# ----------------------------------------------------------------------------
# 5.5 查询数据集实际时间范围
# ----------------------------------------------------------------------------
@mcp.tool()
async def get_data_time_range(energy_type: str) -> Dict[str, Any]:
    """
    查询指定能源类型数据的实际时间覆盖范围（数据集最早/最晚监测时间）。
    用于判断用户提问中涉及的时间段是否真的有数据，避免传入空区间。

    Args:
        energy_type: 能源类型 electricity/water/gas/steam/chilledwater/hotwater/solar/irrigation

    Returns:
        { minTime, maxTime } 等字段（后端原样返回）
    """
    return await call_backend("GET", f"/energy/time-range/{energy_type}")


# ----------------------------------------------------------------------------
# 6. 自动生成并导出统计报表（Excel，多 Sheet + 内嵌图表）
# ----------------------------------------------------------------------------
@mcp.tool()
async def export_report(
    analysis_type: str,
    energy_type: Optional[str] = None,
    building_id: Optional[str] = None,
    building_type: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    granularity: str = "day",
) -> Dict[str, Any]:
    """
    自动生成并导出统计分析 Excel 报表（.xlsx），报表内含多 Sheet 与内嵌图表：
      - 汇总信息 Sheet：总量/均值/峰谷/标准差/记录数
      - 时间序列 Sheet：按粒度聚合 + 内嵌折线图
      - COP 分析 Sheet（analysis_type=cop 时）：含内嵌柱状图
      - 异常分析 Sheet（analysis_type=anomaly 时）：Z-Score 异常点列表
    文件落盘到服务端 exports/ 目录，用户通过返回的 downloadUrl 下载。

    Args:
        analysis_type: 分析类型，summary / cop / anomaly
        energy_type: 能源类型（cop 可省略，其他必填）
        building_id: 建筑编号
        building_type: 建筑类型
        start_time: 起始时间 "yyyy-MM-dd HH:mm:ss"
        end_time: 结束时间 "yyyy-MM-dd HH:mm:ss"
        granularity: 聚合粒度 hour/day/week/month/year

    Returns:
        { fileName, downloadUrl, size, analysisType }
    """
    body = _compact({
        "energyType": energy_type,
        "buildingId": building_id,
        "buildingType": building_type,
        "startTime": start_time,
        "endTime": end_time,
        "granularity": granularity,
        "analysisType": analysis_type,
    })

    data, filename = await download_backend(
        "POST", "/statistics/export",
        json_body=body,
        fallback_name=f"energy_report_{analysis_type}.xlsx",
    )

    (EXPORTS_DIR / filename).write_bytes(data)

    return {
        "fileName": filename,
        "downloadUrl": _download_url(filename),
        "size": len(data),
        "analysisType": analysis_type,
        "message": "报表已生成，用户可通过 downloadUrl 下载 Excel 文件。",
    }


# ----------------------------------------------------------------------------
# 7. 导出单图表 Excel（含数据 + 内嵌图表）
# ----------------------------------------------------------------------------
@mcp.tool()
async def export_chart(
    energy_type: str,
    chart_type: str = "line",
    dimension: str = "time",
    building_id: Optional[str] = None,
    building_type: Optional[str] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    granularity: str = "day",
    top_n: int = 10,
) -> Dict[str, Any]:
    """
    导出单图表 Excel：包含原始数据 + 内嵌的折线/柱状/饼图。

    Args:
        energy_type: 能源类型
        chart_type: line / bar / pie
        dimension: time(按时段) / building(按建筑排名) / type(按建筑类型)
        building_id / building_type / start_time / end_time / granularity / top_n: 同 generate_chart

    Returns:
        { fileName, downloadUrl, size, chartType }
    """
    body = _compact({
        "energyType": energy_type,
        "chartType": chart_type,
        "dimension": dimension,
        "buildingId": building_id,
        "buildingType": building_type,
        "startTime": start_time,
        "endTime": end_time,
        "granularity": granularity,
        "topN": top_n,
    })

    data, filename = await download_backend(
        "POST", "/statistics/chart/export",
        json_body=body,
        fallback_name=f"energy_chart_{chart_type}.xlsx",
    )

    (EXPORTS_DIR / filename).write_bytes(data)

    return {
        "fileName": filename,
        "downloadUrl": _download_url(filename),
        "size": len(data),
        "chartType": chart_type,
        "message": "图表文件已生成，用户可通过 downloadUrl 下载 Excel。",
    }
