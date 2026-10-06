package com.aiphone.assistant.log

import android.content.Context
import com.aiphone.assistant.data.ContextStore
import com.aiphone.assistant.llm.ChatTurn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/** 对话历史图片必须随导出一起走，并随“删除日志”一起消失。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogExporterHistoryImageTest {
    private lateinit var context: Context
    private val shot = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ContextStore.clear(context)
        ContextStore.save(
            context,
            entries = emptyList(),
            turns = emptyList(),
            lastActivityAt = 1L,
            history = listOf(ChatTurn(ChatTurn.USER, "当前屏幕", shot)),
        )
    }

    @Test
    fun `导出包包含对话引用的原始图片`() {
        val out = LogExporter.exportEverything(context, "设备摘要").getOrThrow()
        val imageName = ChatTurn.imageIdOf(shot)

        ZipFile(out).use { zip ->
            assertNotNull(zip.getEntry("conversation.json"))
            val entry = zip.getEntry("images/$imageName")
            assertNotNull("导出的对话引用必须能在同一 ZIP 中找到原图", entry)
            assertArrayEquals(shot, zip.getInputStream(entry).use { it.readBytes() })
        }
    }

    @Test
    fun `删除日志同时清理对话和历史图片`() {
        val imageFile = File(ContextStore.imageDir(context), ChatTurn.imageIdOf(shot))
        val conversation = File(AppLog.rootDir(context), "conversation.json")
        assertTrue(imageFile.exists())
        assertTrue(conversation.exists())

        LogExporter.deleteAllLogs(context)

        assertFalse(conversation.exists())
        assertFalse(imageFile.exists())
    }

    @Test
    fun `截图文件扩展名与实际编码一致`() {
        val jpegAuto = AutoCapture.save(context, shot)!!
        assertTrue(jpegAuto.name.endsWith(".jpg"))
        assertArrayEquals(shot, jpegAuto.readBytes())

        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val logger = RunLogger(File(AppLog.rootDir(context), "test-shot").apply { mkdirs() }, "测试")
        try {
            val pngStep = logger.saveScreenshot(1, png)!!
            assertTrue(pngStep.name.endsWith(".png"))
            assertArrayEquals(png, pngStep.readBytes())
        } finally {
            logger.runDir.deleteRecursively()
        }
    }
}
