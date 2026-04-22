# app/mcp_server/resources.py
"""
MCP 资源集 —— 提供只读的上下文数据，供 LLM 客户端在调用工具前获取元信息。

资源区别于工具：资源没有副作用，URI 稳定，可被客户端主动订阅或缓存。
"""

import json
from typing import Any

from app.mcp_server.server import mcp, call_backend


@mcp.resource("energy://buildings")
async def list_buildings() -> str:
    """
    建筑清单（含 buildingId / buildingType 等元数据）
    供 LLM 在组装查询参数前了解可用的建筑范围
    """
    data: Any = await call_backend("GET", "/energy/buildings")
    return json.dumps(data, ensure_ascii=False, indent=2)


@mcp.resource("energy://types")
async def list_options() -> str:
    """
    下拉框选项汇总：能源类型（含中文标签与单位） + 建筑类型 + 建筑列表
    与前端 /options 接口共用同一数据源
    """
    data: Any = await call_backend("GET", "/energy/options")
    return json.dumps(data, ensure_ascii=False, indent=2)
