#!/bin/bash
# setup_gradle.sh — 生成 gradle wrapper（可选，仅在 Android Studio 同步失败时用）
#
# 正常情况下你不需要运行这个脚本：
#   用 Android Studio 打开工程时，它会根据自己的 Gradle 自动补齐 wrapper。
#
# 什么情况下需要跑它：
#   Android Studio 提示 "Could not find or load main class org.gradle.wrapper.GradleWrapperMain"
#   或者 "gradlew: No such file or directory"
#
# 用法：bash setup_gradle.sh

set -e
cd "$(dirname "$0")"

echo "=============================================================="
echo "  生成 Gradle Wrapper"
echo "=============================================================="
echo

# gradle-wrapper.jar 是二进制文件，不适合手工写，所以这里用两种办法拿：
#   1. 本机已装的 gradle -> 直接 gradle wrapper
#   2. 没有 gradle -> 从已装好的其他项目里复制一个

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"

if [ -f "$WRAPPER_JAR" ]; then
    echo "wrapper 已经存在，无需生成：$WRAPPER_JAR"
    exit 0
fi

mkdir -p gradle/wrapper

# --- 办法 1：用本机 gradle ---
if command -v gradle >/dev/null 2>&1; then
    echo "发现本机 gradle：$(gradle --version 2>/dev/null | grep Gradle | head -1)"
    echo "正在生成 wrapper ..."
    gradle wrapper --gradle-version 8.9
    echo
    echo "✓ 生成完成"
    exit 0
fi

# --- 办法 2：从 Android Studio 自带的 gradle 里找 ---
STUDIO_GRADLE="/Applications/Android Studio.app/Contents/plugins/gradle/lib"
if [ -d "$STUDIO_GRADLE" ]; then
    echo "尝试从 Android Studio 自带 Gradle 中查找 wrapper ..."
    FOUND=$(find "$STUDIO_GRADLE" -name "gradle-wrapper*.jar" 2>/dev/null | head -1)
    if [ -n "$FOUND" ]; then
        cp "$FOUND" "$WRAPPER_JAR"
        echo "✓ 已复制：$FOUND"
        exit 0
    fi
fi

# --- 办法 3：从其他 Android 项目里借一个 ---
echo "本机没有 gradle，正在搜索其他 Android 项目里现成的 wrapper ..."
FOUND=$(find "$HOME" -maxdepth 6 -path "*/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null | head -1)
if [ -n "$FOUND" ]; then
    cp "$FOUND" "$WRAPPER_JAR"
    echo "✓ 已从 $FOUND 复制"
    exit 0
fi

echo
echo "[没找到] 三种办法都没能拿到 gradle-wrapper.jar。"
echo
echo "不用慌，两个替代方案："
echo
echo "  方案 A（推荐）：直接用 Android Studio 打开，它自己能补齐 wrapper。"
echo "                  如果同步失败，在设置里手动指定 Gradle："
echo "                  Settings → Build → Build Tools → Gradle"
echo "                  → 选 'Use Gradle from: Specified location'"
echo "                  → 指向 Android Studio 自带的 gradle"
echo
echo "  方案 B：装一个 gradle 再跑这个脚本"
echo "                  brew install gradle"
echo "                  bash setup_gradle.sh"
exit 1
