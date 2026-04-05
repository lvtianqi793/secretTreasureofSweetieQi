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
_ENV_FILE = _BASE_DIR / ".env.example"
load_dotenv(dotenv_path=_ENV_FILE if _ENV_FILE.exists() else None)


class Settings:
    """应用配置类"""
    
    def __init__(self):
        # 项目根目录（mvp-ollama-gateway/）
        self.BASE_DIR: Path = _BASE_DIR
        
        # Ollama 服务配置
        self.OLLAMA_URL: str = os.getenv("OLLAMA_URL", "http://localhost:11434")
        self.OLLAMA_MODEL: str = os.getenv("OLLAMA_MODEL", "llama2")
        
        # 系统 Prompt 文件路径
        # 默认指向项目根目录下的 prompts/system_prompt.txt
        default_prompt_path = self.BASE_DIR / "prompts" / "system_prompt.txt"
        
        # 从环境变量读取，如果设置了则转换为 Path，否则使用默认路径
        env_prompt_file = os.getenv("SYSTEM_PROMPT_FILE")
        if env_prompt_file:
            # 如果是相对路径，基于项目根目录解析
            path = Path(env_prompt_file)
            if not path.is_absolute():
                path = self.BASE_DIR / path
            self.SYSTEM_PROMPT_FILE: Path = path.resolve()
        else:
            self.SYSTEM_PROMPT_FILE: Path = default_prompt_path
        
        # API 服务配置
        self.API_HOST: str = os.getenv("API_HOST", "0.0.0.0")
        self.API_PORT: int = int(os.getenv("API_PORT", "8000"))
        
        # 可选：日志级别
        self.LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    
    def get_system_prompt(self) -> Optional[str]:
        """
        读取系统 prompt 文件内容
        
        Returns:
            系统 prompt 内容，如果文件不存在则返回 None
        """
        try:
            if not self.SYSTEM_PROMPT_FILE.exists():
                return None
            return self.SYSTEM_PROMPT_FILE.read_text(encoding="utf-8")
        except Exception as e:
            # 这里可以集成 logger，但为了保持简单先使用 print
            print(f"读取系统 prompt 文件失败: {e}")
            return None
    
    def validate(self) -> list[str]:
        """
        验证配置是否有效
        
        Returns:
            错误信息列表，空列表表示配置有效
        """
        errors = []
        
        # 检查系统 prompt 文件是否存在
        if not self.SYSTEM_PROMPT_FILE.exists():
            errors.append(
                f"系统 prompt 文件不存在: {self.SYSTEM_PROMPT_FILE}"
            )
        
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