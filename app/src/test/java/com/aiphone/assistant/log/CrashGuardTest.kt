package com.aiphone.assistant.log

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住崩溃报告的内容。
 *
 * 这份文本是"用户导出日志、开发远程看一眼就能定位"的全部依据，
 * 所以每一块都得真的在：异常类型、消息、线程、当时在跑什么任务、
 * 完整堆栈（含 cause 链）、崩溃前那几百行日志。
 *
 * 只测文本拼装，不测 install() —— 装钩子会改全局状态，
 * 在 JVM 测试里把别的用例也带下去，反而危险。
 */
class CrashGuardTest {

    private fun report(
        error: Throwable = RuntimeException("炸了"),
        taskInfo: String = "无（崩溃发生在任务之外）",
        recent: List<String> = listOf("10:00:01.000 I/纸盒 通道: 点了 (100,200)"),
        env: List<String> = listOf("应用版本：0.10.8(75)", "机型    ：Xiaomi 2312DRA50C"),
    ) = CrashGuard.buildReport(
        at = 1_790_000_000_000L,
        threadName = "main",
        threadDaemon = false,
        error = error,
        taskInfo = taskInfo,
        env = env,
        recent = recent,
    )

    @Test
    fun `报告里有异常类型 消息 线程和任务`() {
        val text = report(error = IllegalStateException("模型配置为空"))
        assertTrue(text.contains("纸盒崩溃报告"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("模型配置为空"))
        assertTrue(text.contains("线程      ：main"))
        assertTrue(text.contains("无（崩溃发生在任务之外）"))
    }

    @Test
    fun `守护线程会被标出来`() {
        val text = CrashGuard.buildReport(
            at = 1L, threadName = "OkHttp Dispatch", threadDaemon = true,
            error = RuntimeException("x"), taskInfo = "-", env = emptyList(), recent = emptyList(),
        )
        assertTrue(text.contains("OkHttp Dispatch（守护线程）"))
    }

    @Test
    fun `异常消息为空时不留空白`() {
        val text = report(error = RuntimeException())
        assertTrue(text.contains("异常消息  ：（无）"))
    }

    @Test
    fun `完整堆栈里有抛出点的方法名`() {
        val text = report(error = boom())
        // stackTraceToString 会带上当前帧：这个测试方法自己
        assertTrue(text.contains("CrashGuardTest"))
        assertTrue(text.contains("【完整堆栈】"))
    }

    @Test
    fun `cause 链也在报告里`() {
        val text = report(error = RuntimeException("外层", IllegalArgumentException("根本原因")))
        assertTrue(text.contains("外层"))
        assertTrue(text.contains("根本原因"))
        assertTrue(text.contains("Caused by"))
    }

    @Test
    fun `崩溃前的日志按原样带出来`() {
        val text = report(
            recent = listOf(
                "10:00:01.000 I/纸盒 通道: 点了 (100,200)",
                "10:00:02.500 E/纸盒 模型: HTTP 500",
            )
        )
        assertTrue(text.contains("【崩溃前的最近日志】"))
        assertTrue(text.contains("点了 (100,200)"))
        assertTrue(text.contains("HTTP 500"))
    }

    @Test
    fun `一条日志都没有时说明没来得及记`() {
        val text = report(recent = emptyList())
        assertTrue(text.contains("日志系统还没开始工作就崩了"))
    }

    @Test
    fun `环境信息逐行写进去`() {
        val text = report()
        assertTrue(text.contains("应用版本：0.10.8(75)"))
        assertTrue(text.contains("机型    ：Xiaomi 2312DRA50C"))
    }

    @Test
    fun `在跑任务时会写出是哪一个`() {
        val text = report(taskInfo = "任务「打开设置看WiFi」· 目录 20261025_180000_打开设置看WiFi")
        assertTrue(text.contains("任务「打开设置看WiFi」"))
    }

    /** 造一个真有调用栈的异常 */
    private fun boom(): Throwable = runCatching { error("从测试里抛的") }.exceptionOrNull()!!
}
