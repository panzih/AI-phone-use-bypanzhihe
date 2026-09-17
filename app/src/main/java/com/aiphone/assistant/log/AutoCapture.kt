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
     * 一张 1080p 的 PNG 约 60~90KB，200 张约 15MB。超过就删最早的 ——
     * 排查问题时最新的那批才有用，而且不能让它把用户存储吃掉。
     */
    private const val MAX_FILES = 200

    private const val DIR = "autocap"

    private val stamp = SimpleDateFormat("HHmmss", Locale.US)

    fun dir(context: Context): File =
        File(AppLog.rootDir(context), DIR).apply { mkdirs() }

    fun count(context: Context): Int =
        dir(context).listFiles()?.count { it.isFile } ?: 0

    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * 存一帧。
     *
     * 文件名带时分秒，导出的 zip 里按名字排序就是时间顺序。
     */
    fun save(context: Context, png: ByteArray): File? = runCatching {
        if (png.isEmpty()) return null
        val f = File(dir(context), "cap_${stamp.format(Date())}_${System.currentTimeMillis() % 1000}.png")
        f.writeBytes(png)
        prune(context)
        f
    }.getOrNull()

    fun deleteAll(context: Context): Int {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return 0
        files.forEach { it.delete() }
        return files.size
    }

    /** 超量就删最早的 */
    private fun prune(context: Context) {
        runCatching {
            dir(context).listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }
}
