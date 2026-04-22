# app/main.py
"""
FastAPI 应用入口
提供 /generate 端点，接收 prompt 并流式返回 Ollama 或 RAGFlow 生成结果
"""

import json
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Dict

from fastapi import FastAPI, HTTPException, status
from fastapi.responses import StreamingResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.models.request import ChatRequest, ErrorResponse, HealthResponse
from app.services.prompt_loader import get_prompt_loader
from app.services.ollama_client import get_ollama_client
from app.services.ragflow_client import get_ragflow_client, RagflowNoDocumentsException, RagflowTimeoutException
from app.mcp_server import mcp as mcp_server

# 使用配置的路径
EXPORTS_DIR = Path(settings.EXPORTS_DIR)

# 配置日志
logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    应用生命周期管理
    启动时验证配置，关闭时清理资源
    """
    # 启动时验证
    logger.info("正在启动服务...")
    
    # 验证配置
    errors = settings.validate()
    if errors:
        for error in errors:
            logger.error(f"配置错误: {error}")
        raise RuntimeError("配置验证失败")
    
    # 验证系统 prompt 可加载（检查 chat 作为代表）
    loader = get_prompt_loader("chat")
    system_prompt = loader.get_prompt()
    if system_prompt:
        logger.info(f"系统 prompt 已加载，长度: {len(system_prompt)} 字符")
    else:
        logger.warning("系统 prompt 未加载或文件为空")
    
    # 检查 Ollama 连接
    client = get_ollama_client()
    if await client.check_connection():
        logger.info(f"Ollama 服务连接成功: {settings.OLLAMA_URL}")
    else:
        logger.warning(f"Ollama 服务连接失败: {settings.OLLAMA_URL}")
    
    # 检查 RAGFlow 连接（如果启用）
    if settings.RAGFLOW_ENABLED:
        ragflow_client = get_ragflow_client()
        if ragflow_client and await ragflow_client.check_connection():
            logger.info(f"RAGFlow 服务连接成功: {settings.RAGFLOW_BASE_URL}")
        else:
            logger.warning(f"RAGFlow 服务连接失败: {settings.RAGFLOW_BASE_URL}")
    
    logger.info(f"服务启动完成，监听 {settings.API_HOST}:{settings.API_PORT}")
    
    yield
    
    # 关闭时清理
    logger.info("正在关闭服务...")


# 创建 FastAPI 实例
app = FastAPI(
    title="Ollama Gateway MVP",
    description="SpringBoot 与 Ollama/RAGFlow 之间的流式网关服务",
    version="1.0.0",
    lifespan=lifespan
)

# 添加 CORS 中间件（允许 SpringBoot 跨域访问）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 生产环境应限制为具体域名
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 挂载 MCP (Model Context Protocol) SSE 子应用
# 暴露 /mcp/sse (SSE 握手) 与 /mcp/messages/ (消息通道)
# 不影响现有 /generate/* 路由与 RAGFlow 逻辑
app.mount("/mcp", mcp_server.sse_app())

# 挂载 /exports 静态目录：MCP export_report / export_chart 工具
# 把后端生成的 xlsx/csv 落盘后，用户通过 http://host:port/exports/<filename> 下载
# 确保目录存在，避免 StaticFiles 抛出 RuntimeError
EXPORTS_DIR.mkdir(parents=True, exist_ok=True)
app.mount(settings.EXPORTS_URL_PATH, StaticFiles(directory=str(EXPORTS_DIR)), name="exports")


async def generate_with_type(request: ChatRequest, prompt_type: str):
    """
    通用生成逻辑，根据类型选择 Ollama 或 RAGFlow

    Args:
        request: 请求体
        prompt_type: prompt 类型 (chat/generatesql/analyse)
    """
    logger.info(f"收到 [{prompt_type}] 生成请求，prompt长度: {len(request.prompt)}")

    # analyse 类型且启用 RAGFlow 时，使用 RAGFlow
    if prompt_type == "analyse" and settings.RAGFLOW_ENABLED:
        return await _generate_with_ragflow(request, prompt_type)
    else:
        return await _generate_with_ollama(request, prompt_type)


async def _run_agent_response(request: ChatRequest, prompt_source: str = "chat"):
    """
    通过 MCP Agent（Ollama tool-calling + 真实 MCP 协议）处理请求。
    调用链：/generate/* → agent_service → MCP SSE → MCP Server → Spring Boot REST
    """
    from app.services.agent_service import run_agent_stream

    # 快速失败：Ollama 不可用
    client = get_ollama_client()
    if not await client.check_connection():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Ollama 服务暂时不可用"
        )

    logger.info(f"收到 [agent:{prompt_source}] 请求，prompt长度: {len(request.prompt)}")

    async def event_stream():
        async for chunk in run_agent_stream(
            user_prompt=request.prompt,
            model=request.model,
            temperature=request.temperature,
            system_prompt_source=prompt_source,
            history=request.messages,
        ):
            yield chunk

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
            "Content-Type": "text/event-stream; charset=utf-8",
        }
    )


async def _generate_with_ollama(request: ChatRequest, prompt_type: str):
    """
    使用 Ollama 生成
    
    Args:
        request: 请求体
        prompt_type: prompt 类型
    """
    # 获取对应类型的系统 prompt
    loader = get_prompt_loader(prompt_type)
    system_prompt = loader.get_prompt()
    
    if not system_prompt:
        logger.error(f"[{prompt_type}] 系统 prompt 加载失败")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"[{prompt_type}] 系统 prompt 未配置或文件读取失败"
        )
    
    # 获取 Ollama 客户端
    client = get_ollama_client()
    
    # 检查 Ollama 连接（可选，快速失败）
    if not await client.check_connection():
        logger.error("Ollama 服务不可用")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Ollama 服务暂时不可用"
        )
    
    # 定义流式生成器
    async def event_stream():
        """SSE 事件流生成器"""
        try:
            # 构建 options 参数
            options: Dict[str, Any] = {}
            if request.temperature is not None:
                options["temperature"] = request.temperature

            chunk_count = 0
            async for chunk in client.generate_stream(
                user_prompt=request.prompt,
                model=request.model,
                system_prompt=system_prompt,
                options=options if options else None,
                think=False
            ):
                yield chunk
                chunk_count += 1
            
            logger.info(f"[{prompt_type}] 流式响应完成，共 {chunk_count} 个 chunk")
            
            # 如果没有收到任何数据，发送错误信息
            if chunk_count == 0:
                yield f"data: {json.dumps({'error': 'No response from Ollama', 'done': True})}\n\n"
                
        except Exception as e:
            logger.error(f"[{prompt_type}] 流式生成异常: {e}")
            error_data = {
                "error": str(e),
                "error_type": type(e).__name__,
                "done": True
            }
            yield f"data: {json.dumps(error_data)}\n\n"
    
    # 返回 SSE 流式响应
    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # 禁用 Nginx 缓冲
            "Content-Type": "text/event-stream; charset=utf-8"
        }
    )


async def _generate_with_ragflow(request: ChatRequest, prompt_type: str):
    """
    使用 RAGFlow 生成（仅 analyse 类型使用）
    
    Args:
        request: 请求体
        prompt_type: 固定为 analyse
    """
    # 获取 RAGFlow 客户端
    client = get_ragflow_client()
    if client is None:
        logger.error("RAGFlow 未启用或配置错误")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="RAGFlow 服务未启用"
        )
    
    # 检查 RAGFlow 连接
    if not await client.check_connection():
        logger.error("RAGFlow 服务不可用")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="RAGFlow 服务暂时不可用"
        )
    
    # 定义流式生成器
    async def event_stream():
        """SSE 事件流生成器"""
        try:
            chunk_count = 0
            async for chunk in client.generate_stream(
                user_prompt=request.prompt
            ):
                yield chunk
                chunk_count += 1
            
            logger.info(f"[{prompt_type}] RAGFlow 流式响应完成，共 {chunk_count} 个 chunk")
            
            # 如果没有收到任何数据，降级到 Ollama
            if chunk_count == 0:
                logger.warning(f"[{prompt_type}] RAGFlow 返回空响应，降级到 Ollama")
                raise RagflowNoDocumentsException("RAGFlow 返回空响应")
                
        except (RagflowNoDocumentsException, RagflowTimeoutException) as e:
            # 关键修改：未检索到文档或超时，降级到 Ollama
            if isinstance(e, RagflowNoDocumentsException):
                logger.warning(f"[{prompt_type}] RAGFlow 未检索到文档，降级到 Ollama: {e}")
                error_reason = "未检索到文档"
            else:
                logger.warning(f"[{prompt_type}] RAGFlow 响应超时，降级到 Ollama: {e}")
                error_reason = "响应超时"
            
            # 获取 analyse 系统 prompt
            loader = get_prompt_loader("analyse")
            system_prompt = loader.get_prompt()
            
            if not system_prompt:
                logger.error(f"[{prompt_type}] analyse 系统 prompt 加载失败，无法降级")
                error_data = {
                    "error": f"RAGFlow {error_reason}，且 analyse 系统 prompt 未配置",
                    "error_type": "RagflowFallbackFailed",
                    "done": True
                }
                yield f"data: {json.dumps(error_data)}\n\n"
                return
            
            # 获取 Ollama 客户端并检查连接
            ollama_client = get_ollama_client()
            if not await ollama_client.check_connection():
                logger.error("Ollama 服务不可用，无法降级")
                error_data = {
                    "error": f"RAGFlow {error_reason}，且 Ollama 服务不可用",
                    "error_type": "RagflowFallbackFailed",
                    "done": True
                }
                yield f"data: {json.dumps(error_data)}\n\n"
                return
            
            # 使用 Ollama 生成，使用 analyse 系统 prompt
            logger.info(f"[{prompt_type}] 开始使用 Ollama 降级生成，使用 analyse 系统 prompt")
            
            options: Dict[str, Any] = {}
            if request.temperature is not None:
                options["temperature"] = request.temperature
            
            fallback_chunk_count = 0
            async for chunk in ollama_client.generate_stream(
                user_prompt=request.prompt,
                model=request.model,
                system_prompt=system_prompt,  # 使用 analyse 系统 prompt
                options=options if options else None,
                think=False
            ):
                yield chunk
                fallback_chunk_count += 1
            
            logger.info(f"[{prompt_type}] Ollama 降级流式响应完成，共 {fallback_chunk_count} 个 chunk")
                           
        except Exception as e:
            logger.error(f"[{prompt_type}] RAGFlow 流式生成异常: {e}")
            error_data = {
                "error": str(e),
                "error_type": type(e).__name__,
                "done": True
            }
            yield f"data: {json.dumps(error_data)}\n\n"
    
    # 返回 SSE 流式响应
    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
            "Content-Type": "text/event-stream; charset=utf-8"
        }
    )


@app.post(
    "/generate/chat",
    response_class=StreamingResponse,
    summary="流式聊天生成（运维知识问答，纯 Ollama）",
    description=(
        "接收用户 prompt，由 Ollama 结合 chat 系统 prompt 生成回复。\n"
        "此端点仅做纯对话；需要真实数据查询的请求请走 /generate/agent（MCP）。"
    ),
    responses={
        200: {
            "description": "SSE 流式响应",
            "content": {
                "text/event-stream": {
                    "example": 'data: {"response": "你好", "done": false}\n\n'
                }
            }
        },
        422: {"description": "请求参数验证失败", "model": ErrorResponse},
        503: {"description": "Ollama 服务不可用", "model": ErrorResponse}
    }
)
async def generate_chat(request: ChatRequest):
    """聊天生成端点（纯 Ollama，运维知识问答用）"""
    return await generate_with_type(request, "chat")


@app.post(
    "/generate/generatesql",
    response_class=StreamingResponse,
    summary="流式 SQL 生成",
    description="接收用户 prompt，结合 generatesql 系统 prompt，流式返回结果（Ollama）",
    responses={
        200: {
            "description": "SSE 流式响应",
            "content": {
                "text/event-stream": {
                    "example": 'data: {"response": "SELECT", "done": false}\n\n'
                }
            }
        },
        422: {
            "description": "请求参数验证失败",
            "model": ErrorResponse
        },
        503: {
            "description": "Ollama 服务不可用",
            "model": ErrorResponse
        }
    }
)
async def generate_sql(request: ChatRequest):
    """SQL 生成端点"""
    return await generate_with_type(request, "generatesql")


@app.post(
    "/generate/analyse",
    response_class=StreamingResponse,
    summary="流式分析生成",
    description="接收用户 prompt，流式返回结果（RAGFlow 或 Ollama）",
    responses={
        200: {
            "description": "SSE 流式响应",
            "content": {
                "text/event-stream": {
                    "example": 'data: {"response": "分析", "done": false}\n\n'
                }
            }
        },
        422: {
            "description": "请求参数验证失败",
            "model": ErrorResponse
        },
        503: {
            "description": "服务不可用",
            "model": ErrorResponse
        }
    }
)
async def generate_analyse(request: ChatRequest):
    """
    分析生成端点
    
    根据配置自动选择：
    - RAGFLOW_ENABLED=true → 使用 RAGFlow
    - RAGFLOW_ENABLED=false → 使用 Ollama + analyse 系统 prompt
    """
    return await generate_with_type(request, "analyse")

@app.post(
    "/generate/agent",
    response_class=StreamingResponse,
    summary="MCP Agent 对话（可主动查询真实数据）",
    description=(
        "基于 Ollama tool-calling + MCP 协议的智能问答：\n"
        "模型会根据问题自主决定是否调用 MCP 工具（query_energy_data / "
        "summary_statistics / calculate_cop / detect_anomaly / generate_chart）"
        "从 Spring Boot 拉取真实数据后再作答。"
    ),
    responses={
        200: {
            "description": "SSE 流式响应，与 /generate/chat 同格式",
            "content": {
                "text/event-stream": {
                    "example": 'data: {"response": "[MCP工具调用] ...", "done": false}\n\n'
                }
            }
        },
        503: {"description": "Ollama 服务不可用", "model": ErrorResponse},
    }
)
async def generate_agent(request: ChatRequest):
    """MCP Agent 显式端点（与 /generate/chat 相同行为，命名更明确便于答辩演示）"""
    return await _run_agent_response(request, prompt_source="chat")


@app.get(
    "/health",
    response_model=HealthResponse,
    summary="健康检查",
    description="检查服务及依赖状态"
)
async def health():
    """
    健康检查端点
    SpringBoot 可用此端点做服务发现和健康监测
    """
    client = get_ollama_client()
    
    # 检查所有 prompt 类型
    chat_ok = get_prompt_loader("chat").get_prompt() is not None
    sql_ok = get_prompt_loader("generatesql").get_prompt() is not None
    
    # analyse 类型：RAGFlow 启用时检查 RAGFlow，否则检查本地 prompt
    if settings.RAGFLOW_ENABLED:
        ragflow_client = get_ragflow_client()
        analyse_ok = ragflow_client is not None and await ragflow_client.check_connection()
    else:
        analyse_ok = get_prompt_loader("analyse").get_prompt() is not None
    
    ollama_ok = await client.check_connection()
    all_prompt_ok = chat_ok and sql_ok and analyse_ok
    
    status_value = "ok" if (ollama_ok and all_prompt_ok) else "error"
    
    return HealthResponse(
        status=status_value,
        ollama_connected=ollama_ok,
        system_prompt_loaded=all_prompt_ok
    )


@app.get(
    "/prompt/info",
    summary="Prompt 文件信息",
    description="查看系统 prompt 文件状态（调试用）"
)
async def prompt_info():
    """获取系统 prompt 文件元数据"""
    info = {
        "chat": get_prompt_loader("chat").get_file_info(),
        "generatesql": get_prompt_loader("generatesql").get_file_info(),
    }
    
    # analyse 类型：RAGFlow 启用时显示配置，否则显示文件信息
    if settings.RAGFLOW_ENABLED:
        info["analyse"] = {
            "source": "ragflow",
            "enabled": True,
            "base_url": settings.RAGFLOW_BASE_URL,
            "chat_id": settings.RAGFLOW_CHAT_ID
        }
    else:
        info["analyse"] = get_prompt_loader("analyse").get_file_info()
    
    return info


# 启动入口
if __name__ == "__main__":
    import uvicorn
    
    uvicorn.run(
        "app.main:app",
        host=settings.API_HOST,
        port=settings.API_PORT,
        reload=False,  # 生产环境设为 False
        log_level=settings.LOG_LEVEL.lower()
    )