# app/services/ragflow_client.py
"""
RAGFlow 客户端服务
负责与 RAGFlow API 通信，支持流式输出
将 RAGFlow 返回格式转换为 Ollama 兼容格式
"""

import json
import logging
import asyncio
from typing import AsyncGenerator, Optional, Dict, Any
import httpx
from fastapi import HTTPException, status
from app.config import settings

# 添加这行
logger = logging.getLogger(__name__)

class RagflowNoDocumentsException(Exception):
    """RAGFlow 未检索到相关文档的异常，用于触发降级到 Ollama"""
    pass


class RagflowTimeoutException(Exception):
    """RAGFlow 响应超时的异常，用于触发降级到 Ollama"""
    pass

class RagflowClient:
    """
    RAGFlow HTTP 客户端
    封装与 RAGFlow /api/v1/chats/{chat_id}/completions 端点的通信
    返回格式统一转换为 Ollama 风格，保持前端兼容
    """
    
    def __init__(self):
        self.base_url = settings.RAGFLOW_BASE_URL.rstrip("/")
        self.api_key = settings.RAGFLOW_API_KEY
        self.chat_id = settings.RAGFLOW_CHAT_ID
        # 关键修复：修正端点路径，添加 chats/ 和 chat_id
        self.chat_endpoint = f"{self.base_url}/api/v1/chats/{self.chat_id}/completions"
        # 使用配置的超时值
        self.timeout = httpx.Timeout(settings.RAGFLOW_TIMEOUT, connect=10.0)
        # 超时降级阈值（秒），超过此时间未收到响应则降级
        self.timeout_threshold = settings.RAGFLOW_TIMEOUT * 0.8
    
    def _build_headers(self) -> Dict[str, str]:
        """
        构建 RAGFlow 请求头
        
        Returns:
            包含认证信息的请求头
        """
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}"
        }
    
    def _build_request_body(
        self,
        user_prompt: str,
        stream: bool = True,
        session_id: Optional[str] = None  # 新增
    ) -> Dict[str, Any]:
        """
        构建 RAGFlow 请求体
        
        Args:
            user_prompt: 用户输入的提示词
            stream: 是否流式输出
            
        Returns:
            RAGFlow 请求体字典
        """
        # 关键修复：RAGFlow 原生 API 使用 question 字段，不是 messages 数组
        body = {
        "question": user_prompt,
        "stream": stream,
        "quote": False
        }
        if session_id:
            body["session_id"] = session_id  # 带上会话ID
        return body
    
    def _transform_response(self, ragflow_data: Dict[str, Any]) -> Dict[str, Any]:
        """
        将 RAGFlow 响应转换为 Ollama 兼容格式
        
        RAGFlow 格式: {"code": 0, "data": {"answer": "...", "reference": {...}}}
        Ollama 格式: {"response": "...", "done": false, "model": "..."}
        
        Args:
            ragflow_data: RAGFlow 原始响应
            
        Returns:
            Ollama 风格的响应字典
        """
        # 检查输入是否为字典类型，避免布尔值错误
        if not isinstance(ragflow_data, dict):
            logger.warning(f"Received non-dict response from RAGFlow: {ragflow_data}")
            # 返回空的响应，让调用方处理
            return {
                "response": "",
                "done": False,
                "model": "ragflow",
                "ragflow_reference": {}
            }
        
        # 检查 RAGFlow 错误码
        if ragflow_data.get("code", 0) != 0:
            error_msg = ragflow_data.get("message", "Unknown RAGFlow error")
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"RAGFlow error: {error_msg}"
            )
        
        data = ragflow_data.get("data", {})
        answer = data.get("answer", "")

        # 构造 Ollama 兼容格式
        return {
            "response": answer,
            "done": False,  # 流式中由调用方控制
            "model": "ragflow",
            "ragflow_reference": data.get("reference", {})  # 保留 RAGFlow 特有信息
        }
    
    def _is_no_documents_response(self, answer: str, reference: Optional[Dict]) -> bool:
        """
        判断是否未检索到相关文档
        
        检测逻辑：检测 answer 中是否包含特定文本
        """
        # 检测 answer 中是否包含特定文本
        target_text = "The answer you are looking for is not found in the knowledge base!"
        return target_text in answer
    
    async def generate_stream(
        self,
        user_prompt: str
    ) -> AsyncGenerator[str, None]:
        """
        流式生成文本，产生 SSE 格式的数据块（Ollama 兼容格式）

        策略：先建立会话获取 session_id，再发送真正问题（避免欢迎语）
        如果未检索到文档，抛出 RagflowNoDocumentsException 触发降级
        如果响应超时，抛出 RagflowTimeoutException 触发降级
        """
        headers = self._build_headers()

        try:
            # 步骤1：建立会话，获取 session_id
            session_id = None
            init_body = self._build_request_body("hi", stream=True)

            async with httpx.AsyncClient(timeout=self.timeout) as client:
                async with client.stream(
                    "POST",
                    self.chat_endpoint,
                    json=init_body,
                    headers=headers
                ) as response:
                    response.raise_for_status()

                    async for line in response.aiter_lines():
                        if not line or not line.startswith("data:"):
                            continue

                        data_content = line[5:]

                        try:
                            chunk_data = json.loads(data_content)
                            data_field = chunk_data.get("data", {})
                            if isinstance(data_field, dict):
                                session_id = data_field.get("session_id")
                                if session_id:
                                    logger.info(f"Got session_id: {session_id}")
                                    break
                        except json.JSONDecodeError:
                            continue

            # 步骤2：使用 session_id 发送真正请求
            body = self._build_request_body(user_prompt, stream=True, session_id=session_id)

            # ---------- 超时检测变量 ----------
            start_time = asyncio.get_event_loop().time()        # 请求开始时间
            last_response_time = start_time                     # 最后收到数据的时间
            response_started = False                            # 是否收到过数据
            first_chunk_received = False                        # 是否收到首块

            async with httpx.AsyncClient(timeout=self.timeout) as client:
                async with client.stream(
                    "POST",
                    self.chat_endpoint,
                    json=body,
                    headers=headers
                ) as response:
                    # RAGFlow 错误处理
                    if response.status_code == 401:
                        raise HTTPException(
                            status_code=status.HTTP_401_UNAUTHORIZED,
                            detail="RAGFlow API key invalid"
                        )
                    elif response.status_code == 404:
                        raise HTTPException(
                            status_code=status.HTTP_404_NOT_FOUND,
                            detail="RAGFlow chat not found"
                        )

                    response.raise_for_status()

                    full_answer = ""
                    full_reference = None
                    is_empty_retrieval = False

                    # 逐行读取 SSE 流
                    async for line in response.aiter_lines():
                        current_time = asyncio.get_event_loop().time()

                        # 首字节超时检测：若还未收到任何数据，检查是否超过阈值
                        if not first_chunk_received:
                            if current_time - start_time > self.timeout_threshold:
                                logger.warning(f"RAGFlow 首字节超时，超过 {self.timeout_threshold} 秒未收到数据")
                                raise RagflowTimeoutException("RAGFlow 首字节超时")
                            # 注意：此时还未收到有效数据，继续等待

                        # 如果已经收到过数据，检查相邻数据块间隔超时
                        if response_started:
                            if current_time - last_response_time > self.timeout_threshold:
                                logger.warning(f"RAGFlow 响应间隔超时，超过 {self.timeout_threshold} 秒未收到新数据")
                                raise RagflowTimeoutException("RAGFlow 响应间隔超时")

                        # 标记已收到数据（此时 line 非空或至少是个有效行）
                        if not response_started:
                            response_started = True
                        # 重要：立即更新最后收到时间，避免处理耗时影响检测
                        last_response_time = current_time

                        # 跳过空行
                        if not line:
                            continue

                        # 只处理 SSE data 行
                        if not line.startswith("data:"):
                            continue

                        data_content = line[5:]

                        # 标记首块已收到（只要进入过这个处理分支）
                        if not first_chunk_received:
                            first_chunk_received = True
                            # 重新记录最后收到时间，避免首块处理延迟影响下一个间隔判断
                            last_response_time = current_time

                        # 检查流结束标志
                        if data_content == "true" or data_content == "[DONE]":
                            # 结束前检查是否空检索
                            if self._is_no_documents_response(full_answer, full_reference):
                                is_empty_retrieval = True
                                break
                            yield "data: [DONE]\n\n"
                            break

                        # 解析 JSON 数据块
                        try:
                            chunk_data = json.loads(data_content)
                        except json.JSONDecodeError:
                            continue

                        if not isinstance(chunk_data, dict):
                            logger.debug(f"Skipping non-dict chunk: {chunk_data}")
                            continue

                        # 转换响应格式
                        try:
                            transformed = self._transform_response(chunk_data)
                            answer = transformed.get("response", "")
                            is_end = transformed.get("done", False)
                            reference = transformed.get("ragflow_reference")

                            full_answer += answer
                            if reference:
                                full_reference = reference
                        except HTTPException:
                            raise
                        except Exception as e:
                            logger.warning(f"Failed to transform RAGFlow response: {e}")
                            continue

                        # 输出 SSE 块
                        yield f"data: {json.dumps(transformed, ensure_ascii=False)}\n\n"

                        if is_end:
                            # 检查空检索
                            if self._is_no_documents_response(full_answer, full_reference):
                                is_empty_retrieval = True
                                break
                            yield "data: [DONE]\n\n"
                            break

                    # 流自然结束（未收到结束标志）时，补充检查空检索
                    if not is_empty_retrieval and self._is_no_documents_response(full_answer, full_reference):
                        is_empty_retrieval = True

                    if is_empty_retrieval:
                        raise RagflowNoDocumentsException(f"RAGFlow 未检索到相关文档，answer: {full_answer[:100]}...")

        except httpx.HTTPStatusError as e:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"RAGFlow HTTP error: {e.response.status_code}"
            )
        except httpx.ConnectError:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="RAGFlow service unavailable"
            )
        except httpx.TimeoutException:
            logger.warning("RAGFlow 请求总超时，触发降级")
            raise RagflowTimeoutException("RAGFlow 请求总超时")
    
    async def check_connection(self) -> bool:
        """
        检查 RAGFlow 服务是否可连接
        
        通过尝试获取对话列表或轻量级请求验证
        
        Returns:
            是否连接成功
        """
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
                response = await client.get(
                    f"{self.base_url}/api/v1/chats",
                    headers=self._build_headers(),
                    params={"page": 1, "page_size": 1}
                )
                return response.status_code == 200
        except Exception:
            return False


# 全局客户端实例
_ragflow_client: Optional[RagflowClient] = None


def get_ragflow_client() -> Optional[RagflowClient]:
    """
    获取 RagflowClient 单例
    
    Returns:
        RagflowClient 实例，如果未启用则返回 None
    """
    global _ragflow_client
    
    if not settings.RAGFLOW_ENABLED:
        return None
    
    if _ragflow_client is None:
        _ragflow_client = RagflowClient()
    
    return _ragflow_client


# 便捷函数
async def generate_ragflow_stream(user_prompt: str) -> AsyncGenerator[str, None]:
    """
    快速 RAGFlow 流式生成函数
    
    Args:
        user_prompt: 用户输入的提示词
        
    Yields:
        SSE 格式的数据块（Ollama 兼容格式）
        
    Raises:
        HTTPException: RAGFlow 未启用或错误
    """
    client = get_ragflow_client()
    if client is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="RAGFlow not enabled"
        )
    
    async for chunk in client.generate_stream(user_prompt):
        yield chunk

# 在文件底部导出异常类
__all__ = ['RagflowClient', 'get_ragflow_client', 'generate_ragflow_stream', 'RagflowNoDocumentsException', 'RagflowTimeoutException']