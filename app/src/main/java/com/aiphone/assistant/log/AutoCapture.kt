package com.aiphone.assistant.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动截图。
 *
 * ## 为什么要有这个
 *
 * 出问题的时候，最想知道的是"当时屏幕上到底是什么样"。而 run.log 里
 * 只有文字：模型说了什么、点了哪个元素。真正的现场是画面 ——
 * 弹了个权限框、界面卡在半路、被别的应用盖住了，这些文字都看不出来。
 *
 * ## 两个刻意的决定
 *
 * **不隐藏任何 UI。** 纸盒自己的悬浮窗、状态栏、别家应用的弹窗，
 * 全都留在画面里。这跟 Agent 自己截图时"先藏悬浮窗再拍"正好相反 ——
 * 那张图是**给模型看的**，不能有干扰；这张图是**给人看的**，
 * 要的就是所见即所得。
 *
 * **固定 5 秒一张。** 不按"有变化才存"来省空间：一旦画面没变化就跳过，
 * 恰好会漏掉"卡住不动"这个最需要看的情况。
 */
object AutoCapture {

    /** 采样间隔 */
    const val INTERVAL_MS = 5000L

    /**
     * 最多留多少张。
     *
     * 文件可能是 JPEG 或 PNG；最多留 200 张。超过就删最早的 ——
     * 排查问题时最新的那批才有用，而且不能让它把用户存储吃掉。
     */
    private const val MAX_FILES = 200

    private const val DIR = "autocap"

    /**
     * 自动截图的时间轴。
     *
     * 自动截图以前是**散装**的：文件名只有时分秒，落盘时间可能被导出/复制
     * 改掉，而且跨天就会重名。要把它和某一次运行、某一步对上是做不到的。
     * 这里补一份 `文件名 → 毫秒时间戳` 的对照表，导出的包里跟着走；
     * 同时 Agent 侧会把每次自动截图的文件名写进当次 run.log，
     * 这样"这一步前后屏幕是什么样"就能真正对上了。
     */
    private const val TIMELINE = "timeline.txt"

    private val stamp = SimpleDateFormat("HHmmss", Locale.US)
    private val timelineStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun dir(context: Context): File =
        File(AppLog.rootDir(context), DIR).apply { mkdirs() }

    fun timelineFile(context: Context): File = File(dir(context), TIMELINE)

    fun count(context: Context): Int =
        dir(context).listFiles()?.count { it.isFile && it.name != TIMELINE } ?: 0

    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * 存一帧。
     *
     * 文件名带时分秒，导出的 zip 里按名字排序就是时间顺序。
     */
    fun save(context: Context, bytes: ByteArray): File? = runCatching {
        if (bytes.isEmpty()) return null
        val extension = ImageFormat.extension(bytes)
        val now = System.currentTimeMillis()
        val f = File(dir(context), "cap_${stamp.format(Date(now))}_${now % 1000}.$extension")
        f.writeBytes(bytes)
        appendTimeline(context, f.name, now)
        prune(context)
        f
    }.getOrNull()

    /** 往时间轴追加一行；失败不影响截图本身 */
    private fun appendTimeline(context: Context, name: String, at: Long) {
        runCatching {
            timelineFile(context).appendText("$name\t$at\t${timelineStamp.format(Date(at))}\n")
        }
    }

    fun deleteAll(context: Context): Int {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return 0
        files.forEach { it.delete() }
        return files.size
    }

    /** 超量就删最早的（时间轴文件不算） */
    private fun prune(context: Context) {
        runCatching {
            dir(context).listFiles()
                ?.filter { it.isFile && it.name != TIMELINE }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }
}
