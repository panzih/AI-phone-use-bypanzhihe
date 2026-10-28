package com.aiphone.assistant.task

import android.content.Context
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.ContextStore
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
 * 任务生命周期：进程死亡不能静默丢任务（整改清单第 7 项）。
 *
 * 这里只测不依赖 Agent/网络的那一半：
 *   - 任务开跑落标记、正常结束删标记；
 *   - 标记残留（进程被回收/强行停止）时，下次启动在对话里明确报告中断；
 *   - `initialize` 只做一次，Activity 重建不会用磁盘旧值盖掉运行中的状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskHostTest {

    private lateinit var context: Context
    private val marker: File
        get() = File(context.filesDir, "task_in_flight.txt")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ContextStore.clear(context)
        marker.delete()
    }

    @Test
    fun `任务标记残留时_下次启动明确报告中断`() {
        TaskHost(context).markTaskStarted("打开设置\n然后关掉 WiFi")

        val host = TaskHost(context)
        host.initialize(AppSettings(), "catalog")

        val notice = host.logs.lastOrNull { it.label == "任务中断" }
        assertNotNull("标记残留必须报告，不能静默", notice)
        assertTrue("报告里要带上任务内容", notice!!.text.contains("打开设置"))
        assertFalse("报告后要清掉标记，避免每次启动都报", marker.exists())
    }

    @Test
    fun `正常结束删标记_下次启动不误报`() {
        val first = TaskHost(context)
        first.markTaskStarted("打开设置")
        first.markTaskFinished()

        val host = TaskHost(context)
        host.initialize(AppSettings(), "catalog")

        assertNull(host.logs.lastOrNull { it.label == "任务中断" })
    }

    @Test
    fun `initialize 只做一次_不覆盖运行中的状态`() {
        val host = TaskHost(context)
        host.initialize(AppSettings(), "catalog")
        host.logs.add(LogEntry("running", LogKind.SYSTEM, "运行中", null))

        // 模拟 Activity 重建：同一个 host 再 initialize 一次
        host.initialize(AppSettings(), "catalog")

        assertEquals(1, host.logs.count { it.id == "running" })
    }
}
