# app/mcp_server/server.py
"""
MCP Server 实例与 HTTP 客户端工厂
通过 httpx 回调 Spring Boot 后端，将其 REST 接口包装为 MCP 工具/资源
"""

import logging
import urllib.parse
from typing import Any, Dict, Optional, Tuple

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

try:
    from mcp.server.fastmcp import FastMCP
except ImportError as e:
    raise ImportError(
        "MCP SDK 未安装，请执行: pip install 'mcp>=1.2.0'"
    ) from e


mcp = FastMCP(
    "energy-mcp",
    instructions=(
        "建筑能源智能管理系统 MCP 服务。"
        "提供能耗数据查询、汇总统计、COP 计算、异常检测、图表生成等能力。"
        "所有工具均通过 HTTP 回调底层 Spring Boot 后端。"
    ),
)


def _spring_base_url() -> str:
    """
    返回 Spring Boot API 根地址（已包含 context-path /api）
    末尾不带斜杠，便于与子路径拼接
    """
    base = settings.SPRING_BOOT_BASE_URL.rstrip("/")
    return base


async def call_backend(
    method: str,
    path: str,
    *,
    params: Optional[Dict[str, Any]] = None,
    json_body: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """
    统一的后端调用函数。解包 Spring 标准 ApiResponse 封装 {code, message, data}。

    Args:
        method: HTTP 方法（GET/POST/...）
        path: 相对路径（以 '/' 开头，相对 SPRING_BOOT_BASE_URL）
        params: 查询参数
        json_body: POST/PUT JSON 请求体

    Returns:
        ApiResponse 中的 data 字段（或在失败时抛出异常）

    Raises:
        RuntimeError: 后端返回非 200 业务码、超时、HTTP 错误等，均带明确中文消息
    """
    url = f"{_spring_base_url()}{path}"
    timeout = httpx.Timeout(settings.MCP_HTTP_TIMEOUT, connect=10.0)

    async with httpx.AsyncClient(timeout=timeout) as client:
        logger.info(f"[MCP->Backend] {method} {url} body={json_body}")
        try:
            resp = await client.request(
                method=method.upper(),
                url=url,
                params=params,
                json=json_body,
            )
            resp.raise_for_status()
        except httpx.TimeoutException as e:
            logger.exception(f"[MCP->Backend] 超时: {url}")
            raise RuntimeError(
                f"后端接口 {path} 在 {settings.MCP_HTTP_TIMEOUT}s 内未返回，"
                "通常是查询范围过大。请在调用时传入 start_time/end_time 缩小范围，"
                "或缩短 granularity 聚合粒度（改为 year/month）。"
            ) from e
        except httpx.HTTPStatusError as e:
            body_preview = ""
            try:
                body_preview = e.response.text[:500]
            except Exception:
                pass
            logger.error(f"[MCP->Backend] HTTP {e.response.status_code}: {body_preview}")
            raise RuntimeError(
                f"后端接口 {path} 返回 HTTP {e.response.status_code}: {body_preview or '(空响应体)'}"
            ) from e
        except httpx.RequestError as e:
            logger.exception(f"[MCP->Backend] 网络错误: {url}")
            raise RuntimeError(f"后端请求失败 ({type(e).__name__}): {e}") from e

        try:
            payload = resp.json()
        except Exception as e:
            raise RuntimeError(f"后端响应不是合法 JSON: {e}") from e

        if not isinstance(payload, dict):
            return {"raw": payload}

        code = payload.get("code")
        if code is not None and code != 200:
            msg = payload.get("message") or f"业务错误 code={code}"
            raise RuntimeError(msg)

        return payload.get("data", payload)


async def download_backend(
    method: str,
    path: str,
    *,
    params: Optional[Dict[str, Any]] = None,
    json_body: Optional[Dict[str, Any]] = None,
    fallback_name: str = "export.bin",
) -> Tuple[bytes, str]:
    """
    调用后端二进制下载接口（Excel/CSV 等），返回 (bytes, filename)。
    filename 从 Content-Disposition 头解析，失败时使用 fallback_name。
    """
    url = f"{_spring_base_url()}{path}"
    # 导出耗时可能较长，给更大的超时
    timeout = httpx.Timeout(settings.MCP_HTTP_TIMEOUT * 3, connect=10.0)

    async with httpx.AsyncClient(timeout=timeout) as client:
        logger.info(f"[MCP->Backend] {method} {url} (binary download)")
        resp = await client.request(
            method=method.upper(),
            url=url,
            params=params,
            json=json_body,
        )
        resp.raise_for_status()

        cd = resp.headers.get("content-disposition", "")
        filename = fallback_name
        if "filename=" in cd:
            raw = cd.split("filename=", 1)[-1].strip().strip('";')
            try:
                filename = urllib.parse.unquote(raw)
            except Exception:
                filename = raw

        return resp.content, filename
