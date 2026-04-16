# app/services/ragflow_client.py
"""
RAGFlow 客户端服务
负责与 RAGFlow API 通信，支持流式输出
将 RAGFlow 返回格式转换为 Ollama 兼容格式
"""

import json
import logging
from typing import AsyncGenerator, Optional, Dict, Any
import httpx
from fastapi import HTTPException, status
from app.config import settings

# 添加这行
logger = logging.getLogger(__name__)

class RagflowNoDocumentsException(Exception):
    """RAGFlow 未检索到相关文档的异常，用于触发降级到 Ollama"""
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
        self.timeout = httpx.Timeout(60.0, connect=10.0)
    
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
        "quote": True
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
        # 检查 RAGFlow 错误码
        if ragflow_data.get("code", 0) != 0:
            error_msg = ragflow_data.get("message", "Unknown RAGFlow error")
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"RAGFlow error: {error_msg}"
            )
        
        data = ragflow_data.get("data", {})
        answer = data.get("answer", "")
        
        # 删除 [ID:x] 格式的文本
        import re
        answer = re.sub(r'\[ID:\d+\]', '', answer)
        
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
        
        检测逻辑：
        1. reference 为空或 None
        2. answer 包含常见\"未找到\"提示词
        """
        # 检查引用是否为空
        if not reference or reference == {}:
            return True
        
        # 检查引用内容（RAGFlow reference 结构可能包含 chunks 或 doc_aggs）
        has_chunks = False
        if isinstance(reference, dict):
            chunks = reference.get("chunks", [])
            doc_aggs = reference.get("doc_aggs", [])
            total_tokens = reference.get("total_tokens", 0)

            if chunks or doc_aggs or total_tokens > 0:
                has_chunks = True
        
        if not has_chunks:
            return True
        
        # 检查 answer 是否包含\"未找到\"提示（可选，根据实际 RAGFlow 提示词调整）
        no_doc_keywords = [
            "根据已知信息无法回答",
            "我没有找到相关信息",
            "抱歉，我不知道",
            "无法从给定信息中找到答案",
            "no relevant information found",
            "don't have enough information",
        ]
        
        answer_lower = answer.lower()
        for keyword in no_doc_keywords:
            if keyword.lower() in answer_lower:
                return True
        
        return False
    
    async def generate_stream(
        self,
        user_prompt: str
    ) -> AsyncGenerator[str, None]:
        """
        流式生成文本，产生 SSE 格式的数据块（Ollama 兼容格式）
        
        策略：先建立会话获取 session_id，再发送真正问题（避免欢迎语）
        如果未检索到文档，抛出 RagflowNoDocumentsException 触发降级
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
                        if not line:
                            continue
                        
                        if not line.startswith("data:"):
                            continue
                        
                        data_content = line[5:]
                        
                        # 检查流结束标志
                        if data_content == "true" or data_content == "[DONE]":
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
                        
                        # 解析 RAGFlow 响应结构
                        data_field = chunk_data.get("data", {})
                        if isinstance(data_field, dict):
                            answer = data_field.get("answer", "")
                            is_end = data_field.get("is_end", False)
                            reference = data_field.get("reference")
                        else:
                            answer = chunk_data.get("answer", "")
                            is_end = chunk_data.get("is_end", False)
                            reference = chunk_data.get("reference")
                        
                        # 累积内容用于最后检查
                        full_answer += answer
                        if reference:
                            full_reference = reference

                        transformed = {
                            "response": answer,
                            "done": is_end,
                            "model": "ragflow"
                        }
                        
                        if reference:
                            transformed["ragflow_reference"] = reference
                        
                        # 输出 SSE 块
                        yield f"data: {json.dumps(transformed, ensure_ascii=False)}\n\n"
                        
                        # 如果标记为结束，发送结束标志并退出
                        if is_end:
                            if self._is_no_documents_response(full_answer, full_reference):
                                is_empty_retrieval = True
                                break
                            yield "data: [DONE]\n\n"
                            break
                    
                    # 如果未检索到文档，抛出异常触发降级
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
__all__ = ['RagflowClient', 'get_ragflow_client', 'generate_ragflow_stream', 'RagflowNoDocumentsException']