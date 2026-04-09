# app/services/ragflow_client.py
"""
RAGFlow 客户端服务
负责与 RAGFlow API 通信，支持流式输出
将 RAGFlow 返回格式转换为 Ollama 兼容格式
"""

import json
from typing import AsyncGenerator, Optional, Dict, Any
import httpx
from fastapi import HTTPException, status
from app.config import settings


class RagflowClient:
    """
    RAGFlow HTTP 客户端
    封装与 RAGFlow /api/conversation/chat 端点的通信
    返回格式统一转换为 Ollama 风格，保持前端兼容
    """
    
    def __init__(self):
        self.base_url = settings.RAGFLOW_BASE_URL.rstrip("/")
        self.api_key = settings.RAGFLOW_API_KEY
        self.chat_id = settings.RAGFLOW_CHAT_ID
        self.chat_endpoint = f"{self.base_url}/api/v1/chat/completions"
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
        stream: bool = True
    ) -> Dict[str, Any]:
        """
        构建 RAGFlow 请求体
        
        Args:
            user_prompt: 用户输入的提示词
            stream: 是否流式输出
            
        Returns:
            RAGFlow 请求体字典
        """
        return {
            "conversation_id": self.chat_id,
            "messages": [
                {"role": "user", "content": user_prompt}
            ],
            "stream": stream
        }
    
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
        
        # 构造 Ollama 兼容格式
        return {
            "response": answer,
            "done": False,  # 流式中由调用方控制
            "model": "ragflow",
            "ragflow_reference": data.get("reference", {})  # 保留 RAGFlow 特有信息
        }
    
    async def generate_stream(
    self,
    user_prompt: str
    ) -> AsyncGenerator[str, None]:
        """
        流式生成文本，产生 SSE 格式的数据块（Ollama 兼容格式）
        
        RAGFlow SSE 规范：
        - 每条消息以 data: 开头，后跟 JSON 字符串
        - 流结束时发送 data: [DONE]
        
        Args:
            user_prompt: 用户输入的提示词
            
        Yields:
            SSE 格式的字符串，如 "data: {...}\n\n"
            
        Raises:
            HTTPException: RAGFlow 错误或连接失败
        """
        body = self._build_request_body(user_prompt, stream=True)
        headers = self._build_headers()
        
        try:
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
                            detail="RAGFlow conversation not found"
                        )
                    
                    response.raise_for_status()
                    
                    # 逐行读取 SSE 流
                    async for line in response.aiter_lines():
                        if not line:
                            continue
                        
                        # RAGFlow SSE 格式：data: {json} 或 data: [DONE]
                        if not line.startswith("data: "):
                            # 不符合规范的行，记录但忽略（保持健壮性）
                            continue
                        
                        data_content = line[6:]  # 去掉 "data: " 前缀
                        
                        # 检查流结束标志
                        if data_content == "[DONE]":
                            yield "data: [DONE]\n\n"
                            break
                        
                        # 解析 JSON 数据块
                        try:
                            chunk_data = json.loads(data_content)
                        except json.JSONDecodeError as e:
                            # 解析失败时发送错误信息并终止流
                            error_msg = f"解析 RAGFlow 响应失败: {data_content[:100]}"
                            yield f"data: {json.dumps({'error': error_msg})}\n\n"
                            break
                        
                        # 转换为 Ollama 兼容格式
                        # RAGFlow 流式块通常包含 answer 字段（增量或完整文本）
                        answer = chunk_data.get("answer", "")
                        # 某些版本可能将 answer 嵌套在 data 字段中
                        if not answer and "data" in chunk_data:
                            answer = chunk_data.get("data", {}).get("answer", "")
                        
                        # 判断是否为最后一个块（根据 RAGFlow 特定字段）
                        is_end = chunk_data.get("is_end", False)
                        if "data" in chunk_data and isinstance(chunk_data["data"], dict):
                            is_end = chunk_data["data"].get("is_end", is_end)
                        
                        transformed = {
                            "response": answer,
                            "done": is_end,
                            "model": "ragflow"
                        }
                        
                        # 保留引用信息（如果存在）
                        reference = chunk_data.get("reference")
                        if not reference and "data" in chunk_data:
                            reference = chunk_data.get("data", {}).get("reference")
                        if reference:
                            transformed["ragflow_reference"] = reference
                        
                        # 输出 SSE 块
                        yield f"data: {json.dumps(transformed, ensure_ascii=False)}\n\n"
                        
                        # 如果标记为结束，发送结束标志并退出
                        if is_end:
                            yield "data: [DONE]\n\n"
                            break
                            
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
    
    async def generate(
        self,
        user_prompt: str
    ) -> Dict[str, Any]:
        """
        非流式生成，等待完整响应后返回（Ollama 兼容格式）
        
        Args:
            user_prompt: 用户输入的提示词
            
        Returns:
            完整的 Ollama 风格响应字典
        """
        body = self._build_request_body(user_prompt, stream=False)
        headers = self._build_headers()
        
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    self.chat_endpoint,
                    json=body,
                    headers=headers
                )
                
                if response.status_code == 401:
                    raise HTTPException(
                        status_code=status.HTTP_401_UNAUTHORIZED,
                        detail="RAGFlow API key invalid"
                    )
                elif response.status_code == 404:
                    raise HTTPException(
                        status_code=status.HTTP_404_NOT_FOUND,
                        detail="RAGFlow conversation not found"
                    )
                
                response.raise_for_status()
                ragflow_data = response.json()
                
                # 转换为 Ollama 格式并标记完成
                transformed = self._transform_response(ragflow_data)
                transformed["done"] = True
                return transformed
                
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
            # RAGFlow 没有专门的 health 端点，用简单请求验证
            async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
                response = await client.get(
                    f"{self.base_url}/api/v1/conversations",
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