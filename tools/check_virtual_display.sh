#!/usr/bin/env bash
#
# 副屏（虚拟显示器）方案的可行性验证。
#
# 为什么要有这个脚本：
#   ADB 副屏这条路依赖三条系统命令，它们在不同 Android 版本 / 厂商 ROM 上
#   的表现不一样。这三条只要有一条不成立，整套方案就得换做法。
#   与其闷头写一千多行、装到手机上发现第一条就跑不通，不如先花两分钟确认。
#
#   **不需要 Shizuku** —— adb shell 和 Shizuku 拿到的是同一个权限
#   （shell 用户），所以这里能跑通，应用里用 Shizuku 就能跑通。
#
# 用法（手机用数据线连上电脑，打开 USB 调试）：
#
#     bash tools/check_virtual_display.sh
#
set -uo pipefail

ADB="${ADB:-adb}"

if ! command -v "$ADB" >/dev/null 2>&1; then
    echo "❗ 找不到 adb。装上 platform-tools，或者用绝对路径："
    echo "   ADB=~/Library/Android/sdk/platform-tools/adb bash tools/check_virtual_display.sh"
    exit 1
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "❗ 没有检测到设备。插上数据线，手机上点「允许 USB 调试」，然后重跑。"
    exit 1
fi

echo "设备：$("$ADB" shell getprop ro.product.model | tr -d '\r')" \
     "· Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
echo

清理() {
    # 不管成功失败，退出时都把测试用的副屏撤掉，
    # 免得用户手机上莫名其妙多一块屏幕
    echo
    echo "清理：撤掉测试用的副屏 ..."
    "$ADB" shell settings put global overlay_display_devices '""' >/dev/null 2>&1
    echo "完成。"
}
trap 清理 EXIT

# ----------------------------------------------------------------------
echo "【1/4】创建虚拟副屏（settings put global overlay_display_devices）"
echo "     这条是整套方案的地基。它走的是 WRITE_SECURE_SETTINGS，shell 有这个权限。"
echo
"$ADB" shell settings put global overlay_display_devices '1080x1920/320'
sleep 3

显示器=$("$ADB" shell dumpsys display | grep -c "DisplayDeviceInfo")
echo "     现在系统里有 $显示器 块显示器（原来是 1，能看到 2 就是成功了）"
echo

# 找出副屏的 display id
副屏ID=$("$ADB" shell dumpsys display | grep -A3 "mDisplayId=" | grep -oE "mDisplayId=[0-9]+" | grep -oE "[0-9]+" | sort -u | tail -1)
echo "     副屏 display id 推测为：${副屏ID:-未识别}"
echo

# ----------------------------------------------------------------------
echo "【2/4】副屏截图（screencap -d <id>）"
echo "     这条决定「能不能在应用里显示副屏画面」。"
echo
echo "     screencap 支持的参数："
"$ADB" shell screencap -h 2>&1 | head -12 | sed 's/^/       /'
echo
if [ -n "${副屏ID:-}" ]; then
    if "$ADB" exec-out screencap -p -d "$副屏ID" > /tmp/vd_test.png 2>/tmp/vd_err.txt; then
        大小=$(wc -c < /tmp/vd_test.png | tr -d ' ')
        if [ "$大小" -gt 1000 ]; then
            echo "     ✅ 截到了 $大小 字节 → /tmp/vd_test.png（可以打开看看是不是副屏）"
        else
            echo "     ❌ 命令成功但只有 $大小 字节，多半是空图"
        fi
    else
        echo "     ❌ 失败：$(cat /tmp/vd_err.txt | head -3)"
    fi
fi
echo

# ----------------------------------------------------------------------
echo "【3/4】往副屏注入触控（input -d <id> tap x y）"
echo "     注意：**无障碍做不到这个**。dispatchGesture 没有 displayId 参数，"
echo "     只能作用于默认屏幕。所以副屏上的点击只能用 shell 的 input -d。"
echo
echo "     input 的用法帮助："
"$ADB" shell input 2>&1 | head -8 | sed 's/^/       /'
echo
if [ -n "${副屏ID:-}" ]; then
    "$ADB" shell input -d "$副屏ID" tap 540 960 && echo "     ✅ 命令被接受了（画面有没有反应要自己看副屏）" \
        || echo "     ❌ input -d 不被支持"
fi
echo

# ----------------------------------------------------------------------
echo "【4/4】把应用起在副屏上（am start --display <id>）"
echo
if [ -n "${副屏ID:-}" ]; then
    "$ADB" shell am start --display "$副屏ID" -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1 | head -3 | sed 's/^/       /'
    echo "     （上面只要不是报错，说明能往指定屏幕起应用）"
fi
echo

cat <<'结语'
----------------------------------------------------------------------
结论怎么读：

  1 成功  → 副屏能建出来，方案成立
  2 失败  → 关键问题：没法把副屏画面拿到应用里显示。
            退路是改用 MediaProjection（要用户授权 + 常驻通知），
            或者放弃"显示副屏"只做"在副屏上跑任务"。
  3 失败  → 副屏上点不了，方案不成立（无障碍也不行，它没有 displayId）
  4 失败  → 应用起不到副屏上，可以让用户手动在副屏上打开目标应用

把这几行的输出发我，我据此决定怎么写。
结语
