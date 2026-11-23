#!/usr/bin/env bash
# =============================================================================
# aifaizhi-data-sync —— docker run 一键部署脚本
# 用法：
#   ./deploy/docker-run.sh                  # 构建（若镜像不存在）并后台启动常驻服务
#   ./deploy/docker-run.sh --build          # 强制重新构建镜像后启动
#   ./deploy/docker-run.sh --sync           # 一次性跑同步任务后退出（不设重启策略）
#   ./deploy/docker-run.sh --sync=2086724333076062209   # 只同步指定 relevant_id
#   ./deploy/docker-run.sh --build --sync   # 组合使用
# 查看日志：docker logs -f aifaizhi-data-sync
# 停止：    docker stop aifaizhi-data-sync && docker rm aifaizhi-data-sync
# =============================================================================
set -euo pipefail

# ---------- 可通过环境变量覆盖的默认值 ----------
IMAGE="${IMAGE:-aifaizhi-data-sync:0.0.1-SNAPSHOT}"
NAME="${NAME:-aifaizhi-data-sync}"
PORT="${PORT:-13607}"
LOG_DIR="${LOG_DIR:-$(pwd)/logs}"
ENV_FILE="${ENV_FILE:-./deploy/docker-run.env}"
CONFIG_DIR="${CONFIG_DIR:-./config}"   # 存在则挂载为外部配置目录

BUILD=0
EXTRA_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    *)       EXTRA_ARGS+=("$arg") ;;
  esac
done

# 检测是否为一次性任务（含 --sync），此时不应设置重启策略，否则会循环重启
ONE_SHOT=0
for arg in "${EXTRA_ARGS[@]}"; do
  if [[ "$arg" == --sync* ]]; then ONE_SHOT=1; fi
done

# ---------- 构建镜像 ----------
if [[ $BUILD -eq 1 ]] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "==> 构建镜像 $IMAGE ..."
  docker build -t "$IMAGE" .
fi

# ---------- 清理同名旧容器 ----------
if docker inspect "$NAME" >/dev/null 2>&1; then
  echo "==> 移除已存在的容器 $NAME ..."
  docker rm -f "$NAME" >/dev/null
fi

mkdir -p "$LOG_DIR"

RUN_ARGS=(
  -d
  --name "$NAME"
  -p "${PORT}:13607"
  -v "${LOG_DIR}:/app/logs"
  -e TZ=Asia/Shanghai
)
if [[ -f "$ENV_FILE" ]]; then
  echo "==> 加载环境变量文件: $ENV_FILE"
  RUN_ARGS+=(--env-file "$ENV_FILE")
fi
if [[ -d "$CONFIG_DIR" ]]; then
  RUN_ARGS+=(-v "$(cd "$CONFIG_DIR" && pwd):/app/config:ro")
fi
if [[ $ONE_SHOT -eq 1 ]]; then
  RUN_ARGS+=(--restart no)
else
  RUN_ARGS+=(--restart unless-stopped)
fi

echo "==> 启动容器 $NAME （镜像 $IMAGE，参数: ${EXTRA_ARGS[*]:-<常驻服务>}）"
docker run "${RUN_ARGS[@]}" "$IMAGE" "${EXTRA_ARGS[@]}"

echo
echo "==> 启动完成。常用命令："
echo "    docker logs -f $NAME          # 查看实时日志"
echo "    docker ps --filter name=$NAME # 查看状态"
echo "    curl http://127.0.0.1:${PORT}/actuator/health/readiness  # 健康检查"
