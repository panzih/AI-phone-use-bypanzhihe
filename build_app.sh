#!/bin/bash
# build_app.sh — 一键把项目打包成 macOS 应用（.app）
#
# 用法：
#     bash build_app.sh
#
# 做完之后：
#     dist/AI手机助手.app       ← 双击就能用
#
# 关于虚拟环境：macOS 自带的 /usr/bin/python3 属于系统组件，不允许往里装
# 第三方包（会报 "Operation not permitted: ~/Library/Python"）。
# 所以在项目目录里建 .venv，想删直接 rm -rf .venv，不污染系统。

set -e

cd "$(dirname "$0")"
PROJECT_DIR="$(pwd)"

echo "=============================================================="
echo "  打包 macOS 应用"
echo "=============================================================="
echo

# ---- 1. 找 Python ----
PY=""
for cand in python3.12 python3.11 python3.10 python3; do
    if command -v "$cand" >/dev/null 2>&1; then
        PY="$(command -v "$cand")"
        break
    fi
done
if [ -z "$PY" ]; then
    echo "[错误] 找不到 Python 3。请到 https://www.python.org/downloads/ 安装。"
    exit 1
fi
echo "基础 Python：$PY"
"$PY" --version
echo

# ---- 2. 虚拟环境 ----
VENV="$PROJECT_DIR/.venv"
if [ ! -x "$VENV/bin/python" ]; then
    echo "创建虚拟环境 .venv ..."
    # --system-site-packages 让 venv 能用到系统自带的 tkinter，
    # 那是图形界面的基础，venv 默认会隔离掉它。
    "$PY" -m venv --system-site-packages "$VENV" || {
        echo "[错误] 创建虚拟环境失败。试试：$PY -m pip install virtualenv"; exit 1; }
    echo "  完成：$VENV"
else
    echo "复用已有的虚拟环境：$VENV"
fi
echo

VPY="$VENV/bin/python"

if ! "$VPY" -c "import tkinter" >/dev/null 2>&1; then
    echo "[错误] 这个 Python 里没有可用的 tkinter，无法打包图形界面。"
    exit 1
fi
echo "tkinter 可用（Tk $("$VPY" -c 'import tkinter;print(tkinter.TkVersion)' 2>/dev/null)）"
echo

# ---- 3. 依赖 ----
echo "安装 PyInstaller（必需）..."
if ! "$VPY" -m pip install --upgrade pyinstaller --quiet; then
    echo "[错误] PyInstaller 安装失败。换国内镜像重试："
    echo "  $VPY -m pip install pyinstaller -i https://pypi.tuna.tsinghua.edu.cn/simple"
    exit 1
fi
echo "  完成"

echo "安装 Pillow（可选，用于网格标尺和本地压缩）..."
if "$VPY" -m pip install --upgrade Pillow --quiet 2>/dev/null; then
    echo "  完成"
else
    echo "  跳过（不影响打包）"
fi
echo

# ---- 4. 清理 + 打包 ----
rm -rf build dist

echo "开始打包（第一次约 1-2 分钟）..."
echo
# PYINSTALLER_CONFIG_DIR 把缓存也放项目里，避免被系统权限挡住
PYINSTALLER_CONFIG_DIR="$PROJECT_DIR/.pyinstaller_cache" \
    "$VPY" -m PyInstaller macapp.spec --noconfirm

# ---- 5. 结果 ----
echo
echo "=============================================================="
if [ -d "dist/AI手机助手.app" ]; then
    echo "  ✓ 打包成功"
    echo
    echo "  应用位置：$PROJECT_DIR/dist/AI手机助手.app"
    echo "  体积：    $(du -sh 'dist/AI手机助手.app' | cut -f1)"
    echo
    echo "  接下来："
    echo "    1. 打开 dist 文件夹，双击「AI手机助手.app」"
    echo "    2. 首次打开被 macOS 拦的话，到「系统设置 → 隐私与安全性」"
    echo "       点「仍要打开」"
    echo "    3. 应用里点「打开配置」填 API Key，重启应用"
    echo "    4. 点「环境自检」验证，通过后就能下任务"
    echo
    echo "  启动失败的排查日志："
    echo "    ~/Library/Application Support/AI手机助手/logs/startup.log"
    echo
    echo "  ⚠ 电脑上必须先装好 adb，否则连不上手机。"
    echo "     装好后在终端执行 adb version 验证。"
else
    echo "  [失败] 没有生成 .app，请看上面的错误信息。"
    exit 1
fi
echo "=============================================================="
