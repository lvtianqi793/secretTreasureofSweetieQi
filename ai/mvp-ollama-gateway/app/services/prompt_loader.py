# app/services/prompt_loader.py
"""
Prompt 加载服务
负责从文件系统读取和管理系统 Prompt
"""

import os
from pathlib import Path
from typing import Optional, Dict, Union, Any
from dataclasses import dataclass


@dataclass
class PromptMetadata:
    """Prompt 文件元数据"""
    file_path: Path
    last_modified: float
    content: str


class PromptLoader:
    """
    Prompt 加载器
    支持热重载（文件修改后自动重新加载）
    """
    
    def __init__(self, file_path: Optional[Path] = None, prompt_type: Optional[str] = None):
        """
        初始化 Prompt 加载器
        
        Args:
            file_path: Prompt 文件路径，优先使用
            prompt_type: prompt 类型 (chat/generatesql/analyse)，从配置读取对应路径
        """
        # 延迟导入避免循环依赖
        from app.config import settings
        
        # 根据类型或路径确定文件位置
        if file_path is not None:
            self.file_path: Path = file_path
        elif prompt_type is not None:
            self.file_path: Path = self._get_path_by_type(prompt_type, settings)
        else:
            # 默认使用 chat 类型保持兼容
            self.file_path: Path = settings.SYSTEM_CHAT_PROMPT_FILE
        
        self._cache: Optional[PromptMetadata] = None
        self._encoding: str = "utf-8"
    
    def _get_path_by_type(self, prompt_type: str, settings) -> Path:
        """
        根据类型获取对应文件路径
        
        Args:
            prompt_type: chat/generatesql/analyse
            settings: 配置对象
        
        Returns:
            对应的文件路径
        """
        type_map = {
            "chat": settings.SYSTEM_CHAT_PROMPT_FILE,
            "generatesql": settings.SYSTEM_GENERATESQL_PROMPT_FILE,
            "analyse": settings.SYSTEM_ANALYSE_PROMPT_FILE,
        }
        
        if prompt_type not in type_map:
            raise ValueError(f"未知的 prompt 类型: {prompt_type}，可选: {list(type_map.keys())}")
        
        return type_map[prompt_type]
    
    def _read_file(self) -> str:
        """
        读取文件内容
        
        Returns:
            文件内容字符串
            
        Raises:
            FileNotFoundError: 文件不存在
            PermissionError: 无权限读取文件
            UnicodeDecodeError: 文件编码错误
        """
        with open(self.file_path, "r", encoding=self._encoding) as f:
            return f.read()
    
    def _get_modified_time(self) -> float:
        """获取文件最后修改时间"""
        return os.path.getmtime(self.file_path)
    
    def _is_cache_valid(self) -> bool:
        """检查缓存是否有效（文件未修改）"""
        if self._cache is None:
            return False
        
        try:
            current_mtime = self._get_modified_time()
            return current_mtime == self._cache.last_modified
        except OSError:
            return False
    
    def load(self, force_reload: bool = False) -> str:
        """
        加载系统 Prompt，支持缓存和热重载
        
        Args:
            force_reload: 强制重新加载，忽略缓存
            
        Returns:
            系统 Prompt 内容
            
        Raises:
            FileNotFoundError: 文件不存在时抛出
        """
        # 检查缓存是否有效
        if not force_reload and self._is_cache_valid():
            return self._cache.content
        
        # 读取文件
        content = self._read_file()
        modified_time = self._get_modified_time()
        
        # 更新缓存
        self._cache = PromptMetadata(
            file_path=self.file_path,
            last_modified=modified_time,
            content=content
        )
        
        return content
    
    def get_prompt(self) -> Optional[str]:
        """
        安全地获取 Prompt，出错时返回 None 而不是抛出异常
        
        Returns:
            Prompt 内容或 None
        """
        try:
            return self.load()
        except Exception as e:
            # 可以在这里集成日志
            print(f"[PromptLoader] 加载失败: {e}")
            return None
    
    def reload(self) -> str:
        """强制重新加载 Prompt"""
        return self.load(force_reload=True)
    
    def get_file_info(self) -> Dict[str, Any]:
        """
        获取文件信息（用于调试和监控）
        
        Returns:
            包含文件信息的字典
        """
        info: Dict[str, Any] = {
            "file_path": str(self.file_path),
            "exists": self.file_path.exists(),
            "is_file": self.file_path.is_file() if self.file_path.exists() else False,
            "last_modified": None,
            "size_bytes": None,
            "cached": self._cache is not None,
        }
        
        if self.file_path.exists():
            try:
                stat = self.file_path.stat()
                info["last_modified"] = stat.st_mtime
                info["size_bytes"] = stat.st_size
            except OSError:
                pass
        
        return info


# 全局缓存，按类型存储
_loaders: Dict[str, PromptLoader] = {}


def get_prompt_loader(prompt_type: Optional[str] = None, file_path: Optional[Path] = None) -> PromptLoader:
    """
    获取 PromptLoader 单例（按类型缓存）
    
    Args:
        prompt_type: 可选，prompt 类型 (chat/generatesql/analyse)
        file_path: 可选，指定自定义文件路径（优先于 type）
        
    Returns:
        PromptLoader 实例
    """
    global _loaders
    
    # 用 file_path 或 type 作为缓存 key
    cache_key = str(file_path) if file_path is not None else (prompt_type or "default")
    
    if cache_key not in _loaders:
        _loaders[cache_key] = PromptLoader(
            file_path=file_path,
            prompt_type=prompt_type
        )
    
    return _loaders[cache_key]


def get_system_prompt() -> Optional[str]:
    """
    便捷函数：快速获取系统 Prompt（默认 chat 类型）
    
    Returns:
        系统 Prompt 内容或 None
    """
    loader = get_prompt_loader("chat")
    return loader.get_prompt()


# 新增便捷函数
def get_chat_prompt() -> Optional[str]:
    """获取 chat 系统 prompt"""
    return get_prompt_loader("chat").get_prompt()


def get_generatesql_prompt() -> Optional[str]:
    """获取 generatesql 系统 prompt"""
    return get_prompt_loader("generatesql").get_prompt()


def get_analyse_prompt() -> Optional[str]:
    """获取 analyse 系统 prompt"""
    return get_prompt_loader("analyse").get_prompt()