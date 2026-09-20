package com.aiphone.assistant.shell

import android.content.Context
import android.util.Log

/**
 * ADB 命令的语义化封装。
 *
 * ## 为什么把命令集中在这一个文件里
 *
 * 上层（副屏页面、将来的 ADB 操作通道）只说"截一张副屏""在这儿点一下"，
 * 不直接拼命令。这样命令格式变了、或者某条命令在某个 ROM 上要用别的写法，
 * 改动只落在这一个文件里。
 *
 * 而且**所有命令都记日志** —— 副屏这套东西在真机上调试时，
 * "我到底发了什么、系统回了什么"是最重要的信息。
 */
object AdbShell {

    private const val TAG = "AdbShell"

    // ------------------------------------------------------------------
    // 显示器
    // ------------------------------------------------------------------

    /** 一块显示器 */
    data class DisplayInfo(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int,
        /** 是不是我们建出来的虚拟屏 */
        val virtual: Boolean,
    ) {
        fun label(): String = "$name（id=$id，${width}x$height${if (virtual) "，虚拟" else ""}）"
    }

    /**
     * 列出所有显示器。
     *
     * ## 解析为什么写得这么"宽容"
     *
     * `dumpsys display` 的格式在各版本、各厂商 ROM 之间**都不一样**。
     * 想写一个精确的正则，结果是换台机器就解析不出来。
     * 所以这里只抓两样最稳定的东西：
     *
     *   - `DisplayDeviceInfo{...}` 这个开头的行（每块屏一行）
     *   - 行里的 `W x H`
     *
     * id 用**出现顺序**推断（第一块是 0），虚拟屏靠 uniqueId/名字里的
     * virtual/overlay 关键字识别。解析不出来不猜 —— 返回空列表，
     * 由调用方把原始输出摆出来给人看。
     */
    suspend fun listDisplays(context: Context): List<DisplayInfo> {
        val raw = ShizukuBridge.run(context, "dumpsys display")
        if (raw.startsWith("错误：")) return emptyList()
        return parseDisplays(raw)
    }

    /** 单独拆出来是为了能单测式地对着一段文本看逻辑（不用真机） */
    fun parseDisplays(raw: String): List<DisplayInfo> {
        val out = ArrayList<DisplayInfo>()
        var index = 0
        raw.lineSequence().forEach { line ->
            if (!line.contains("DisplayDeviceInfo{")) return@forEach
            // 名字：第一个引号里的内容
            val name = Regex("\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                ?: "显示器 $index"
            // 分辨率：形如 "1080 x 2400"
            val size = Regex("(\\d{3,5})\\s*x\\s*(\\d{3,5})").find(line)
            val w = size?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = size?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val virtual = line.contains("virtual", true) || line.contains("overlay", true)
            out.add(DisplayInfo(index, name, w, h, virtual))
            index++
        }
        return out
    }

    /** 主屏（第一块非虚拟屏） */
    suspend fun mainDisplay(context: Context): DisplayInfo? =
        listDisplays(context).firstOrNull { !it.virtual } ?: listDisplays(context).firstOrNull()

    /**
     * （旧链路）写 `overlay_display_devices` 建一块模拟副屏，和"开发者选项
     * → 模拟副屏"同一机制；只需 `WRITE_SECURE_SETTINGS`，但建出来的是
     * 非 TRUSTED 假屏。
     *
     * 当前 TRUSTED 副屏由 ShellService 反射 DisplayManager.createVirtualDisplay
     * 直连建立（见 ShizukuBridge.createDisplay），不走这里；方法保留备用。
     *
     * 值是 `<宽>x<高>/<dpi>`。
     */
    suspend fun createVirtualDisplay(context: Context, width: Int, height: Int, dpi: Int): String {
        val spec = "${width}x$height/$dpi"
        Log.i(TAG, "建副屏：$spec")
        return ShizukuBridge.run(context, "settings put global overlay_display_devices '$spec'")
    }

    /** （旧链路）撤掉 overlay_display_devices 建的模拟副屏（全部） */
    suspend fun removeVirtualDisplay(context: Context): String {
        Log.i(TAG, "撤副屏")
        return ShizukuBridge.run(context, "settings put global overlay_display_devices ''")
    }

    /**
     * 把应用起在指定屏上。
     *
     * @param component 完整的组件名（`包名/活动名`）。不传就只给包名，
     *                  让系统自己找启动入口
     */
    suspend fun startAppOnDisplay(
        context: Context,
        displayId: Int,
        packageName: String,
        component: String? = null,
    ): String {
        // 只知道包名时用 MAIN/LAUNCHER 隐式启动：不需要知道 Activity 名，
        // 系统自己找启动入口。给组件名则点名启动（更精确）
        val cmd = if (component != null) {
            "am start --display $displayId -n $component"
        } else {
            "am start --display $displayId " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -p $packageName"
        }
        return ShizukuBridge.run(context, cmd)
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------

    /** 截主屏 */
    suspend fun screenshot(context: Context): ByteArray =
        ShizukuBridge.runBytes(context, "screencap -p")

    /**
     * （旧链路）用 `screencap -d <id>` 截指定显示器。
     *
     * 对 TRUSTED 虚拟屏它返回 Status -2、拿不到图 —— 虚拟屏画面进的是
     * ImageReader，取帧走 ShizukuBridge.grabFrame。方法保留备用；
     * 拿不到时返回空数组，调用方不得显示黑图假装成功。
     */
    suspend fun screenshotDisplay(context: Context, displayId: Int): ByteArray {
        val bytes = ShizukuBridge.runBytes(context, "screencap -p -d $displayId")
        // 有些 ROM 上 -d 不认，会把错误信息打到 stdout，长度很小且不是 PNG
        if (bytes.size < 1024 || !isPng(bytes)) {
            Log.w(TAG, "screencap -d $displayId 没拿到 PNG（${bytes.size} 字节）")
            return ByteArray(0)
        }
        return bytes
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /** 在指定屏上点一下。`-d` 缺省就是主屏 */
    suspend fun tap(context: Context, displayId: Int?, x: Int, y: Int): String =
        ShizukuBridge.run(context, "input ${displayArg(displayId)}tap $x $y")

    suspend fun swipe(
        context: Context,
        displayId: Int?,
        x1: Int, y1: Int, x2: Int, y2: Int,
        durationMs: Int,
    ): String = ShizukuBridge.run(
        context,
        "input ${displayArg(displayId)}swipe $x1 $y1 $x2 $y2 $durationMs",
    )

    suspend fun keyEvent(context: Context, displayId: Int?, keyCode: Int): String =
        ShizukuBridge.run(context, "input ${displayArg(displayId)}keyevent $keyCode")

    /**
     * 往指定屏输入文字。
     *
     * ⚠️ `input text` **只支持 ASCII** —— 中文会静默失败。中文输入要靠
     * 无障碍的 ACTION_SET_TEXT（副屏上没有无障碍，所以副屏的中文输入
     * 目前做不到，这一点要在界面上说清楚）。
     */
    suspend fun inputText(context: Context, displayId: Int?, text: String): String =
        ShizukuBridge.run(
            context,
            "input ${displayArg(displayId)}text '${text.replace("'", "")}'",
        )

    private fun displayArg(displayId: Int?): String =
        if (displayId == null) "" else "-d $displayId "

    /** 屏幕上现在是什么（调试用，导出日志时带上） */
    suspend fun windowState(context: Context): String =
        ShizukuBridge.run(context, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -5")
}
