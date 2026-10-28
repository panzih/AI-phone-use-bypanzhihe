package com.aiphone.assistant.data

import android.content.Context
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.memory.Turn
import com.aiphone.assistant.ui.LogEntry
import com.aiphone.assistant.ui.LogKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 上下文落盘的原子性（整改清单第 8 项）。
 *
 * 目标不是"能存能读"，而是：**写到一半被杀不会留下半截 JSON**，
 * 主文件坏了还能从备份回来，清空时不能把备份留下让旧上下文"复活"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextStoreAtomicTest {

    private lateinit var context: Context

    private val main: File
        get() = File(AppLog.rootDir(context), "conversation.json")
    private val backup: File
        get() = File(AppLog.rootDir(context), "conversation.json.bak")
    private val tmp: File
        get() = File(AppLog.rootDir(context), "conversation.json.tmp")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ContextStore.clear(context)
    }

    private fun save(task: String) {
        ContextStore.save(
            context = context,
            entries = listOf(
                LogEntry("e-$task", LogKind.ACTION, task, null),
            ),
            turns = listOf(Turn(Turn.Role.USER, task)),
            lastActivityAt = 1L,
            promptVersion = "v",
            modelName = "m",
            skillCatalog = "c",
        )
    }

    @Test
    fun `保存后不留临时文件_第二次保存会生成备份`() {
        save("第一次")
        assertFalse("写完不应残留 .tmp", tmp.exists())
        assertFalse("第一次没有旧文件，不该有备份", backup.exists())

        save("第二次")
        assertFalse(tmp.exists())
        assertTrue("第二次保存应把旧主文件备份下来", backup.exists())
        assertEquals("第二次", ContextStore.load(context)?.entries?.first()?.text)
    }

    @Test
    fun `主文件损坏时自动回退备份并恢复主文件`() {
        save("旧内容")
        save("新内容")
        // 模拟写到一半被杀：主文件成了半截 JSON
        main.writeText("{ this is not json")

        val restored = ContextStore.load(context)

        assertNotNull("必须能从备份恢复", restored)
        assertEquals("旧内容", restored!!.entries.first().text)
        // 恢复后主文件应重新可读，而不是每次启动都走回退
        assertNotNull("主文件应已恢复", ContextStore.load(context))
    }

    @Test
    fun `主文件损坏且没有备份时按新开处理`() {
        save("唯一内容")
        backup.delete()
        main.writeText("{ broken")

        assertNull(ContextStore.load(context))
    }

    @Test
    fun `清空上下文会连备份一起删_不会复活旧对话`() {
        save("旧内容")
        save("新内容")
        assertTrue(backup.exists())

        ContextStore.clear(context)

        assertFalse(main.exists())
        assertFalse("备份不删的话下次 load 会把旧上下文恢复回来", backup.exists())
        assertNull(ContextStore.load(context))
    }
}
