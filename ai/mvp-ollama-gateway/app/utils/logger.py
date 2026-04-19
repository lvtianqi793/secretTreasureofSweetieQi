import logging
import sys
from logging.handlers import RotatingFileHandler
import os


def setup_logger(name: str = "ai-gateway") -> logging.Logger:
    """
    设置生产环境的日志配置
    """
    logger = logging.getLogger(name)
    
    # 避免重复添加handler
    if logger.handlers:
        return logger
    
    # 设置日志级别
    log_level = os.getenv("LOG_LEVEL", "INFO").upper()
    logger.setLevel(getattr(logging, log_level, logging.INFO))
    
    # 日志格式
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(filename)s:%(lineno)d - %(message)s'
    )
    
    # 控制台输出
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)
    
    # 文件输出（生产环境推荐）
    log_dir = "/var/log/ai-gateway"
    if os.path.exists(log_dir):
        file_handler = RotatingFileHandler(
            filename=os.path.join(log_dir, "ai-gateway.log"),
            maxBytes=10 * 1024 * 1024,  # 10MB
            backupCount=5
        )
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    
    # 避免日志传播到根logger
    logger.propagate = False
    
    return logger


# 创建默认logger实例
default_logger = setup_logger()