package com.aiphone.assistant.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogExporter {
    private const val KEEP_EXPORTS = 5

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

    fun exportLatest(context: Context): File? {
        val latest = AppLog.listRuns(context).firstOrNull() ?: return null
        return exportRuns(context, listOf(latest), "纸盒日志_${latest.name}")
    }

    fun exportAll(context: Context): File? = exportRuns(context, AppLog.listRuns(context), "纸盒日志_全部")

    private fun addEntry(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())

    private fun pruneOldExports(context: Context) {
        runCatching {
            AppLog.exportDir(context).listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP_EXPORTS)
                ?.forEach { it.delete() }
        }
    }

    fun share(context: Context, file: File, subject: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
