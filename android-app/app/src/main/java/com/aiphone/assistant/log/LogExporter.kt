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
 * ## 应用内不提供查看
 *
 * 日志是给开发排查用的，不是给用户读的。在应用里再做一个日志阅读器，
 * 只会让设置页变复杂，而且用户真正需要的是"把它发出去"。
 * 所以这里只保留导出，不提供浏览。
 */
object LogExporter {

    /** 导出目录里最多留几份，超了删最旧的，避免缓存无限涨 */
    private const val KEEP_EXPORTS = 5

    // ------------------------------------------------------------------
    // 打包
    // ------------------------------------------------------------------

    /**
     * 把若干运行目录打成一个 zip。
     *
     * zip 里保留每次运行的那一层目录名，这样解压出来结构跟应用里一致，
     * 看日志和截图都不用再找。
     */
    fun exportRuns(context: Context, runs: List<File>, nameHint: String = "纸盒日志"): File? {
        if (runs.isEmpty()) return null
        val out = File(AppLog.exportDir(context), "${nameHint}_${stamp()}.zip")
        return runCatching {
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                runs.forEach { run ->
                    if (run.isDirectory) {
                        run.walkTopDown().filter { it.isFile }.forEach { f ->
                            addEntry(zos, f, "${run.name}/${f.relativeTo(run).path}")
                        }
                    } else {
                        addEntry(zos, run, run.name)
                    }
                }
            }
            pruneOldExports(context)
            out
        }.getOrNull()
    }

    /** 只导最近一次运行 —— 最常用的那个 */
    fun exportLatest(context: Context): File? {
        val latest = AppLog.listRuns(context).firstOrNull() ?: return null
        return exportRuns(context, listOf(latest), "纸盒日志_${latest.name}")
    }

    /** 导出全部运行记录 */
    fun exportAll(context: Context): File? =
        exportRuns(context, AppLog.listRuns(context), "纸盒日志_全部")

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
