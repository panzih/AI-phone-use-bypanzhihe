#!/usr/bin/env bash
#
# 清理构建产物。
#
# 什么时候用：磁盘紧张的时候。构建缓存 + APK 能占几百 MB，
# 而 Android Studio 自己还要写索引 —— 磁盘满了它的表现是
# "同步失败 / 编译失败"，但报错里只会说
# `No space left on device`，不告诉你是磁盘的问题。
#
#     bash tools/clean_build.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

echo "清理前："
df -h /System/Volumes/Data 2>/dev/null | tail -1 | awk '{print "  可用 " $4}'
du -sh app/build 2>/dev/null | awk '{print "  app/build " $1}' || true

rm -rf app/build .kotlin build

echo
echo "已删除：app/build / .kotlin / build"
echo
df -h /System/Volumes/Data 2>/dev/null | tail -1 | awk '{print "清理后可用 " $4}'
echo
echo "注意：没有动 ~/.gradle —— 那是所有 Android 工程共用的依赖缓存，"
echo "      删了别的项目也要重新下载，不划算。"
echo "      如果确实要清它：rm -rf ~/.gradle/caches（下次构建会重新下载依赖）"
