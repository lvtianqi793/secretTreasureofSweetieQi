# app/config.py
"""
配置管理模块
负责加载环境变量和应用程序配置
"""

import os
from pathlib import Path
from functools import lru_cache
from typing import Optional

# 加载 .env 文件（在项目根目录）
from dotenv import load_dotenv

# 在类定义前加载环境变量
_BASE_DIR = Path(__file__).parent.parent
_ENV_FILE = _BASE_DIR / ".env"
load_dotenv(dotenv_path=_ENV_FILE if _ENV_FILE.exists() else None)


class Settings:
    """应用配置类"""
    
    def __init__(self):
        # 项目根目录（mvp-ollama-gateway/）
        self.BASE_DIR: Path = _BASE_DIR
        
        # Ollama 服务配置
        self.OLLAMA_URL: str = os.getenv("OLLAMA_URL", "http://localhost:11434")
        self.OLLAMA_MODEL: str = os.getenv("OLLAMA_MODEL", "qwen3.5:2b")
        
        # 系统 Prompt 文件路径 - 支持三种类型
        self.SYSTEM_CHAT_PROMPT_FILE: Path = self._resolve_prompt_path(
            "SYSTEM_CHAT_PROMPT_FILE", 
            "prompts/system_chat_prompt.txt"
        )
        self.SYSTEM_GENERATESQL_PROMPT_FILE: Path = self._resolve_prompt_path(
            "SYSTEM_GENERATESQL_PROMPT_FILE",
            "prompts/system_generatesql_prompt.txt"
        )
        self.SYSTEM_ANALYSE_PROMPT_FILE: Path = self._resolve_prompt_path(
            "SYSTEM_ANALYSE_PROMPT_FILE",
            "prompts/system_analyse_prompt.txt"
        )
        
        # 保留旧配置兼容（可选）
        default_prompt_path = self.BASE_DIR / "prompts" / "system_prompt.txt"
        env_prompt_file = os.getenv("SYSTEM_PROMPT_FILE")
        if env_prompt_file:
            path = Path(env_prompt_file)
            if not path.is_absolute():
                path = self.BASE_DIR / path
            self.SYSTEM_PROMPT_FILE: Path = path.resolve()
        else:
            self.SYSTEM_PROMPT_FILE: Path = default_prompt_path
        
        # RAGFlow 服务配置（analyse 路由专用）
        self.RAGFLOW_ENABLED: bool = os.getenv("RAGFLOW_ENABLED", "false").lower() == "true"
        self.RAGFLOW_BASE_URL: str = os.getenv("RAGFLOW_BASE_URL", "http://localhost:9380")
        self.RAGFLOW_API_KEY: str = os.getenv("RAGFLOW_API_KEY", "")
        self.RAGFLOW_CHAT_ID: str = os.getenv("RAGFLOW_CHAT_ID", "")

        # Spring Boot 后端地址 (MCP tools 通过 HTTP 回调)
        self.SPRING_BOOT_BASE_URL: str = os.getenv(
            "SPRING_BOOT_BASE_URL", "http://localhost:8080/api"
        )
        self.MCP_HTTP_TIMEOUT: float = float(os.getenv("MCP_HTTP_TIMEOUT", "30"))

        # API 服务配置
        self.API_HOST: str = os.getenv("API_HOST", "0.0.0.0")
        self.API_PORT: int = int(os.getenv("API_PORT", "8000"))

        # MCP 客户端连接的 SSE 端点
        # Agent 通过真实 MCP 协议 (SSE + JSON-RPC) 回调本进程挂载的 MCP Server
        default_mcp_sse = f"http://127.0.0.1:{self.API_PORT}/mcp/sse"
        self.MCP_SSE_URL: str = os.getenv("MCP_SSE_URL", default_mcp_sse)
        
        # 可选：日志级别
        self.LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    
    def _resolve_prompt_path(self, env_var: str, default_relative_path: str) -> Path:
        """
        解析 prompt 文件路径
        
        Args:
            env_var: 环境变量名
            default_relative_path: 默认相对路径（基于项目根目录）
        
        Returns:
            解析后的绝对路径
        """
        env_value = os.getenv(env_var)
        if env_value:
            path = Path(env_value)
            if not path.is_absolute():
                path = self.BASE_DIR / path
            return path.resolve()
        else:
            return (self.BASE_DIR / default_relative_path).resolve()
    
    def get_system_prompt(self) -> Optional[str]:
        """
        读取系统 prompt 文件内容（默认读取 chat）
        
        Returns:
            系统 prompt 内容，如果文件不存在则返回 None
        """
        return self._read_prompt_file(self.SYSTEM_CHAT_PROMPT_FILE)
    
    def get_chat_prompt(self) -> Optional[str]:
        """读取聊天系统 prompt"""
        return self._read_prompt_file(self.SYSTEM_CHAT_PROMPT_FILE)
    
    def get_generatesql_prompt(self) -> Optional[str]:
        """读取 SQL 生成系统 prompt"""
        return self._read_prompt_file(self.SYSTEM_GENERATESQL_PROMPT_FILE)
    
    def get_analyse_prompt(self) -> Optional[str]:
        """读取分析系统 prompt"""
        return self._read_prompt_file(self.SYSTEM_ANALYSE_PROMPT_FILE)
    
    def _read_prompt_file(self, file_path: Path) -> Optional[str]:
        """
        读取指定 prompt 文件
        
        Args:
            file_path: 文件路径
        
        Returns:
            文件内容，失败返回 None
        """
        try:
            if not file_path.exists():
                return None
            return file_path.read_text(encoding="utf-8")
        except Exception as e:
            print(f"读取 prompt 文件失败 {file_path}: {e}")
            return None
    
    def validate(self) -> list[str]:
        """
        验证配置是否有效
        
        Returns:
            错误信息列表，空列表表示配置有效
        """
        errors = []
        
        # 检查三种系统 prompt 文件
        prompt_files = [
            ("chat", self.SYSTEM_CHAT_PROMPT_FILE),
            ("generatesql", self.SYSTEM_GENERATESQL_PROMPT_FILE),
            ("analyse", self.SYSTEM_ANALYSE_PROMPT_FILE),
        ]
        for name, path in prompt_files:
            if not path.exists():
                errors.append(f"{name} prompt 文件不存在: {path}")
        
        # 如果启用了 RAGFlow，检查必要配置
        if self.RAGFLOW_ENABLED:
            if not self.RAGFLOW_API_KEY:
                errors.append("RAGFLOW_ENABLED=true 但 RAGFLOW_API_KEY 未设置")
            if not self.RAGFLOW_CHAT_ID:
                errors.append("RAGFLOW_ENABLED=true 但 RAGFLOW_CHAT_ID 未设置")
                
        # 检查 Ollama URL 是否设置
        if not self.OLLAMA_URL:
            errors.append("OLLAMA_URL 未设置")
        
        return errors


@lru_cache()
def get_settings() -> Settings:
    """
    获取配置单例（使用 lru_cache 确保全局只有一个实例）
    
    Returns:
        Settings 实例
    """
    return Settings()


# 便捷导出，供其他模块直接使用
settings = get_settings()