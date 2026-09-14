package com.aiphone.assistant.log

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunLogger internal constructor(
    val runDir: File,
    val task: String,
) {
    private val lock = Any()
    private val logFile = File(runDir, LOG_NAME)
    private val shotDir = File(runDir, SHOT_SUBDIR).apply { mkdirs() }
    private val reportFile = File(runDir, REPORT_NAME)
    private val steps = JSONArray()
    private val startedAt = System.currentTimeMillis()
    @Volatile private var closed = false
    val screenshotsDir: File get() = shotDir
    var screenshotCount: Int = 0
        private set

    fun line(message: String, tag: String = "App", level: String = "I") {
        synchronized(lock) {
            if (closed) return
            val stamped = "[${TIME.format(Date())}] $message"
            runCatching { logFile.appendText(stamped + "\n") }
        }
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
    fun section(title: String) { line(""); line("──── $title ────") }

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

    fun recordStep(step: Int, thought: String? = null, action: String? = null,
                   result: String? = null, rawModelOutput: String? = null, shot: File? = null) {
        val o = JSONObject().apply {
            put("step", step)
            put("thought", thought ?: JSONObject.NULL)
            put("action", action ?: JSONObject.NULL)
            put("result", result ?: JSONObject.NULL)
            put("rawModelOutput", rawModelOutput ?: JSONObject.NULL)
            put("screenshot", shot?.name ?: JSONObject.NULL)
            put("at", System.currentTimeMillis())
        }
        synchronized(lock) { if (closed) return; steps.put(o) }
    }

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

object AppLog {
    private const val ROOT_DIR = "纸盒"
    private const val RUNS_DIR = "runs"
    @Volatile private var current: RunLogger? = null
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun logRoot(context: Context): File = File(context.filesDir, "$ROOT_DIR/logs").apply { mkdirs() }
    fun runsDir(context: Context): File = File(logRoot(context), RUNS_DIR).apply { mkdirs() }
    fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }
    fun insightDir(context: Context): File = File(context.filesDir, "$ROOT_DIR/insights").apply { mkdirs() }
    fun current(): RunLogger? = current

    fun start(context: Context, task: String, env: List<String> = emptyList()): RunLogger {
        synchronized(this) {
            current?.close()
            val base = runsDir(context)
            val ts = stamp.format(Date())
            val slug = sanitize(task)
            var dir = File(base, "${ts}_$slug")
            var n = 2
            while (dir.exists()) { dir = File(base, "${ts}_${slug}_$n"); n++ }
            dir.mkdirs()
            val logger = RunLogger(dir, task)
            logger.section("环境信息")
            env.forEach { logger.line("  $it") }
            logger.line("  日志目录：${dir.absolutePath}")
            current = logger
            return logger
        }
    }

    fun closeCurrent() { synchronized(this) { current?.close(); current = null } }
    fun i(message: String, tag: String = "App") { current?.line(message, tag, "I") }
    fun w(message: String, tag: String = "App") { current?.line(message, tag, "W") }
    fun e(message: String, tag: String = "App") { current?.line(message, tag, "E") }

    fun environment(context: Context, appVersion: String, channelLabel: String,
                    modelName: String, baseUrl: String, detail: String): List<String> = listOf(
        "应用版本：$appVersion",
        "系统    ：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        "机型    ：${Build.MANUFACTURER} ${Build.MODEL}",
        "操作通道：$channelLabel",
        "模型    ：$modelName",
        "接口地址：$baseUrl",
        "图片精度：$detail",
    )

    fun sanitize(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\s/\\\\:*?\"<>|\\n\\r\\t]+"), "_")
            .take(24).trim('_')
        return cleaned.ifBlank { "任务" }
    }

    fun listRuns(context: Context): List<File> =
        runsDir(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
}
