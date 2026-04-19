# app/services/agent_service.py
"""
MCP Agent 服务 —— Ollama tool-calling + 真实 MCP 协议 闭环

核心：本服务通过 MCP 官方客户端 SDK，以 SSE + JSON-RPC 2.0 的方式连接到
本进程挂载在 /mcp 下的 MCP Server，真实走一次完整协议流程：
    initialize  →  tools/list  →  tools/call (多次)
而不是用进程内方法直调，确保"数据接入与查询"确实基于 MCP 协议。

流程：
  1. 打开 MCP SSE 客户端会话并握手
  2. 通过 tools/list 获取工具 JSON Schema，转成 Ollama tools 字段
  3. 多轮循环：Ollama 生成 → 若含 tool_calls → 通过 tools/call 执行 → 回填 → 继续
  4. 流式返回中间过程（MCP 调用可观测）与最终答案
"""

import json
import logging
from typing import Any, AsyncIterator, Dict, List, Optional

import httpx

from app.config import settings
from app.services.prompt_loader import get_prompt_loader

try:
    from mcp import ClientSession
    from mcp.client.sse import sse_client
except ImportError as e:  # pragma: no cover
    raise ImportError(
        "MCP SDK 未安装或版本过低，请执行: pip install 'mcp>=1.2.0'"
    ) from e

logger = logging.getLogger(__name__)

# 最大工具调用轮次，防止模型死循环
MAX_AGENT_STEPS = 6

# 过程流中对参数/结果的预览长度（避免前端 UI 被刷屏）
PREVIEW_ARGS_LIMIT = 200
PREVIEW_RESULT_LIMIT = 400

AGENT_TOOL_INSTRUCTIONS = """你现在通过 MCP 协议接入了建筑能源管理系统的工具集。
可用工具：
- query_energy_data: 按条件查询能耗记录（分页）
- summary_statistics: 时段汇总统计（总量、均值、峰谷）
- calculate_cop: 计算制冷机组 COP（冷冻水能效）
- detect_anomaly: 异常检测
- generate_chart: 生成可视化图表数据（line/bar/pie）
- get_data_time_range: 查询某能源类型数据的实际覆盖时间范围
- export_report: 自动生成并导出统计分析 Excel 报表（多 Sheet + 内嵌图表）
- export_chart: 导出单图表 Excel（line/bar/pie + 原始数据）

【工具调用规则】
1. 涉及具体数据/数字/统计的问题，必须通过 MCP 工具获取真实数据，严禁编造。
2. 时间参数固定格式 "yyyy-MM-dd HH:mm:ss"。
3. 能源类型必须为：electricity, water, gas, steam, chilledwater, hotwater, solar, irrigation 之一。
4. 用户问"导出/下载/生成报表"类需求时，调用 export_report（综合报表）或 export_chart（单图表）。
5. 闲聊或概念解释可直接回答，不调用工具。
6. **关键：时间参数绝不能凭当前日期推测。如果用户没有明确说时间段（如"2017 年 6 月"），start_time 和 end_time 必须留空（不传），由后端自动用全量数据。绝对不要用"今年"、"上个月"、"最近一年"来编 2024/2025/2026 年的日期——数据集实际可能是 2016 年或更早，编出来的时间范围会查出 0 条数据导致空报表。**
7. 若不确定数据覆盖时段，可先调 get_data_time_range 查询实际范围，再决定是否传时间参数。
8. **相对时间翻译规则：当用户说"近 N 天 / 近 N 小时 / 最近一周 / 上个月 / 最近 X"等相对时间时，一律以数据集的 maxTime 为"现在"来往前推算，而不是以今天的真实日期推算。例如 maxTime=2017-12-31 23:00:00 且用户说"近 7 天"，那么 end_time=2017-12-31 23:00:00，start_time=2017-12-24 23:00:00。**
9. **继续调用规则：get_data_time_range 只是辅助工具，它不能直接回答用户问题。调完 get_data_time_range 之后，如果用户本来问的是统计/异常/图表/导出，你必须在同一轮继续调用对应的业务工具（detect_anomaly、summary_statistics、generate_chart 等），把相对时间翻译好的 start_time/end_time 传进去，绝不能只给用户报个时间范围就结束。**
10. 数据集是历史数据（2016–2017 年范围），不要以"现在是 2026 年"为由拒绝回答——用户问的"最近"就是指数据里最后那几天。
11. **COP / detect_anomaly 查询强制附加时间范围**：calculate_cop 和 detect_anomaly 背后是两张千万行级别的表做 JOIN 或全表扫描，没有时间过滤会超时。调用这两个工具前，若用户未指明时间，**必须先调 get_data_time_range 拿到 maxTime，再用 maxTime 往前回溯一段合理区间**（如月度 COP → 取最后 3 个月；异常检测 → 取最后 7 天）作为 start_time / end_time 传入，绝不允许完全不传时间参数。
12. 工具返回"[工具错误] ... 超时"或"HTTP 500"时，不要盲目重试同样的参数——分析错误信息，调整参数（缩小时间窗、改 granularity 为 year）后再试，最多重试 1 次。

【作答规则（严格遵守）】
1. 必须使用中文回答，禁止使用英文、emoji、表情符号。
2. 必须直接回答用户问题本身；用户问平均值就报平均值，问总量就报总量，不要跑题到其他字段。
3. 必须原样引用工具返回的具体数值和单位，例如 "电力平均值为 144.91 kWh（来自 summary.avgValue）"。
4. 回答要简洁明确，一般 1–3 句；涉及多指标时用简短列表。
5. 严禁列菜单（例如"你想做什么？1. 统计分析 2. 可视化..."），严禁给代码示例，严禁反问用户下一步。
6. 工具返回里的 summary 字段（totalValue/avgValue/maxValue/minValue/stdDev/recordCount）是主要答案来源，timeSeries 仅在用户问趋势时才需要讨论。
7. 如果工具结果包含 unit 字段，数值后必须带上单位。
8. export_report / export_chart 返回 { fileName, downloadUrl, size } 后，必须把 downloadUrl 原样展示给用户并告知文件名，例如："报表已生成：能耗统计报表_summary_xxx.xlsx，下载地址 http://.../exports/xxx.xlsx"。
"""


def _sse(obj: Dict[str, Any]) -> str:
    """打包为 SSE data 行，与前端既有解析逻辑兼容"""
    return f"data: {json.dumps(obj, ensure_ascii=False)}\n\n"


def _truncate(s: str, limit: int) -> str:
    return s if len(s) <= limit else s[:limit] + "..."


def _normalize_args(raw: Any) -> Dict[str, Any]:
    """Ollama 返回的 tool_call.arguments 通常为 dict，兼容字符串 JSON"""
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            return {}
    return {}


def _mcp_tools_to_ollama(mcp_tools) -> List[Dict[str, Any]]:
    """MCP ListToolsResult.tools → Ollama tools 字段"""
    result: List[Dict[str, Any]] = []
    for t in mcp_tools:
        schema = getattr(t, "inputSchema", None)
        if not isinstance(schema, dict):
            schema = {"type": "object", "properties": {}}
        result.append({
            "type": "function",
            "function": {
                "name": t.name,
                "description": (t.description or "").strip(),
                "parameters": schema,
            }
        })
    return result


def _mcp_result_to_text(call_result) -> str:
    """MCP CallToolResult.content → 字符串（拼接 TextContent.text）"""
    parts: List[str] = []
    for item in (getattr(call_result, "content", None) or []):
        text = getattr(item, "text", None)
        if text is not None:
            parts.append(text)
        else:
            parts.append(str(item))
    return "\n".join(parts) if parts else ""


def _build_system_prompt(base_chat_prompt: Optional[str]) -> str:
    base = (base_chat_prompt or "").strip()
    if base:
        return f"{base}\n\n---\n\n{AGENT_TOOL_INSTRUCTIONS}"
    return AGENT_TOOL_INSTRUCTIONS


async def run_agent_stream(
    user_prompt: str,
    model: Optional[str] = None,
    temperature: Optional[float] = None,
    system_prompt_source: str = "chat",
) -> AsyncIterator[str]:
    """
    MCP Agent 主循环。以 SSE 分段产出，每段形如
    `data: {"response": "...", "done": false}\\n\\n`
    """
    use_model = model or settings.OLLAMA_MODEL

    base_prompt = get_prompt_loader(system_prompt_source).get_prompt()
    system_prompt = _build_system_prompt(base_prompt)

    options: Dict[str, Any] = {}
    if temperature is not None:
        options["temperature"] = temperature

    messages: List[Dict[str, Any]] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]

    ollama_timeout = httpx.Timeout(300.0, connect=10.0)

    try:
        # 1) 打开真实 MCP SSE 客户端会话 → 本进程 /mcp/sse
        logger.info(f"[Agent] 连接 MCP Server: {settings.MCP_SSE_URL}")
        async with sse_client(settings.MCP_SSE_URL) as (read_stream, write_stream):
            async with ClientSession(read_stream, write_stream) as session:
                # 2) 协议握手
                await session.initialize()

                # 3) tools/list 拉取工具 Schema
                tools_result = await session.list_tools()
                tools = _mcp_tools_to_ollama(tools_result.tools)
                logger.info(f"[Agent] MCP 会话就绪，加载 {len(tools)} 个工具")

                yield _sse({
                    "response": f"[MCP已连接] 已加载 {len(tools)} 个工具\n",
                    "done": False,
                })

                # 4) 进入 Ollama ↔ MCP 工具调用循环
                async with httpx.AsyncClient(timeout=ollama_timeout) as ollama:
                    for step in range(MAX_AGENT_STEPS):
                        logger.info(f"[Agent] step={step + 1} messages={len(messages)}")

                        payload: Dict[str, Any] = {
                            "model": use_model,
                            "messages": messages,
                            "tools": tools,
                            "stream": False,
                        }
                        if options:
                            payload["options"] = options

                        resp = await ollama.post(
                            f"{settings.OLLAMA_URL}/api/chat",
                            json=payload,
                        )
                        resp.raise_for_status()
                        data = resp.json()

                        msg = data.get("message") or {}
                        content = msg.get("content") or ""
                        tool_calls = msg.get("tool_calls") or []

                        assistant_msg: Dict[str, Any] = {
                            "role": msg.get("role", "assistant"),
                            "content": content,
                        }
                        if tool_calls:
                            assistant_msg["tool_calls"] = tool_calls
                        messages.append(assistant_msg)

                        # 情况 A：无 tool_calls → 已给出最终答案
                        if not tool_calls:
                            if content:
                                yield _sse({"response": content, "done": False})
                            yield _sse({"response": "", "done": True})
                            return

                        # 情况 B：通过 MCP tools/call 真实执行工具
                        for tc in tool_calls:
                            fn = tc.get("function") or {}
                            name = fn.get("name") or ""
                            args = _normalize_args(fn.get("arguments"))

                            args_preview = _truncate(
                                json.dumps(args, ensure_ascii=False),
                                PREVIEW_ARGS_LIMIT,
                            )
                            yield _sse({
                                "response": f"\n[MCP tools/call] {name}({args_preview})\n",
                                "done": False,
                            })

                            try:
                                call_result = await session.call_tool(name, args)
                                tool_result = _mcp_result_to_text(call_result)
                                if getattr(call_result, "isError", False):
                                    tool_result = f"[工具错误] {tool_result}"
                            except Exception as e:
                                logger.exception(f"[Agent] MCP 调用失败 {name}")
                                tool_result = f"工具调用失败: {e}"

                            yield _sse({
                                "response": f"[工具结果] {_truncate(tool_result, PREVIEW_RESULT_LIMIT)}\n\n",
                                "done": False,
                            })

                            messages.append({
                                "role": "tool",
                                "content": tool_result,
                            })

                    # 超出最大轮次仍未收敛
                    yield _sse({
                        "response": "\n[已达最大 MCP 工具调用轮次]",
                        "done": True,
                    })

    except httpx.HTTPError as e:
        logger.exception("[Agent] Ollama HTTP 错误")
        yield _sse({"error": f"Ollama 调用失败: {e}", "done": True})
    except Exception as e:
        logger.exception("[Agent] 异常")
        yield _sse({"error": f"{type(e).__name__}: {e}", "done": True})
