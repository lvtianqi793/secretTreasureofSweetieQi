# app/models/request.py
"""
数据模型定义
使用 Pydantic 进行请求验证和响应序列化
"""

from typing import Optional, List, Dict, Any, Literal
from pydantic import BaseModel, Field, validator


# ==================== 请求模型 ====================

class ChatRequest(BaseModel):
    """
    聊天请求模型
    SpringBoot 发送的请求体
    """
    prompt: str = Field(
        ...,
        min_length=1,
        description="用户输入的提示词",
        example="你好，请介绍一下自己"
    )
    
    model: Optional[str] = Field(
        default=None,
        description="可选，指定使用的模型，默认使用配置中的模型",
        example="llama2"
    )
    
    stream: bool = Field(
        default=True,
        description="是否使用流式输出",
        example=True
    )
    
    temperature: Optional[float] = Field(
        default=None,
        ge=0.0,
        le=2.0,
        description="采样温度，控制随机性，范围 0-2",
        example=0.7
    )

    messages: Optional[List[Dict[str, Any]]] = Field(
        default=None,
        description="多轮对话历史（不含当前 prompt），每项 {role, content}，role 为 user/assistant/system",
        example=[{"role": "user", "content": "查询本月能耗"}, {"role": "assistant", "content": "本月总能耗 1234 kWh"}]
    )

    class Config:
        json_schema_extra = {
            "example": {
                "prompt": "你好，请介绍一下自己",
                "model": "qwen3.5:2b",
                "stream": True,
                "temperature": 0.7,
                "messages": []
            }
        }


class GenerateRequest(BaseModel):
    """
    生成请求模型（兼容 Ollama Generate API 格式）
    如需直接透传 Ollama 参数可使用此模型
    """
    model: str = Field(
        ...,
        description="模型名称",
        example="llama2"
    )
    
    prompt: str = Field(
        ...,
        min_length=1,
        description="生成提示词",
        example="你好"
    )
    
    system: Optional[str] = Field(
        default=None,
        description="系统提示词，如不传则使用文件中的系统提示词",
        example="你是一个有用的助手"
    )
    
    stream: bool = Field(
        default=True,
        description="是否流式输出"
    )
    
    options: Optional[Dict[str, Any]] = Field(
        default=None,
        description="额外的模型参数",
        example={"temperature": 0.7, "num_predict": 100}
    )


# ==================== 响应模型 ====================

class ChatResponse(BaseModel):
    """
    非流式聊天响应模型
    """
    response: str = Field(
        ...,
        description="生成的回复内容"
    )
    
    model: str = Field(
        ...,
        description="使用的模型名称"
    )
    
    done: bool = Field(
        default=True,
        description="是否完成生成"
    )
    
    total_duration: Optional[int] = Field(
        default=None,
        description="总耗时（纳秒）"
    )


class StreamChunk(BaseModel):
    """
    流式响应块
    每个 SSE 事件的数据结构
    """
    response: str = Field(
        ...,
        description="当前块生成的文本"
    )
    
    done: bool = Field(
        default=False,
        description="是否为最后一块"
    )
    
    model: Optional[str] = Field(
        default=None,
        description="模型名称（通常在第一个块返回）"
    )
    
    class Config:
        # 允许额外字段，兼容 Ollama 的其他返回字段
        extra = "allow"


class ErrorResponse(BaseModel):
    """
    错误响应模型
    """
    error: str = Field(
        ...,
        description="错误信息"
    )
    
    detail: Optional[str] = Field(
        default=None,
        description="详细错误信息"
    )
    
    code: Optional[str] = Field(
        default=None,
        description="错误代码"
    )


class HealthResponse(BaseModel):
    """
    健康检查响应
    """
    status: Literal["ok", "error"] = Field(
        ...,
        description="服务状态"
    )
    
    ollama_connected: bool = Field(
        ...,
        description="Ollama 服务是否可连接"
    )
    
    system_prompt_loaded: bool = Field(
        ...,
        description="系统提示词是否已加载"
    )
    
    version: str = Field(
        default="1.0.0",
        description="API 版本"
    )


# ==================== Ollama 内部模型 ====================

class OllamaRequest(BaseModel):
    """
    内部使用的 Ollama 请求体
    将我们的请求转换为 Ollama 格式
    """
    model: str
    prompt: str
    system: Optional[str] = None
    stream: bool = True
    options: Optional[Dict[str, Any]] = None
    think: bool = False
    
    def to_json(self) -> Dict[str, Any]:
        """转换为 JSON 字典，过滤 None 值"""
        return self.model_dump(exclude_none=True)


# ==================== 便捷类型别名 ====================

# 流式响应生成器类型
StreamGenerator = Any  # 实际是 AsyncGenerator[str, None]，但避免复杂导入