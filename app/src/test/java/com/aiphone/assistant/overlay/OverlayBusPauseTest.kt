package com.aiphone.assistant.overlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「暂停」这条新状态机的三条约定。
 *
 * 它们都是 Agent 主循环读的东西，写错了不会编译报错、只会静默走错分支 ——
 * 典型表现是"按了继续它不动"或"新任务一开头就当成已停止"，
 * 这两种在真机上都要跑一轮任务才看得出来，所以放在这里。
 */
class OverlayBusPauseTest {

    @After
    fun tearDown() {
        // 单例对象，测完必须还原，否则会污染同一 JVM 里的其它用例
        OverlayBus.clearPause()
        OverlayBus.clearUserNotes()
        OverlayBus.clearStop()
    }

    @Test
    fun `暂停可以反复切换，恢复后不留尾巴`() {
        assertFalse(OverlayBus.pauseRequested)
        OverlayBus.togglePause()
        assertTrue(OverlayBus.pauseRequested)
        OverlayBus.togglePause()
        assertFalse(OverlayBus.pauseRequested)
    }

    @Test
    fun `按停止会顺手清掉暂停请求`() {
        // 否则 Agent 会先进暂停等待、再看停止 —— 表现是"按了停止还要再点一次继续"
        OverlayBus.requestPause()
        OverlayBus.requestStop()
        assertFalse(OverlayBus.pauseRequested)
        assertTrue(OverlayBus.stopRequested)
    }

    @Test
    fun `暂停期间补的话按顺序取走，取完为空`() {
        OverlayBus.pushUserNote("别打开 B 站")
        OverlayBus.pushUserNote("用微信搜")
        assertEquals(listOf("别打开 B 站", "用微信搜"), OverlayBus.drainUserNotes())
        assertTrue(OverlayBus.drainUserNotes().isEmpty())
    }

    @Test
    fun `空白补充不入队`() {
        // 暂停时手滑按一下发送（或者只敲了空格）不该变成一轮空指令
        OverlayBus.pushUserNote("   ")
        OverlayBus.pushUserNote("")
        assertTrue(OverlayBus.drainUserNotes().isEmpty())
    }

    @Test
    fun `clearPause 同时清掉用户意图与已停下的状态`() {
        OverlayBus.requestPause()
        OverlayBus.markPaused(true)
        OverlayBus.clearPause()
        assertFalse(OverlayBus.pauseRequested)
        assertFalse(OverlayBus.paused.value)
    }
}
