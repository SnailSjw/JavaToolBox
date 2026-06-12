#!/usr/bin/env bash
set -e

# ── 配置 ──
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOL_DIR="${SCRIPT_DIR}/tools"
BUILD="${TOOL_DIR}/.build"
WEB_DIR="${SCRIPT_DIR}/web"
PORT=8080

# ── 检查 JDK ──
if ! command -v javac &> /dev/null; then
    echo ""
    echo "  [错误] 未找到 javac，请确认已安装 JDK 并配置 PATH"
    echo ""
    exit 1
fi

# ── 目录 ──
mkdir -p "${TOOL_DIR}"
mkdir -p "${BUILD}"
mkdir -p "${WEB_DIR}"

if [ ! -f "${WEB_DIR}/index.html" ]; then
    echo ""
    echo "  [错误] web/index.html 不存在"
    exit 1
fi

echo ""
echo "  ◆ JavaToolBox"
echo "  ├─ 编译中..."
echo ""

# ── 编译 ──
if ! javac -encoding UTF-8 -d "${BUILD}" "${SCRIPT_DIR}/JavaToolBox.java"; then
    echo ""
    echo "  [错误] 编译失败，请检查代码"
    echo ""
    exit 1
fi

echo "  ├─ 编译成功"
echo "  ├─ 工具目录: ${TOOL_DIR}"
echo "  ├─ 端口: ${PORT}"
echo "  └─ 启动中..."
echo ""

# ── 启动 ──
java -Dfile.encoding=UTF-8 -cp "${BUILD}" JavaToolBox "${TOOL_DIR}" "${PORT}"

# ── 异常退出 ──
echo ""
echo "  已停止运行"
echo ""
