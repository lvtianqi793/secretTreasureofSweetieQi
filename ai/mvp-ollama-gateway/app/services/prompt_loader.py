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
    
    def __init__(self, file_path: Optional[Path] = None):
        """
        初始化 Prompt 加载器
        
        Args:
            file_path: Prompt 文件路径，默认从配置读取
        """
        # 延迟导入避免循环依赖
        from app.config import settings
        
        self.file_path: Path = file_path or settings.SYSTEM_PROMPT_FILE
        self._cache: Optional[PromptMetadata] = None
        self._encoding: str = "utf-8"
    
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


# 全局单例实例
_prompt_loader: Optional[PromptLoader] = None


def get_prompt_loader(file_path: Optional[Path] = None) -> PromptLoader:
    """
    获取 PromptLoader 单例
    
    Args:
        file_path: 可选，指定自定义文件路径
        
    Returns:
        PromptLoader 实例
    """
    global _prompt_loader
    
    if _prompt_loader is None or file_path is not None:
        _prompt_loader = PromptLoader(file_path)
    
    return _prompt_loader


def get_system_prompt() -> Optional[str]:
    """
    便捷函数：快速获取系统 Prompt
    
    Returns:
        系统 Prompt 内容或 None
    """
    loader = get_prompt_loader()
    return loader.get_prompt()