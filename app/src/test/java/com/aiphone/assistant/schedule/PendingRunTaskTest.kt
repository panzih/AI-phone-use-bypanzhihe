package com.aiphone.assistant.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 定时任务交接点：确保任务内容只能被"拿着正确令牌的自己人"取走。
 *
 * 背景（2026-10-27 评估 P0-2）：`MainActivity` 导出且以前直接读 `run_task`，
 * 任意第三方 App 都能让它自动执行任务。现在任务只走进程内交接，
 * 这里钉住三条容易改坏的规则：
 *  - 令牌不对（包括外部 App 伪造的 Intent）取不到任务；
 *  - 取过一次就没了，不能重放；
 *  - 新任务覆盖旧任务时，旧令牌立即失效。
 */
class PendingRunTaskTest {

    private fun task(text: String, scheduleId: String = "id-$text") = PendingRunTask.Task(
        text = text,
        scheduleId = scheduleId,
        useVirtualDisplay = false,
    )

    @Test
    fun `令牌正确时能取到任务，且只能取一次`() {
        val token = PendingRunTask.offer(task("打开设置"))

        val first = PendingRunTask.consume(token)
        assertEquals("打开设置", first?.text)
        assertEquals("id-打开设置", first?.scheduleId)

        assertNull(PendingRunTask.consume(token))
    }

    @Test
    fun `令牌缺失或不匹配时取不到任务，也不会把任务弄丢`() {
        val token = PendingRunTask.offer(task("打开微信"))

        assertNull(PendingRunTask.consume(null))
        assertNull(PendingRunTask.consume(""))
        assertNull(PendingRunTask.consume("not-the-token"))

        // 真正的入口随后仍然能正常接手
        assertEquals("打开微信", PendingRunTask.consume(token)?.text)
    }

    @Test
    fun `新任务覆盖同一槽位后，旧令牌立即失效`() {
        val oldToken = PendingRunTask.offer(task("旧任务", scheduleId = "same-slot"))
        val newToken = PendingRunTask.offer(task("新任务", scheduleId = "same-slot"))

        assertNull(PendingRunTask.consume(oldToken))
        assertEquals("新任务", PendingRunTask.consume(newToken)?.text)
    }

    @Test
    fun `不同定时任务各自占一个槽位，互不覆盖`() {
        val a = PendingRunTask.offer(task("任务 A", scheduleId = "a"))
        val b = PendingRunTask.offer(task("任务 B", scheduleId = "b"))

        assertEquals("任务 A", PendingRunTask.consume(a)?.text)
        assertEquals("任务 B", PendingRunTask.consume(b)?.text)
    }
}
