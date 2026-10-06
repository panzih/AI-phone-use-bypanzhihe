package com.aiphone.assistant.log

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把所有运行目录汇总成一张"一览表"。
 *
 * ## 为什么需要它
 *
 * 用户导出一次日志，包里往往有十几次运行。以前只有 `runs.txt` 一串目录名，
 * 想知道"哪几次失败了、失败在第几步、是不是根本读不到控件树"，
 * 必须逐个目录打开 `report.json` / `run.log` 翻 —— 而排查的第一步
 * 恰恰就是"挑出可疑的那几次"。
 *
 * 这个类只做**只读解析**：读 report.json 里已经有的字段，不改任何东西。
 * 解析失败（旧版本没有这些字段、文件被截断）就如实留空，
 * 绝不因为一条坏数据让整个导出失败。
 *
 * 纯逻辑 + org.json，可在 Robolectric 下单测（见 RunIndexTest）。
 */
object RunIndex {

    /** 一次运行的摘要 */
    data class Row(
        val dir: String,
        val task: String,
        val startedAt: Long?,
        val durationMs: Long?,
        val steps: Int?,
        val success: Boolean?,
        val outcome: String,
        /** 元素列表被 limit 截断过的步数（>=1 说明提示词可能不完整） */
        val truncatedSteps: Int,
        /** 哪几步"读不到控件树"（rootAvailable=false），最多记前 8 个 */
        val noTreeSteps: List<Int>,
    )

    /** 汇总结果 */
    data class Summary(val rows: List<Row>, val csv: String) {
        val totalSteps: Int get() = rows.sumOf { it.steps ?: 0 }
        val failedRuns: Int get() = rows.count { it.success == false }
    }

    fun build(runs: List<File>): Summary {
        val rows = runs.map { parseRun(it) }
        return Summary(rows, toCsv(rows))
    }

    /** 解析一次运行；任何字段缺失都留空，不让它影响其它运行 */
    fun parseRun(dir: File): Row {
        val report = File(dir, RunLogger.REPORT_NAME)
        var task = dir.name
        var startedAt: Long? = null
        var durationMs: Long? = null
        var steps: Int? = null
        var success: Boolean? = null
        var outcome = ""
        var truncated = 0
        val noTree = mutableListOf<Int>()

        runCatching {
            if (!report.isFile) return@runCatching
            val root = JSONObject(report.readText())
            task = root.optString("task", dir.name)
            if (root.has("startedAt")) startedAt = root.optLong("startedAt")
            if (root.has("durationMs")) durationMs = root.optLong("durationMs")
            if (root.has("success") && !root.isNull("success")) success = root.getBoolean("success")
            outcome = root.optString("outcome", "").replace('\n', ' ')
            val arr = root.optJSONArray("steps") ?: return@runCatching
            steps = arr.length()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val no = if (s.has("step")) s.optInt("step") else i + 1
                // uiTree 为 null = 那一步**没有可用的元素列表**（读不到控件树，
                // 或这一屏没有可交互元素）。这正是复盘时最该先看的那几步。
                if (s.isNull("uiTree") && noTree.size < 8) noTree.add(no)
                if (uiFileHasTruncation(dir, no)) truncated++
            }
        }.onFailure {
            android.util.Log.w("RunIndex", "解析 ${dir.name} 的 report.json 失败：$it")
        }

        return Row(dir.name, task, startedAt, durationMs, steps, success, outcome, truncated, noTree)
    }

    private fun uiFileHasTruncation(dir: File, step: Int): Boolean {
        val f = File(File(dir, RunLogger.UI_SUBDIR), "step_%02d.txt".format(step))
        return runCatching { f.isFile && f.readText().lineSequence().any { it.contains("截断=true") } }
            .getOrDefault(false)
    }

    /** 一览表（CSV）。用 UTF-8 BOM 之外的普通 CSV，Excel 打开中文正常 */
    fun toCsv(rows: List<Row>): String = buildString {
        appendLine(
            "目录,任务,开始时间,耗时秒,步数,成功,结局,截断步数,读不到控件树的步,可疑原因"
        )
        rows.forEach { r ->
            appendLine(
                listOf(
                    r.dir,
                    r.task,
                    r.startedAt?.let { formatTime(it) } ?: "",
                    r.durationMs?.let { "%.1f".format(it / 1000.0) } ?: "",
                    r.steps?.toString() ?: "",
                    when (r.success) {
                        true -> "是"
                        false -> "否"
                        null -> ""
                    },
                    r.outcome,
                    if (r.truncatedSteps > 0) r.truncatedSteps.toString() else "",
                    r.noTreeSteps.joinToString(" "),
                    suspicion(r),
                ).joinToString(",") { csvCell(it) }
            )
        }
    }

    /**
     * 给这一行标一个"值不值得细看"的提示。
     *
     * 目的是让人在几十行里**直接跳到可疑的那几行**，而不是全看一遍。
     * 判据只用 report.json 里可靠存在的东西，不做推断。
     */
    fun suspicion(r: Row): String = buildList {
        if (r.success == false) add("失败")
        if (r.noTreeSteps.isNotEmpty()) add("有读不到控件树的步")
        if (r.truncatedSteps > 0) add("元素列表被截断")
        if ((r.steps ?: 0) == 0) add("一步都没走")
    }.joinToString("；")

    private fun formatTime(ms: Long): String =
        TIMESTAMP.format(Date(ms))

    /** CSV 转义：字段里可能有逗号 / 引号 / 换行 */
    private fun csvCell(v: String): String {
        val needsQuote = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
