# app/mcp_server/__init__.py
"""
MCP (Model Context Protocol) 服务模块
将后端 Spring Boot 的能耗查询/统计接口以 MCP 协议暴露给 LLM 客户端
"""

from app.mcp_server.server import mcp

# 导入以触发 @mcp.tool() / @mcp.resource() 注册
from app.mcp_server import tools  # noqa: F401
from app.mcp_server import resources  # noqa: F401

__all__ = ["mcp"]
