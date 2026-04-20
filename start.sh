#!/bin/bash

# 一键启动脚本 - 能源管理系统
# 支持: start | stop | restart | status

RAGFLOW_DIR="${HOME}/myRagflow/ragflow/docker"  # 根据实际路径调整

set -e

# 脚本名称
SCRIPT_NAME=$(basename "$0")

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 日志函数
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 显示帮助
show_help() {
    cat << EOF
能源管理系统管理脚本

用法: ./$SCRIPT_NAME [命令]

命令:
    start    启动所有服务（默认）
    stop     停止所有服务
    restart  重启所有服务
    status   查看服务状态
    help     显示此帮助信息

示例:
    ./$SCRIPT_NAME start    # 启动服务
    ./$SCRIPT_NAME stop     # 停止服务
    ./$SCRIPT_NAME restart  # 重启服务
    ./$SCRIPT_NAME status   # 查看状态
EOF
}

# 检查 Docker 权限
check_docker() {
    log_info "检查 Docker 权限..."
    
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装"
        exit 1
    fi
    
    if ! command -v docker compose &> /dev/null; then
        log_error "Docker Compose 未安装"
        exit 1
    fi
    
    if ! docker ps > /dev/null 2>&1; then
        log_error "无法执行 Docker 命令，请检查："
        log_error "  1. Docker 服务是否运行"
        log_error "  2. 当前用户是否在 docker 组"
        log_error "  3. 或使用 sudo 运行此脚本"
        exit 1
    fi
    
    log_success "Docker 权限检查通过"
}

# 检查 ollama
check_ollama() {
    log_info "检查 ollama 服务..."
    
    if ! command -v ollama &> /dev/null; then
        log_error "ollama 未安装"
        exit 1
    fi
    
    if ! pgrep -x "ollama" > /dev/null; then
        log_warning "ollama 未启动，正在启动..."
        ollama serve > /tmp/ollama.log 2>&1 &
        OLLAMA_PID=$!
        
        local attempt=1
        while [ $attempt -le 30 ]; do
            if curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
                log_success "ollama 已启动 (PID: $OLLAMA_PID)"
                echo $OLLAMA_PID > /tmp/ollama.pid
                return 0
            fi
            sleep 2
            attempt=$((attempt + 1))
        done
        
        log_error "ollama 启动超时"
        exit 1
    else
        log_success "ollama 已在运行"
    fi
    
    # 检查模型
    if ! ollama list | grep -q "qwen3.5:9b"; then
        log_warning "qwen3.5:9b 模型未找到，请运行: ollama pull qwen3.5:9b"
        exit 1
    fi
    if ! ollama list | grep -q "mxbai-embed-large:latest"; then
        log_warning "mxbai-embed-large:latest 模型未找到，请运行: ollama pull mxbai-embed-large:latest"
        exit 1
    fi
}

# 启动 Ragflow
start_ragflow() {
    log_info "检查 Ragflow 状态..."
    if [ ! -d "$RAGFLOW_DIR" ]; then
        log_error "Ragflow 目录不存在: $RAGFLOW_DIR"
        exit 1
    fi
    
    # 检查 Ragflow 是否已经在运行
    if curl -s -f http://localhost:9380/api/v1/system/healthz > /dev/null 2>&1; then
        log_success "Ragflow 已在运行"
        return 0
    fi
    
    log_info "启动 Ragflow..."
    
    # 使用子 shell 执行，避免影响父 shell 的工作目录
    (
        cd "$RAGFLOW_DIR"
        docker compose up -d
    )
    
    local attempt=1
    while [ $attempt -le 30 ]; do
        if curl -s -f http://localhost:9380/api/v1/system/healthz > /dev/null 2>&1; then
            log_success "Ragflow 启动成功"
            return 0
        fi
        sleep 10
        attempt=$((attempt + 1))
    done
    
    log_error "Ragflow 启动超时"
    exit 1
}

# 启动项目服务
start_project() {
    log_info "启动能源管理系统..."
    cd "$(dirname "$0")"
    
    docker compose up --build -d
    
    # 等待 AI 网关
    attempt=1
    while [ $attempt -le 30 ]; do
        if curl -s -f http://localhost:8000/health > /dev/null 2>&1; then
            log_success "AI 网关就绪"
            break
        fi
        sleep 5
        attempt=$((attempt + 1))
    done
    
    # 等待后端
    attempt=1
    while [ $attempt -le 30 ]; do
        if curl -s -f http://localhost:8080/system/health > /dev/null 2>&1; then
            log_success "后端服务就绪"
            break
        fi
        sleep 5
        attempt=$((attempt + 1))
    done
    
    echo ""
    log_success "所有服务启动完成！"
    echo "后端 API:     http://localhost:8080"
    echo "AI 网关:      http://localhost:8000"
    echo "AI 网关文档:  http://localhost:8000/docs"
    echo "Ragflow:      http://localhost:9380"
}

# 启动所有
cmd_start() {
    echo "=== 启动能源管理系统 ==="
    check_docker
    check_ollama
    start_ragflow
    start_project
}

# 停止所有
cmd_stop() {
    echo "=== 停止能源管理系统 ==="
    
    # 停止项目容器
    cd "$(dirname "$0")"
    log_info "停止项目服务..."
    docker compose down 2>/dev/null || true
    
    # 停止 Ragflow
    if [ -d "$RAGFLOW_DIR" ]; then
        log_info "停止 Ragflow..."
        # 使用子 shell 执行，避免影响父 shell 的工作目录
        (
            cd "$RAGFLOW_DIR"
            docker compose down 2>/dev/null || true
        )
    fi
    
    # 停止 ollama（如果是脚本启动的）
    if [ -f /tmp/ollama.pid ]; then
        local pid=$(cat /tmp/ollama.pid)
        if kill -0 $pid 2>/dev/null; then
            log_info "停止 ollama (PID: $pid)..."
            kill $pid
            rm -f /tmp/ollama.pid
        fi
    fi
    
    log_success "所有服务已停止"
}

# 重启
cmd_restart() {
    echo "=== 重启能源管理系统 ==="
    cmd_stop
    echo ""
    cmd_start
}

# 查看状态
cmd_status() {
    echo "=== 服务状态 ==="
    echo ""
    
    # 检查 ollama
    if pgrep -x "ollama" > /dev/null; then
        echo -e "ollama:      ${GREEN}运行中${NC}"
    else
        echo -e "ollama:      ${RED}未运行${NC}"
    fi
    
    # 检查容器
    cd "$(dirname "$0")" 2>/dev/null || true
    
    echo ""
    log_info "项目容器状态:"
    docker compose ps 2>/dev/null || echo "未运行"
    
    echo ""
    if [ -d "$RAGFLOW_DIR" ]; then
        log_info "Ragflow 容器状态:"
        # 使用子 shell 执行，避免影响父 shell 的工作目录
        (
            cd "$RAGFLOW_DIR"
            docker compose ps 2>/dev/null || echo "未运行"
        )
    fi
    
    echo ""
    log_info "端口检查:"
    local ports=("8080" "8000" "9380" "5432")
    local names=("后端" "AI网关" "Ragflow" "数据库")
    
    for i in "${!ports[@]}"; do
        if nc -z localhost ${ports[$i]} 2>/dev/null; then
            echo -e "${names[$i]} (${ports[$i]}): ${GREEN}可连接${NC}"
        else
            echo -e "${names[$i]} (${ports[$i]}): ${RED}未连接${NC}"
        fi
    done
}

# 清理函数
cleanup() {
    if [ -f /tmp/ollama.pid ]; then
        local pid=$(cat /tmp/ollama.pid)
        kill $pid 2>/dev/null || true
        rm -f /tmp/ollama.pid
    fi
}
trap cleanup EXIT

# 主入口
main() {
    local cmd="${1:-start}"
    
    case "$cmd" in
        start)    cmd_start ;;
        stop)     cmd_stop ;;
        restart)  cmd_restart ;;
        status)   cmd_status ;;
        help|-h|--help) show_help ;;
        *)
            log_error "未知命令: $cmd"
            show_help
            exit 1
            ;;
    esac
}

main "$@"