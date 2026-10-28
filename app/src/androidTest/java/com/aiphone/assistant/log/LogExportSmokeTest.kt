package com.aiphone.assistant.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * 日志导出的设备冒烟测试。
 *
 * 覆盖的是一条完整链路：应用私有目录里写一份 run.log → 打包 → 用系统
 * `ZipFile` 读回来。重点不是“文件存在”，而是：
 *
 *   - `manifest.txt` / `index.csv` / `device.txt` 这些排查入口真的在；
 *   - 文本里的 `sk-` Key 和 Bearer 真的被脱敏，不是只在单测里成立。
 *
 * 不测分享面板 —— 那一步依赖系统 UI，属于人工验收。
 */
@RunWith(AndroidJUnit4::class)
class LogExportSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun exportEverything_producesZipWithManifestAndSanitizedLog() {
        val runDir = File(AppLog.runsDir(context), "androidTest_${System.currentTimeMillis()}")
        assertTrue("建不出测试运行目录：$runDir", runDir.mkdirs())
        File(runDir, "run.log").writeText(
            "Authorization: Bearer abcdefghijklmnopqrstuvwxyz\n" +
                "api_key=sk-abcdefghijklmnop1234\n" +
                "普通日志：这一步点开了设置\n"
        )

        val zipFile: File
        try {
            val result = LogExporter.exportEverything(context, "androidTest 设备摘要")
            assertTrue("导出失败：${result.exceptionOrNull()}", result.isSuccess)
            zipFile = result.getOrThrow()
        } finally {
            runDir.deleteRecursively()
        }

        try {
            ZipFile(zipFile).use { zip ->
                listOf("manifest.txt", "device.txt", "index.csv", "runs.txt").forEach { name ->
                    assertNotNull("导出包缺少 $name", zip.getEntry(name))
                }

                val entry = zip.getEntry("logs/runs/${runDir.name}/run.log")
                assertNotNull("导出包缺少本次运行日志", entry)
                val text = zip.getInputStream(entry).bufferedReader().readText()

                assertTrue("文本日志没有进包", text.contains("普通日志"))
                assertFalse("Bearer 没有被脱敏", text.contains("abcdefghijklmnopqrstuvwxyz"))
                assertFalse("sk- Key 没有被脱敏", text.contains("sk-abcdefghijklmnop1234"))
                assertTrue("脱敏标记应该保留", text.contains("Bearer ***"))
            }
        } finally {
            zipFile.delete()
        }
    }
}
