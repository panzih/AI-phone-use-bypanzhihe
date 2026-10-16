package com.aiphone.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ActivityProbe] 的 dumpsys 解析。
 *
 * 这些解析决定"要把哪个任务栈搬到哪块屏去"，错了的后果不是报错、
 * 而是**把别的应用搬走/搬空**。而 dumpsys 的文本格式又是系统私有实现，
 * 没法在真机上穷举 ROM —— 所以固定几段真实形态的文本，把语义钉死。
 *
 * 纯 Kotlin，不需要 Robolectric（解析函数不碰 Android API）。
 */
class ActivityProbeTest {

    /** 截取真实 dumpsys 的形态（任务栈 + topResumedActivity + 两个 Display 段） */
    private val dump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):

          * Task{t1 #101 type=standard A=10086:com.tencent.mm}
            * ActivityRecord{9a2c1b3 u0 com.tencent.mm/.ui.LauncherUI t101}
          * Task{t2 #102 type=standard A=10086:com.android.settings}
            * ActivityRecord{5f1a2b3 u0 com.android.settings/.Settings t102}

          topResumedActivity=ActivityRecord{9a2c1b3 u0 com.tencent.mm/.ui.LauncherUI t101}

        Display #1 (activities from top to bottom):
          * Task{t3 #201 type=standard A=10086:com.tencent.mm}
            * ActivityRecord{1c2d3e4 u0 com.tencent.mm/.ui.LauncherUI t201}
          topResumedActivity=ActivityRecord{1c2d3e4 u0 com.tencent.mm/.ui.LauncherUI t201}

    """.trimIndent()

    // ---------- parseTaskIdForPackage ----------

    @Test
    fun `按包名找任务栈_只认主屏段`() {
        // 主屏段里微信是 t101 —— 副屏段里那个 t201 不能算进来
        assertEquals(101, ActivityProbe.parseTaskIdForPackage(dump, "com.tencent.mm"))
        assertEquals(102, ActivityProbe.parseTaskIdForPackage(dump, "com.android.settings"))
    }

    @Test
    fun `同一个包多个任务_取最后一个_最近活跃`() {
        val multi = """
            Display #0 (activities from top to bottom):
              * ActivityRecord{a u0 com.foo.app/.A t10}
              * ActivityRecord{b u0 com.foo.app/.B t11}
        """.trimIndent()
        assertEquals(11, ActivityProbe.parseTaskIdForPackage(multi, "com.foo.app"))
    }

    @Test
    fun `没有这个包或者没有主屏段_返回负一`() {
        assertEquals(-1, ActivityProbe.parseTaskIdForPackage(dump, "com.not.installed"))
        assertEquals(-1, ActivityProbe.parseTaskIdForPackage("啥都没有", "com.tencent.mm"))
    }

    @Test
    fun `包名带正则元字符也按字面匹配`() {
        val weird = """
            Display #0 (activities from top to bottom):
              * ActivityRecord{a u0 com.a+b(c)/.MainKt t77}
        """.trimIndent()
        assertEquals(77, ActivityProbe.parseTaskIdForPackage(weird, "com.a+b(c)"))
    }

    // ---------- 任务是否存在 ----------

    @Test
    fun `任务是否存在_看的是任务标记形态`() {
        assertTrue(ActivityProbe.dumpContainsTask(dump, 101))
        assertTrue("副屏上的任务也算存在（迁移前校验用）", ActivityProbe.dumpContainsTask(dump, 201))
        assertFalse(ActivityProbe.dumpContainsTask(dump, 999))
    }

    // ---------- 顶部任务 ----------

    @Test
    fun `读指定屏的顶部任务_包名和任务号`() {
        assertEquals(101 to "com.tencent.mm", ActivityProbe.parseTopResumedTask(dump, 0))
        assertEquals(201 to "com.tencent.mm", ActivityProbe.parseTopResumedTask(dump, 1))
    }

    @Test
    fun `读不存在的屏_返回空`() {
        assertNull(ActivityProbe.parseTopResumedTask(dump, 7))
        assertNull(ActivityProbe.parseTopResumedTask("Display #0 空屏没有顶部任务", 0))
    }

    // ---------- 顶部是否是该任务 / 任务是否在段内 ----------

    @Test
    fun `顶部任务判定_按段不跨屏`() {
        assertTrue(ActivityProbe.parseIsTopResumed(dump, 101, 0))
        assertFalse("t201 是副屏的顶部，不是主屏的", ActivityProbe.parseIsTopResumed(dump, 201, 0))
        assertTrue(ActivityProbe.parseIsTopResumed(dump, 201, 1))
        assertFalse(ActivityProbe.parseIsTopResumed(dump, 102, 0))
    }

    @Test
    fun `任务在不在指定屏_跨段会误判所以必须按段查`() {
        assertTrue(ActivityProbe.parseTaskInDisplay(dump, 101, 0))
        assertFalse("t201 在副屏段里，主屏段不含它", ActivityProbe.parseTaskInDisplay(dump, 201, 0))
        assertTrue(ActivityProbe.parseTaskInDisplay(dump, 201, 1))
        assertFalse(ActivityProbe.parseTaskInDisplay(dump, 999, 0))
    }

    @Test
    fun `段的右界_到下一个Display为止`() {
        val seg0 = ActivityProbe.displaySegment(dump, 0)!!
        assertTrue(seg0.startsWith("Display #0"))
        assertFalse("主屏段不能吃掉副屏段", seg0.contains("t201"))
    }
}
