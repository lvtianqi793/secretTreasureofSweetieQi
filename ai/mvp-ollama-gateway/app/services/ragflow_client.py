# app/services/ragflow_client.py
"""
RAGFlow 客户端服务
负责与 RAGFlow API 通信，支持流式输出
将 RAGFlow 返回格式转换为 Ollama 兼容格式
"""
import logging
import json
from typing import AsyncGenerator, Optional, Dict, Any
import httpx
from fastapi import HTTPException, status
from app.config import settings


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
        self.chat_endpoint = f"{self.base_url}/api/v1/agents/{self.chat_id}/completions"
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
        # 关键修复：RAGFlow 原生 API 使用 question 字段，不是 messages 数组
        return {
            "question": user_prompt,
            "stream": stream,
            "quote": True  # 启用引用返回
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
    
    def _is_no_documents_response(self, answer: str, reference: Optional[Dict]) -> bool:
        # Agent 模式下，如果没有 agent_0 输出，或者 answer 为空
        if not answer or answer.strip() == "":
            return True
        
        # 检查常见的无答案提示
        no_doc_keywords = [
            "根据已知信息无法回答",
            "我没有找到相关信息",
            "抱歉，我不知道",
            "无法从给定信息中找到答案",
            "no relevant information found",
            "don't have enough information",
            "I don't know",
            "I couldn't find",
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
        body = self._build_request_body(user_prompt, stream=True)
        headers = self._build_headers()
        
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                async with client.stream(
                    "POST",
                    self.chat_endpoint,  # 注意：现在是 agents/{id}/completions
                    json=body,
                    headers=headers
                ) as response:
                    response.raise_for_status()
                    
                    full_answer = ""
                    has_agent_output = False
                    
                    async for line in response.aiter_lines():
                        if not line or not line.startswith("data:"):
                            continue
                        
                        data_content = line[5:]  # 去掉 "data:"
                        
                        if data_content == "true":  # 结束标志
                            if not has_agent_output:
                                # 没有 agent 输出，触发降级
                                raise RagflowNoDocumentsException("RAGFlow Agent 未生成有效回答")
                            yield "data: [DONE]\n\n"
                            break
                        
                        try:
                            chunk_data = json.loads(data_content)
                        except json.JSONDecodeError:
                            continue
                        
                        # Agent 模式：解析 node_finished 事件
                        if chunk_data.get("event") == "node_finished":
                            node_data = chunk_data.get("data", {})
                            component_name = node_data.get("component_name", "")
                            outputs = node_data.get("outputs", {})
                            
                            # 从 agent_0 节点提取答案
                            if component_name == "Agent_0" and outputs:
                                logger.info(f"Agent outputs: {json.dumps(outputs, ensure_ascii=False)}")  # 加这行
                                has_agent_output = True
                                # 尝试从多个可能的字段提取答案
                                answer = (
                                    outputs.get("answer") or 
                                    outputs.get("content") or
                                    outputs.get("formalized_content") or
                                    str(outputs)  # 兜底：直接转字符串
                                )
                                
                                if answer:
                                    full_answer += answer
                                    
                                    transformed = {
                                        "response": answer,
                                        "done": False,
                                        "model": "ragflow",
                                        "ragflow_reference": outputs  # 保留完整输出
                                    }
                                    yield f"data: {json.dumps(transformed, ensure_ascii=False)}\n\n"
                    
                    # 流结束，发送最终标记
                    if has_agent_output:
                        yield f"data: {json.dumps({'response': '', 'done': True, 'model': 'ragflow'}, ensure_ascii=False)}\n\n"
                    else:
                        # 没有有效输出，触发降级
                        raise RagflowNoDocumentsException("RAGFlow Agent 未生成有效回答")
                        
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
                        detail="RAGFlow chat not found"
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