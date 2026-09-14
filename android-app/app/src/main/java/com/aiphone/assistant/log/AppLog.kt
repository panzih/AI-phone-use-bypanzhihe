package com.aiphone.assistant.log

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次运行的日志。
 *
 * ## 为什么每次运行要单独一个目录
 *
 * 最早的设计是"一个 last_run.json 覆盖写"。那个做法有个致命问题：
 * 第二次运行就把第一次冲掉了，用户说"上次它点了什么"时你什么都查不到。
 *
 * 所以这里改成 **每次运行一个独立目录，永不覆盖**：
 *
 * ```
 * <应用私有目录>/纸盒/logs/runs/
 *   20260914_183012_打开设置看WiFi/
 *     run.log          完整文字记录（窗口里看到的全部，带时间戳）
 *     report.json      结构化记录（每步动作 + AI 原始输出 + 结果）
 *     screenshots/     每一步的截图
 * ```
 *
 * ## 为什么每写一行就落盘
 *
 * 自动化工具最常见的失败形态是"卡死"或"被系统杀掉"，
 * 那时候日志缓冲区里没刷出去的内容全丢 —— 而丢掉的往往正是最后那几行，
 * 也就是最关键的线索。所以这里不做缓冲，每行 append + close。
 * 代价是每行一次 IO，但日志行数很少，可以忽略。
 *
 * ## 为什么同时打 logcat
 *
 * 用 adb 连着调试时，`adb logcat -s 纸盒` 能实时看到，
 * 不用一边跑一边去翻文件。两边内容一致。
 */
class RunLogger internal constructor(
    /** 本次运行的目录 */
    val runDir: File,
    /** 用户输入的任务描述，用来给目录命名 */
    val task: String,
) {

    private val lock = Any()
    private val logFile = File(runDir, LOG_NAME)
    private val shotDir = File(runDir, SHOT_SUBDIR).apply { mkdirs() }
    private val reportFile = File(runDir, REPORT_NAME)

    /** 结构化记录，close() 时一次性写成 report.json */
    private val steps = JSONArray()

    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var closed = false

    val screenshotsDir: File get() = shotDir

    /** 这次运行到现在产生了几个截图 */
    var screenshotCount: Int = 0
        private set

    // ------------------------------------------------------------------
    // 写日志
    // ------------------------------------------------------------------

    /**
     * 写一行。
     *
     * @param tag 来源模块，只用于 logcat 过滤
     * @param level "I" / "W" / "E"，E 在界面上会标红
     */
    fun line(message: String, tag: String = "App", level: String = "I") {
        synchronized(lock) {
            if (closed) return
            val stamped = "[${TIME.format(Date())}] $message"
            runCatching { logFile.appendText(stamped + "\n") }
        }
        // 同时打 logcat。不放在 synchronized 里，避免拖慢文件写入。
        runCatching {
            when (level) {
                "E" -> android.util.Log.e(LOGCAT_TAG, "$tag: $message")
                "W" -> android.util.Log.w(LOGCAT_TAG, "$tag: $message")
                else -> android.util.Log.i(LOGCAT_TAG, "$tag: $message")
            }
        }
    }

    fun warn(message: String, tag: String = "App") = line(message, tag, "W")

    fun error(message: String, tag: String = "App") = line(message, tag, "E")

    /** 居中分段标题，让长日志好读一些 */
    fun section(title: String) {
        line("")
        line("──── $title ────")
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------

    /**
     * 存一张截图。
     *
     * 命名用步号补零（step_01 / step_02），这样文件管理器里是按顺序排的，
     * 不会出现 step_10 排在 step_2 前面那种事。
     */
    fun saveScreenshot(step: Int, bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        return synchronized(lock) {
            if (closed) return null
            runCatching {
                val f = File(shotDir, "step_%02d.png".format(step))
                f.writeBytes(bytes)
                screenshotCount++
                f
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------
    // 结构化记录
    // ------------------------------------------------------------------

    /**
     * 记一步。这是 report.json 的内容，也是将来"让 AI 复盘"的输入。
     *
     * @param shot 这一步的截图文件，没有就传 null
     */
    fun recordStep(
        step: Int,
        thought: String? = null,
        action: String? = null,
        result: String? = null,
        rawModelOutput: String? = null,
        shot: File? = null,
    ) {
        val o = JSONObject().apply {
            put("step", step)
            put("thought", thought ?: JSONObject.NULL)
            put("action", action ?: JSONObject.NULL)
            put("result", result ?: JSONObject.NULL)
            put("rawModelOutput", rawModelOutput ?: JSONObject.NULL)
            put("screenshot", shot?.name ?: JSONObject.NULL)
            put("at", System.currentTimeMillis())
        }
        synchronized(lock) {
            if (closed) return
            steps.put(o)
        }
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    /** 写 report.json 并封口。之后再写日志会被忽略。 */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching {
                val root = JSONObject().apply {
                    put("task", task)
                    put("startedAt", startedAt)
                    put("endedAt", System.currentTimeMillis())
                    put("durationMs", System.currentTimeMillis() - startedAt)
                    put("screenshots", screenshotCount)
                    put("steps", steps)
                }
                reportFile.writeText(root.toString(2))
            }
        }
    }

    companion object {
        const val LOG_NAME = "run.log"
        const val REPORT_NAME = "report.json"
        const val SHOT_SUBDIR = "screenshots"
        const val LOGCAT_TAG = "纸盒"

        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

/**
 * 全局日志入口。
 *
 * 让任意一层（通道、界面、将来的 Agent 循环）都能直接写日志，
 * 不用一层层把 RunLogger 传下去。
 *
 * 没有正在运行的任务时，写日志是**安全的空操作** ——
 * 调用方不需要先判断"现在有没有在跑任务"。
 */
object AppLog {

    private const val ROOT_DIR = "纸盒"
    private const val RUNS_DIR = "runs"

    @Volatile
    private var current: RunLogger? = null

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** 日志根目录：<应用私有目录>/纸盒/logs */
    fun logRoot(context: Context): File =
        File(context.filesDir, "$ROOT_DIR/logs").apply { mkdirs() }

    /** 所有运行记录的目录 */
    fun runsDir(context: Context): File = File(logRoot(context), RUNS_DIR).apply { mkdirs() }

    /** 导出文件放这儿，FileProvider 也只暴露这一个目录 */
    fun exportDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    /** 用户洞察（记忆）目录 */
    fun insightDir(context: Context): File =
        File(context.filesDir, "$ROOT_DIR/insights").apply { mkdirs() }

    fun current(): RunLogger? = current

    /**
     * 开一次新的运行。
     *
     * @param env 环境信息，会在日志开头打印。这些是排查问题时第一批要问的东西
     *            （版本、机型、模型、通道），写进日志就不用再问用户了。
     */
    fun start(context: Context, task: String, env: List<String> = emptyList()): RunLogger {
        synchronized(this) {
            current?.close()

            val base = runsDir(context)
            val ts = stamp.format(Date())
            val slug = sanitize(task)
            var dir = File(base, "${ts}_$slug")
            var n = 2
            while (dir.exists()) {
                dir = File(base, "${ts}_${slug}_$n")
                n++
            }
            dir.mkdirs()

            val logger = RunLogger(dir, task)
            logger.section("环境信息")
            env.forEach { logger.line("  $it") }
            logger.line("  日志目录：${dir.absolutePath}")
            current = logger
            return logger
        }
    }

    fun closeCurrent() {
        synchronized(this) {
            current?.close()
            current = null
        }
    }

    /** 便捷方法：没有活动任务时静默丢弃 */
    fun i(message: String, tag: String = "App") { current?.line(message, tag, "I") }
    fun w(message: String, tag: String = "App") { current?.line(message, tag, "W") }
    fun e(message: String, tag: String = "App") { current?.line(message, tag, "E") }

    /**
     * 收集环境信息。
     *
     * 放在这里而不是让调用方拼，是因为"该记哪些"这件事应该只有一个答案 ——
     * 分两处写迟早会漏。
     */
    fun environment(
        context: Context,
        appVersion: String,
        channelLabel: String,
        modelName: String,
        baseUrl: String,
        detail: String,
    ): List<String> = listOf(
        "应用版本：$appVersion",
        "系统    ：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        "机型    ：${Build.MANUFACTURER} ${Build.MODEL}",
        "操作通道：$channelLabel",
        "模型    ：$modelName",
        "接口地址：$baseUrl",
        "图片精度：$detail",
    )

    /**
     * 目录名清理。
     *
     * 用户输入的任务描述里什么字符都可能有（换行、斜杠、冒号），
     * 直接拿去当目录名会创建失败或者造出诡异的路径。
     * 只保留中英文、数字和少量安全符号，其余换成下划线。
     */
    fun sanitize(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\s/\\\\:*?\"<>|\\n\\r\\t]+"), "_")
            .take(24)
            .trim('_')
        return cleaned.ifBlank { "任务" }
    }

    /** 列出所有运行目录，最新的在前 */
    fun listRuns(context: Context): List<File> =
        runsDir(context).listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?: emptyList()
}
