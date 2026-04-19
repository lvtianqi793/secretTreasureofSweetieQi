# app/services/ollama_client.py
"""
Ollama 客户端服务
负责与 Ollama API 通信，支持流式输出
"""

import json
from typing import AsyncGenerator, Optional, Dict, Any
import httpx
from app.config import settings
from app.models.request import OllamaRequest
from app.services.prompt_loader import get_prompt_loader


class OllamaClient:
    """
    Ollama HTTP 客户端
    封装与 Ollama /api/generate 端点的通信
    """
    
    def __init__(self):
        self.base_url = settings.OLLAMA_URL.rstrip("/")
        self.default_model = settings.OLLAMA_MODEL
        self.generate_endpoint = f"{self.base_url}/api/generate"
        self.timeout = httpx.Timeout(60.0, connect=10.0)
    
    def _get_system_prompt_by_type(self, prompt_type: Optional[str] = None) -> Optional[str]:
        """
        根据类型获取系统 prompt
        
        Args:
            prompt_type: chat/generatesql/analyse，None 则使用默认 chat
            
        Returns:
            系统 prompt 内容或 None
        """
        if prompt_type is None:
            prompt_type = "chat"
        
        loader = get_prompt_loader(prompt_type)
        return loader.get_prompt()
    
    def _build_request_body(
        self,
        user_prompt: str,
        model: Optional[str] = None,
        system_prompt: Optional[str] = None,
        prompt_type: Optional[str] = None,
        stream: bool = True,
        options: Optional[Dict[str, Any]] = None,
        think: bool = False
    ) -> Dict[str, Any]:
        """
        构建 Ollama 请求体
        
        Args:
            user_prompt: 用户输入的提示词
            model: 模型名称，默认使用配置中的模型
            system_prompt: 系统提示词，优先级高于 prompt_type
            prompt_type: 系统提示词类型 (chat/generatesql/analyse)
            stream: 是否流式输出
            options: 额外的模型参数
            think: 是否启用思考过程
            
        Returns:
            Ollama 请求体字典
        """
        # 获取系统提示词（优先级：传入 > 按类型加载 > None）
        if system_prompt is None and prompt_type is not None:
            system_prompt = self._get_system_prompt_by_type(prompt_type)
        elif system_prompt is None:
            system_prompt = self._get_system_prompt_by_type("chat")
        
        request = OllamaRequest(
            model=model or self.default_model,
            prompt=user_prompt,
            system=system_prompt,
            stream=stream,
            options=options,
            think=think
        )
        
        return request.to_json()
    
    async def generate_stream(
        self,
        user_prompt: str,
        model: Optional[str] = None,
        system_prompt: Optional[str] = None,
        prompt_type: Optional[str] = None,
        options: Optional[Dict[str, Any]] = None,
        think: bool = False
    ) -> AsyncGenerator[str, None]:
        """
        流式生成文本，产生 SSE 格式的数据块
        
        Args:
            user_prompt: 用户输入的提示词
            model: 模型名称
            system_prompt: 系统提示词，优先级高于 prompt_type
            prompt_type: 系统提示词类型 (chat/generatesql/analyse)
            options: 额外的模型参数
            think: 是否启用思考过程
            
        Yields:
            SSE 格式的字符串，如 "data: {...}\n\n"
            
        Raises:
            httpx.HTTPError: HTTP 请求失败
            json.JSONDecodeError: 响应解析失败
        """
        body = self._build_request_body(
            user_prompt=user_prompt,
            model=model,
            system_prompt=system_prompt,
            prompt_type=prompt_type,
            stream=True,
            options=options,
            think=think
        )
        
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            async with client.stream(
                "POST",
                self.generate_endpoint,
                json=body,
                headers={"Content-Type": "application/json"}
            ) as response:
                response.raise_for_status()
                
                async for line in response.aiter_lines():
                    if not line:
                        continue
                    
                    try:
                        # 解析 Ollama 的 JSON 响应行
                        data = json.loads(line)
                        
                        # 转换为 SSE 格式
                        sse_chunk = f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
                        yield sse_chunk
                        
                        # 如果生成完成，可以选择是否继续
                        if data.get("done", False):
                            # 发送结束标记（可选，根据前端需求）
                            yield "data: [DONE]\n\n"
                            break
                            
                    except json.JSONDecodeError as e:
                        # 记录解析错误但继续处理
                        error_msg = f"解析响应失败: {line[:100]}"
                        yield f"data: {json.dumps({'error': error_msg})}\n\n"
    
    async def generate(
        self,
        user_prompt: str,
        model: Optional[str] = None,
        system_prompt: Optional[str] = None,
        prompt_type: Optional[str] = None,
        options: Optional[Dict[str, Any]] = None,
        think: bool = False
    ) -> Dict[str, Any]:
        """
        非流式生成，等待完整响应后返回
        
        Args:
            user_prompt: 用户输入的提示词
            model: 模型名称
            system_prompt: 系统提示词，优先级高于 prompt_type
            prompt_type: 系统提示词类型 (chat/generatesql/analyse)
            options: 额外的模型参数
            think: 是否启用思考过程
            
        Returns:
            完整的 Ollama 响应字典
        """
        body = self._build_request_body(
            user_prompt=user_prompt,
            model=model,
            system_prompt=system_prompt,
            prompt_type=prompt_type,
            stream=False,
            options=options,
            think=think
        )
        
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            response = await client.post(
                self.generate_endpoint,
                json=body,
                headers={"Content-Type": "application/json"}
            )
            response.raise_for_status()
            return response.json()
    
    async def check_connection(self) -> bool:
        """
        检查 Ollama 服务是否可连接
        
        Returns:
            是否连接成功
        """
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(5.0)) as client:
                # Ollama 的 tags 端点用于列出模型，轻量级检查
                response = await client.get(f"{self.base_url}/api/tags")
                return response.status_code == 200
        except Exception:
            return False


# 全局客户端实例
_ollama_client: Optional[OllamaClient] = None


def get_ollama_client() -> OllamaClient:
    """
    获取 OllamaClient 单例
    
    Returns:
        OllamaClient 实例
    """
    global _ollama_client
    if _ollama_client is None:
        _ollama_client = OllamaClient()
    return _ollama_client


# 便捷函数
async def generate_stream(
    user_prompt: str,
    model: Optional[str] = None,
    prompt_type: Optional[str] = None
) -> AsyncGenerator[str, None]:
    """
    快速流式生成函数
    
    Args:
        user_prompt: 用户输入的提示词
        model: 模型名称
        prompt_type: 系统提示词类型 (chat/generatesql/analyse)
        
    Yields:
        SSE 格式的数据块
    """
    client = get_ollama_client()
    async for chunk in client.generate_stream(
        user_prompt=user_prompt,
        model=model,
        prompt_type=prompt_type
    ):
        yield chunk