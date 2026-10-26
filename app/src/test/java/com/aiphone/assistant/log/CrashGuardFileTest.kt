package com.aiphone.assistant.log

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 验"崩溃真的落到磁盘上"。
 *
 * 上一份 [CrashGuardTest] 只测了报告文本拼装；这个测的是最容易在真机上线后
 * 才发现坏掉的那一段 —— 文件到底有没有被创建、内容读得出来、"有没有没提示过的"
 * 数得准不准、以及攒太多会不会把磁盘塞满。
 *
 * 用 Robolectric 是因为要真拿 `context.filesDir`；不装全局钩子
 * （那会污染同进程里的其它用例），直接调 [CrashGuard.recordCrash]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashGuardFileTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        AppLog.attach(ctx)
        // 同一进程里别的用例可能往内存缓冲写过东西，先清干净，免得断言互相干扰
        AppLog.clearRecent()
    }

    @After
    fun tearDown() {
        CrashGuard.crashDir(ctx).listFiles()?.forEach { it.delete() }
        AppLog.clearRecent()
    }

    private fun crash(message: String = "炸了"): File? =
        CrashGuard.recordCrash(ctx, threadName = "main", threadDaemon = false, error = RuntimeException(message))

    @Test
    fun `崩溃会生成一份能读的报告`() {
        val f = crash("模型配置为空")
        assertNotNull(f)
        assertTrue(f!!.isFile)
        assertEquals(CrashGuard.CRASH_SUBDIR, f.parentFile?.name)

        val text = f.readText()
        assertTrue(text.contains("纸盒崩溃报告"))
        assertTrue(text.contains("java.lang.RuntimeException"))
        assertTrue(text.contains("模型配置为空"))
    }

    @Test
    fun `崩溃时内存里的日志跟着进报告`() {
        AppLog.e("点了 (100,200) 之后什么都没发生", "通道")
        val text = crash()!!.readText()
        assertTrue(text.contains("点了 (100,200) 之后什么都没发生"))
    }

    @Test
    fun `连续两次崩溃不会互相覆盖`() {
        val a = crash("第一次")!!
        val b = crash("第二次")!!
        assertTrue("两份文件必须路径不同", a.absolutePath != b.absolutePath)
        assertTrue(a.isFile && b.isFile)
        assertEquals(2, CrashGuard.listAll(ctx).size)
    }

    @Test
    fun `没提示过的崩溃数得准 提示之后归零`() {
        assertEquals(0, CrashGuard.unreportedCount(ctx))
        crash()
        assertEquals(1, CrashGuard.unreportedCount(ctx))
        crash()
        assertEquals(2, CrashGuard.unreportedCount(ctx))

        CrashGuard.markReported(ctx)
        // 提示过之后不该再数进去，否则用户每次冷启动都被提醒一遍
        assertEquals(0, CrashGuard.unreportedCount(ctx))

        // 标记之后又崩的，仍然要提醒
        Thread.sleep(10)
        crash()
        assertEquals(1, CrashGuard.unreportedCount(ctx))
    }

    @Test
    fun `攒太多只留最近的那几份`() {
        val dir = CrashGuard.crashDir(ctx)
        // 造 25 份旧的（文件名时间戳递增，模拟历史记录）
        (1..25).forEach {
            File(dir, "crash_2026010${it}_120000_000.log").writeText("旧报告 $it")
        }
        crash() // 这次写盘会顺手清理

        val all = CrashGuard.listAll(ctx)
        assertTrue("最多留 ${CrashGuard.KEEP_REPORTS} 份，实际 ${all.size}", all.size <= CrashGuard.KEEP_REPORTS)
        // 最新那份一定还在（它排在文件名最后）
        assertTrue(all.first().name.startsWith("crash_2"))
    }

    @Test
    fun `删除日志会把崩溃记录一起清掉`() {
        crash()
        assertTrue(CrashGuard.listAll(ctx).isNotEmpty())
        CrashGuard.deleteAll(ctx)
        assertTrue(CrashGuard.listAll(ctx).isEmpty())
    }
}
