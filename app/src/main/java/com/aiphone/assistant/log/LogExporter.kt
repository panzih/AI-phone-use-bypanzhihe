package com.aiphone.assistant.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志导出。
 *
 * ## 为什么必须"导出"而不是"放在某个目录里"
 *
 * Android 11 之后，`/Android/data/<包名>/` 对第三方文件管理器和 USB(MTP)
 * 都**不可见**了。也就是说，把日志写在应用私有目录里，用户根本拿不到它 ——
 * 这是这个功能最容易做错的地方：开发时用 adb pull 拿得到，
 * 用户手动就是找不到。
 *
 * 所以这里唯一对外的出口是**系统分享面板**：打包成 zip 之后
 * 用户可以发微信、发邮件、存到网盘、或者用「文件」App 存到本地 ——
 * 全都不需要任何存储权限，也不依赖"用户能找到 /Android/data"这个前提。
 *
 * ## 应用内不提供查看，只提供两件事
 *
 *   导出  把所有 debug 相关的东西打成一个 zip
 *   删除  把日志相关的目录一次清掉
 *
 * 日志是给开发排查用的，不是给用户读的。在应用里再做一个日志阅读器，
 * 只会让设置页变复杂，而且用户真正需要的是"把它发出去"或者"清掉"。
 *
 * ## 导出包里到底有什么
 *
 * 原则是：**排查时可能要问的东西，全都在里面**，而不是让用户再来回
 * 截图、描述。所以除了运行日志和截图，还包括设备信息、当前设置
 * （API Key 打码）、记忆、学到的技能、定时任务、以及对话记录。
 */
object LogExporter {

    /** 导出目录里最多留几份，超了删最旧的，避免缓存无限涨 */
    private const val KEEP_EXPORTS = 5

    // ------------------------------------------------------------------
    // 打包
    // ------------------------------------------------------------------

    /**
     * 导出**所有 debug 内容**。
     *
     * 目录结构：
     *
     * ```
     * 纸盒日志_<时间>.zip
     * ├── manifest.txt          这个包里有什么、为什么带它
     * ├── device.txt            设备 + 应用 + 当前设置（Key 打码）+ 技能/记忆概览
     * ├── logs/runs/...         每次任务的 run.log / report.json / 截图
     * ├── logs/diag/...         自检、设备列表这类一次性输出
     * ├── autocap/…png          每 5 秒一张的自动截图（不隐藏任何 UI 的那种）
     * ├── memory.md             记忆全文
     * ├── conversation.json     主界面上那段对话
     * ├── macros/…json          录制的技能
     * └── schedules.json        定时任务
     * ```
     *
     * ⚠️ 上面几行里的通配符故意写成省略号，**不能写成"斜杠 + 星号"** ——
     * Kotlin 的块注释是**可以嵌套的**（这点和 Java 不一样），
     * 那个两字符组合会被当成"又开了一层注释"，于是整个文件的后半段
     * 都被吃掉，报错只说 "Unclosed comment"。这段注释本身就是踩过之后的说明。
     *
     * @param summary 设备/设置概览的文本，由调用方拼（它才拿得到 AppSettings）
     */
    fun exportEverything(context: Context, summary: String): File? {
        val runs = AppLog.listRuns(context)
        val diagDir = File(AppLog.logRoot(context), "diag")
        val autocap = AutoCapture.dir(context)
        val memory = File(AppLog.rootDir(context), "memory.md")
        val conversation = File(AppLog.rootDir(context), "conversation.json")
        val macros = AppLog.macroDir(context)

        val out = File(AppLog.exportDir(context), "纸盒日志_${stamp()}.zip")
        return runCatching {
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                addText(zos, "manifest.txt", manifest(runs.size, AutoCapture.count(context)))
                addText(zos, "device.txt", summary)

                // 运行日志
                runs.forEach { run ->
                    run.walkTopDown().filter { it.isFile }.forEach { f ->
                        addEntry(zos, f, "logs/runs/${run.name}/${f.relativeTo(run).path}")
                    }
                }
                // 诊断日志
                diagDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    addEntry(zos, f, "logs/diag/${f.name}")
                }
                // 自动截图
                autocap.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                    addEntry(zos, f, "autocap/${f.name}")
                }
                // 记忆 / 对话 / 技能 / 定时任务
                if (memory.exists()) addEntry(zos, memory, "memory.md")
                if (conversation.exists()) addEntry(zos, conversation, "conversation.json")
                macros.listFiles()?.filter { it.isFile }?.forEach { f ->
                    addEntry(zos, f, "macros/${f.name}")
                }
                val schedules = File(AppLog.rootDir(context), "schedules.json")
                if (schedules.exists()) addEntry(zos, schedules, "schedules.json")

                // runs 目录名带中文和空格，列一份清单方便对照
                addText(
                    zos,
                    "runs.txt",
                    if (runs.isEmpty()) "（没有运行记录）"
                    else runs.joinToString("\n") { it.name },
                )
            }
            pruneOldExports(context)
            out
        }.getOrNull()
    }

    private fun addText(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun manifest(runCount: Int, shotCount: Int): String = buildString {
        appendLine("纸盒日志包")
        appendLine("导出时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine()
        appendLine("内容：")
        appendLine("  logs/runs/     每次任务的完整记录（$runCount 次）")
        appendLine("  logs/diag/     自检、设备列表这类一次性输出")
        appendLine("  autocap/       每 5 秒一张的自动截图（$shotCount 张）")
        appendLine("  device.txt     设备信息、当前设置、技能与记忆概览")
        appendLine("  memory.md      AI 沉淀的记忆（只增不减）")
        appendLine("  conversation.json  主界面上的对话")
        appendLine("  macros/        录制的技能")
        appendLine("  schedules.json 定时任务")
        appendLine()
        appendLine("排查建议：")
        appendLine("  1. 先看 device.txt —— 版本、模型、思考模式、上下文策略都在里面")
        appendLine("  2. 再看 logs/runs/<最近一次>/run.log —— 每一步的模型输出和前缀复用都在这")
        appendLine("  3. 对不上时间线时翻 autocap/ —— 文件名是时分秒")
        appendLine()
        appendLine("API Key 已打码，不会出现在这个包里。")
    }

    /**
     * 删掉所有日志。
     *
     * **不碰**记忆、技能、定时任务 —— 那些是用户的资产，不是日志。
     * 只清"这一次次运行留下的痕迹"。
     */
    fun deleteAllLogs(context: Context): Int {
        var n = 0
        AppLog.listRuns(context).forEach { run ->
            if (run.deleteRecursively()) n++
        }
        val diag = File(AppLog.logRoot(context), "diag")
        diag.listFiles()?.forEach { it.delete() }
        n += AutoCapture.deleteAll(context)
        AppLog.exportDir(context).listFiles()?.forEach { it.delete() }
        File(AppLog.rootDir(context), "conversation.json").delete()
        AppLog.i("已删除日志（$n 项）", "日志")
        return n
    }

    private fun addEntry(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())

    private fun pruneOldExports(context: Context) {
        runCatching {
            AppLog.exportDir(context).listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP_EXPORTS)
                ?.forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------
    // 出口：系统分享面板
    // ------------------------------------------------------------------

    /**
     * 弹出系统分享面板。
     *
     * 需要 manifest 里注册 FileProvider（authority = <包名>.fileprovider），
     * 否则收不到这个 Uri —— 直接传 file:// 会在 Android 7 以上抛
     * FileUriExposedException 崩掉。
     */
    fun share(context: Context, file: File, subject: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "导出日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}
