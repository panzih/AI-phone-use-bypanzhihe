package com.aiphone.assistant.memory

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 记忆治理（整改清单第 8 项）：去重、上限、压缩、清空。
 *
 * 记忆是只增不减的文件，不设上限就会无限长；模型重复归纳同一件事时
 * 也不该堆出一串一模一样的条目。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MemoryStore.clear(context)
    }

    private fun entry(title: String, body: String) = MemoryEntry(title = title, body = body)

    @Test
    fun `正文重复的记忆会被跳过_标题不同也算重复`() {
        assertEquals(
            MemoryStore.AppendResult.Written,
            MemoryStore.append(context, entry("偏好", "用户喜欢深色模式")),
        )
        assertEquals(
            MemoryStore.AppendResult.Duplicate,
            MemoryStore.append(context, entry("另一个标题", "  用户喜欢深色模式\n")),
        )
        assertEquals(1, MemoryStore.stats(context).first)
    }

    @Test
    fun `写入超过上限会自动压缩_只留最新的`() {
        // 每条 1000 字，70 条 = 70K，超过 60K 上限
        repeat(70) { i ->
            MemoryStore.append(
                context,
                entry("第 $i 条", "第 $i 条：" + "记".repeat(990)),
            )
        }

        val (count, chars) = MemoryStore.stats(context)
        assertTrue("条数不能超过上限", count <= MemoryStore.MAX_ENTRIES)
        assertTrue("字符数不能超过上限", chars <= MemoryStore.MAX_CHARS)
        val text = MemoryStore.read(context)
        assertTrue("最新的记忆必须还在", text.contains("第 69 条"))
    }

    @Test
    fun `手动压缩会缩小文件`() {
        repeat(45) { i ->
            MemoryStore.append(
                context,
                entry("第 $i 条", "第 $i 条：" + "字".repeat(990)),
            )
        }
        val before = MemoryStore.stats(context).second
        assertTrue("45K 还没到自动压缩线", before < MemoryStore.MAX_CHARS)

        assertTrue("手动压缩应生效", MemoryStore.compress(context))

        val after = MemoryStore.stats(context).second
        assertTrue("压缩后应变小", after < before)
        assertTrue(MemoryStore.read(context).contains("第 44 条"))
    }

    @Test
    fun `清空记忆后条数为零`() {
        MemoryStore.append(context, entry("偏好", "用户喜欢深色模式"))
        assertEquals(1, MemoryStore.stats(context).first)

        MemoryStore.clear(context)

        assertEquals(0, MemoryStore.stats(context).first)
    }
}
