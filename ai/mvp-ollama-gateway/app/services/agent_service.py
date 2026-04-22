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

import asyncio
import json
import logging
from typing import Any, AsyncIterator, Dict, List, Optional, Tuple

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

# 替换硬编码
MAX_AGENT_STEPS = settings.MAX_AGENT_STEPS
PREVIEW_ARGS_LIMIT = settings.PREVIEW_ARGS_LIMIT
PREVIEW_RESULT_LIMIT = settings.PREVIEW_RESULT_LIMIT
OLLAMA_TIMEOUT = httpx.Timeout(settings.OLLAMA_TIMEOUT, connect=10.0)
MAX_TOOL_RESULT_LENGTH = getattr(settings, "MAX_TOOL_RESULT_LENGTH", 8000)  # 工具结果最大保留字符
MAX_CONTEXT_MESSAGES = 20  # 保留最近的消息轮数（不含 system）
TOOL_CALL_TIMEOUT = 30.0  # 单个 MCP 工具调用超时（秒）

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
    """MCP CallToolResult.content → 字符串（拼接 TextContent.text），并截断"""
    parts: List[str] = []
    for item in (getattr(call_result, "content", None) or []):
        text = getattr(item, "text", None)
        if text is not None:
            parts.append(text)
        else:
            parts.append(str(item))
    full_text = "\n".join(parts) if parts else ""
    return _truncate(full_text, MAX_TOOL_RESULT_LENGTH)


def _format_tool_call_for_user(tool_name: str, args: Dict[str, Any]) -> str:
    """将工具调用信息转换为用户友好的描述"""
    tool_descriptions = {
        "query_energy_data": "正在查询能耗数据",
        "summary_statistics": "正在统计能耗数据",
        "calculate_cop": "正在计算制冷机组能效",
        "detect_anomaly": "正在检测能耗异常",
        "generate_chart": "正在生成图表数据",
        "get_data_time_range": "正在查询数据时间范围",
        "export_report": "正在生成报表",
        "export_chart": "正在导出图表"
    }
    
    base_description = tool_descriptions.get(tool_name, f"正在执行{tool_name}操作")
    
    # 根据具体参数添加更多细节
    if tool_name == "query_energy_data":
        energy_type = args.get("energy_type", "")
        if energy_type:
            base_description += f"（{energy_type}类型）"
    elif tool_name == "summary_statistics":
        energy_type = args.get("energy_type", "")
        granularity = args.get("granularity", "")
        if energy_type and granularity:
            base_description += f"（{energy_type}，{granularity}粒度）"
    
    return base_description


def _format_tool_result_for_user(tool_name: str, result: str) -> str:
    """将工具执行结果转换为用户友好的描述"""
    if "[工具错误]" in result or "工具调用失败" in result:
        return "操作遇到问题，正在重新处理..."
    
    tool_success_messages = {
        "query_energy_data": "能耗数据查询完成",
        "summary_statistics": "统计计算完成",
        "calculate_cop": "能效计算完成",
        "detect_anomaly": "异常检测完成",
        "generate_chart": "图表数据生成完成",
        "get_data_time_range": "时间范围查询完成",
        "export_report": "报表生成完成",
        "export_chart": "图表导出完成"
    }
    
    return tool_success_messages.get(tool_name, "操作完成")


def _build_system_prompt(base_chat_prompt: Optional[str]) -> str:
    base = (base_chat_prompt or "").strip()
    if base:
        return f"{base}\n\n---\n\n{AGENT_TOOL_INSTRUCTIONS}"
    return AGENT_TOOL_INSTRUCTIONS


# 过滤历史时需要剔除的 MCP 过程噪声前缀
_MCP_NOISE_PREFIXES = (
    "[MCP已连接]",
    "[MCP tools/call]",
    "[工具结果]",
    "[工具错误]",
    "[已达最大 MCP 工具调用轮次]",
)


def _sanitize_history(history: Optional[List[Dict[str, Any]]]) -> List[Dict[str, Any]]:
    """
    过滤前端回传的历史，仅保留 user/assistant 轮次，并剥离 MCP 过程噪声。
    - assistant 消息中会被逐行过滤掉以 _MCP_NOISE_PREFIXES 开头的行
    - 剥离后若 content 为空则整条丢弃
    - 最多保留最近 20 轮，避免上下文过长
    """
    if not history:
        return []
    cleaned: List[Dict[str, Any]] = []
    for item in history:
        if not isinstance(item, dict):
            continue
        role = item.get("role")
        content = item.get("content")
        if role not in ("user", "assistant") or not isinstance(content, str):
            continue
        if role == "assistant":
            kept_lines = [
                ln for ln in content.splitlines()
                if not ln.lstrip().startswith(_MCP_NOISE_PREFIXES)
            ]
            content = "\n".join(kept_lines).strip()
            if not content:
                continue
        else:
            content = content.strip()
            if not content:
                continue
        cleaned.append({"role": role, "content": content})
    return cleaned[-20:]


def _normalize_tool_calls(tool_calls: Any) -> List[Dict[str, Any]]:
    """
    兼容 Ollama 可能返回的不同 tool_calls 格式：
    - 标准格式: [{"function": {"name": "...", "arguments": {...}}}]
    - 单对象: {"function": {...}}
    - 直接是函数列表: [{"name": "...", "arguments": {...}}]
    """
    if not tool_calls:
        return []
    if isinstance(tool_calls, dict):
        # 单对象格式
        if "function" in tool_calls:
            return [tool_calls]
        # 可能是直接 {"name": "...", "arguments": {...}}
        if "name" in tool_calls:
            return [{"function": tool_calls}]
        return []
    if isinstance(tool_calls, list):
        normalized = []
        for tc in tool_calls:
            if isinstance(tc, dict):
                if "function" in tc:
                    normalized.append(tc)
                elif "name" in tc:
                    normalized.append({"function": tc})
                else:
                    # 未知格式，跳过
                    continue
        return normalized
    return []


async def _ensure_time_range_for_tool(
    session: ClientSession,
    tool_name: str,
    args: Dict[str, Any],
    energy_type: Optional[str] = None,
) -> Tuple[Dict[str, Any], Optional[str]]:
    """
    为 detect_anomaly 和 calculate_cop 自动补充时间参数。
    返回 (修改后的 args, 补充说明文本)，若无修改则返回原 args 和 None。
    """
    needs_time_filter = tool_name in ("detect_anomaly", "calculate_cop")
    if not needs_time_filter:
        return args, None
    
    # 已有 start_time 和 end_time 则直接使用
    if args.get("start_time") and args.get("end_time"):
        return args, None
    
    # 获取能源类型（优先从 args，否则使用传入的默认）
    en_type = args.get("energy_type") or energy_type or "electricity"
    
    # 调用 get_data_time_range 获取数据实际最大时间
    try:
        time_range_result = await session.call_tool("get_data_time_range", {"energy_type": en_type})
        result_text = _mcp_result_to_text(time_range_result)
        # 解析 JSON，期望格式 {"minTime": "...", "maxTime": "..."}
        try:
            data = json.loads(result_text)
            max_time = data.get("maxTime")
            if not max_time:
                return args, None
        except json.JSONDecodeError:
            # 如果返回的不是 JSON，尝试用正则提取
            import re
            match = re.search(r'"maxTime":\s*"([^"]+)"', result_text)
            if match:
                max_time = match.group(1)
            else:
                return args, None
        
        # 根据工具类型决定回溯区间
        from datetime import datetime, timedelta
        max_dt = datetime.strptime(max_time, "%Y-%m-%d %H:%M:%S")
        
        if tool_name == "detect_anomaly":
            # 异常检测默认回溯 7 天
            start_dt = max_dt - timedelta(days=7)
            end_dt = max_dt
        else:  # calculate_cop
            # COP 计算默认回溯 90 天（约 3 个月）
            start_dt = max_dt - timedelta(days=90)
            end_dt = max_dt
        
        new_args = args.copy()
        new_args["start_time"] = start_dt.strftime("%Y-%m-%d %H:%M:%S")
        new_args["end_time"] = end_dt.strftime("%Y-%m-%d %H:%M:%S")
        hint = f"（自动补充时间范围：{new_args['start_time']} 至 {new_args['end_time']}）"
        return new_args, hint
    except Exception as e:
        logger.warning(f"[Agent] 自动补充时间范围失败: {e}")
        return args, None


async def run_agent_stream(
    user_prompt: str,
    model: Optional[str] = None,
    temperature: Optional[float] = None,
    system_prompt_source: str = "chat",
    history: Optional[List[Dict[str, Any]]] = None,
) -> AsyncIterator[str]:
    """
    MCP Agent 主循环。以 SSE 分段产出，每段形如
    `data: {"response": "...", "done": false}\\n\\n`

    history: 可选的多轮对话历史（不含当前 user_prompt），每项 {role, content}。
    注入到 system 之后、当前 user_prompt 之前，由 Ollama 作为完整会话上下文处理。
    """
    use_model = model or settings.OLLAMA_MODEL

    base_prompt = get_prompt_loader(system_prompt_source).get_prompt()
    system_prompt = _build_system_prompt(base_prompt)

    options: Dict[str, Any] = {}
    if temperature is not None:
        options["temperature"] = temperature

    messages: List[Dict[str, Any]] = [{"role": "system", "content": system_prompt}]
    prior = _sanitize_history(history)
    if prior:
        messages.extend(prior)
        logger.info(f"[Agent] 注入历史消息 {len(prior)} 条")
    messages.append({"role": "user", "content": user_prompt})

    # 滑动窗口：保留 system + 最近 MAX_CONTEXT_MESSAGES 条消息
    def trim_messages():
        nonlocal messages
        if len(messages) > MAX_CONTEXT_MESSAGES + 1:  # +1 是 system
            # 保留 system 和最近的 MAX_CONTEXT_MESSAGES 条
            messages = [messages[0]] + messages[-(MAX_CONTEXT_MESSAGES):]
            logger.info(f"[Agent] 上下文滑动窗口修剪，当前消息数 {len(messages)}")

    try:
        async with sse_client(settings.MCP_SSE_URL) as (read_stream, write_stream):
            async with ClientSession(read_stream, write_stream) as session:
                await session.initialize()
                tools_result = await session.list_tools()
                tools = _mcp_tools_to_ollama(tools_result.tools)
                logger.info(f"[Agent] MCP 会话就绪，加载 {len(tools)} 个工具")

                yield _sse({
                    "response": f"[MCP已连接] 已加载 {len(tools)} 个工具\n",
                    "done": False,
                })

                async with httpx.AsyncClient(timeout=OLLAMA_TIMEOUT) as ollama:
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
                        raw_tool_calls = msg.get("tool_calls") or []
                        tool_calls = _normalize_tool_calls(raw_tool_calls)

                        assistant_msg: Dict[str, Any] = {
                            "role": msg.get("role", "assistant"),
                            "content": content,
                        }
                        if tool_calls:
                            assistant_msg["tool_calls"] = tool_calls
                        messages.append(assistant_msg)
                        trim_messages()

                        if not tool_calls:
                            if content:
                                yield _sse({"response": content, "done": False})
                            yield _sse({"response": "", "done": True})
                            return

                        # 执行每个工具调用
                        for tc in tool_calls:
                            fn = tc.get("function") or {}
                            name = fn.get("name") or ""
                            args = _normalize_args(fn.get("arguments"))

                            # 自动补充时间参数（针对 detect_anomaly / calculate_cop）
                            energy_type_for_time = None
                            # 尝试从历史或上下文中获取能源类型（简单策略：从 args 或消息内容中找）
                            if "energy_type" in args:
                                energy_type_for_time = args["energy_type"]
                            else:
                                # 简单从最近的 user 或 assistant 消息中找
                                for m in reversed(messages):
                                    if m["role"] in ("user", "assistant"):
                                        text = m.get("content", "")
                                        import re
                                        match = re.search(r"(electricity|water|gas|steam|chilledwater|hotwater|solar|irrigation)", text, re.I)
                                        if match:
                                            energy_type_for_time = match.group(1).lower()
                                            break
                            new_args, time_hint = await _ensure_time_range_for_tool(
                                session, name, args, energy_type_for_time
                            )
                            if time_hint:
                                yield _sse({"response": f"\n{time_hint}\n", "done": False})
                            args = new_args

                            yield _sse({
                                "response": f"\n{_format_tool_call_for_user(name, args)}\n",
                                "done": False,
                            })

                            try:
                                # 带超时的工具调用
                                call_result = await asyncio.wait_for(
                                    session.call_tool(name, args),
                                    timeout=TOOL_CALL_TIMEOUT
                                )
                                tool_result = _mcp_result_to_text(call_result)
                                if getattr(call_result, "isError", False):
                                    tool_result = f"[工具错误] {tool_result}"
                            except asyncio.TimeoutError:
                                logger.warning(f"[Agent] MCP 工具 {name} 超时")
                                tool_result = f"[工具错误] 工具 {name} 执行超时（>{TOOL_CALL_TIMEOUT}秒），请尝试缩小时间范围或改用更粗粒度。"
                            except Exception as e:
                                logger.exception(f"[Agent] MCP 调用失败 {name}")
                                tool_result = f"[工具错误] 工具调用失败: {e}"

                            yield _sse({
                                "response": f"{_format_tool_result_for_user(name, tool_result)}\n\n",
                                "done": False,
                            })

                            messages.append({
                                "role": "tool",
                                "content": tool_result,
                            })
                            trim_messages()

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