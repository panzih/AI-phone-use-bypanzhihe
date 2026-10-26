package com.aiphone.assistant.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住崩溃现场要用的那份内存日志。
 *
 * 这个类的价值全在"崩溃那一刻还在内存里"，所以测试重点不是格式，
 * 而是**覆盖顺序对不对**：满了之后必须是最新的留下，最旧的被挤掉。
 * 顺序错了的话，崩溃报告里最有价值的那几行（临死前的）恰好会被丢掉。
 */
class RecentLogTest {

    @Test
    fun `没有内容时导出为空`() {
        val log = RecentLog(capacity = 5)
        assertTrue(log.dump().isEmpty())
        assertEquals(0, log.written)
        assertTrue(log.dumpText().contains("（空）"))
    }

    @Test
    fun `没满时按写入顺序输出`() {
        val log = RecentLog(capacity = 5)
        log.add("I", "t", "第一行", at = 1000L)
        log.add("W", "t", "第二行", at = 2000L)
        log.add("E", "t", "第三行", at = 3000L)

        val lines = log.dump()
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("第一行"))
        assertTrue(lines[1].endsWith("第二行"))
        assertTrue(lines[2].endsWith("第三行"))
    }

    @Test
    fun `写满之后只留最新的 挤掉的是最旧的`() {
        val log = RecentLog(capacity = 3)
        (1..5).forEach { log.add("I", "t", "第 $it 行", at = it * 1000L) }

        val lines = log.dump()
        assertEquals(3, lines.size)
        // 3、4、5 留下，1、2 被挤掉 —— 崩溃时最需要的是最后几条
        assertTrue(lines[0].endsWith("第 3 行"))
        assertTrue(lines[1].endsWith("第 4 行"))
        assertTrue(lines[2].endsWith("第 5 行"))
        // 累计写入数照实记，用来判断"这份现场覆盖了多长的窗口"
        assertEquals(5, log.written)
    }

    @Test
    fun `单条超长会被截断 不挤占缓冲`() {
        val log = RecentLog(capacity = 2)
        log.add("I", "t", "短的一行", at = 1L)
        log.add("I", "t", "长".repeat(RecentLog.MAX_ENTRY_CHARS + 500), at = 2L)

        val long = log.dump().last()
        assertTrue(long.contains("截断"))
        // 截断后仍然留着，短的那条也还在
        assertEquals(2, log.dump().size)
        assertTrue(long.length < RecentLog.MAX_ENTRY_CHARS + 200)
    }

    @Test
    fun `带上级别和标签 方便定位到哪个模块`() {
        val log = RecentLog(capacity = 5)
        log.add("E", "通道", "读不到控件树", at = 5000L)
        val line = log.dump().single()
        assertTrue(line.contains("E/纸盒 通道:"))
        assertTrue(line.contains("读不到控件树"))
    }

    @Test
    fun `清空之后不再吐出旧内容`() {
        val log = RecentLog(capacity = 5)
        log.add("I", "t", "删之前的一行", at = 1L)
        log.clear()
        assertTrue(log.dump().isEmpty())
        assertEquals(0, log.written)

        log.add("I", "t", "删之后的一行", at = 2L)
        assertEquals(1, log.dump().size)
    }

    @Test
    fun `dumpText 报出容量与累计写入`() {
        val log = RecentLog(capacity = 3)
        (1..7).forEach { log.add("I", "t", "行 $it", at = it.toLong()) }
        val text = log.dumpText()
        assertTrue(text.contains("3 条"))
        assertTrue(text.contains("累计写入 7"))
    }
}
