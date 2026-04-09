# app/main.py
"""
FastAPI 应用入口
提供 /generate 端点，接收 prompt 并流式返回 Ollama 或 RAGFlow 生成结果
"""

import json
import logging
from contextlib import asynccontextmanager
from typing import Any, Dict

from fastapi import FastAPI, HTTPException, status
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.models.request import ChatRequest, ErrorResponse, HealthResponse
from app.services.prompt_loader import get_prompt_loader
from app.services.ollama_client import get_ollama_client
from app.services.ragflow_client import get_ragflow_client


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
            
            # 如果没有收到任何数据，发送错误信息
            if chunk_count == 0:
                yield f"data: {json.dumps({'error': 'No response from RAGFlow', 'done': True})}\n\n"
                
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
    summary="流式聊天生成",
    description="接收用户 prompt，结合 chat 系统 prompt，流式返回结果（Ollama）",
    responses={
        200: {
            "description": "SSE 流式响应",
            "content": {
                "text/event-stream": {
                    "example": 'data: {"response": "你好", "done": false}\n\n'
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
async def generate_chat(request: ChatRequest):
    """聊天生成端点"""
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


# 保留旧接口兼容（可选，可删除）
@app.post(
    "/generate",
    response_class=StreamingResponse,
    summary="流式生成文本（兼容旧接口）",
    description="默认使用 chat 系统 prompt",
    deprecated=True  # 标记为已弃用
)
async def generate(request: ChatRequest):
    """兼容旧接口，默认使用 chat"""
    return await generate_with_type(request, "chat")


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