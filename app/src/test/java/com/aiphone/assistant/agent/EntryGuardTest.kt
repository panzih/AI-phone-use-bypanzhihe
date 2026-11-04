package com.aiphone.assistant.agent

import com.aiphone.assistant.llm.LlmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口层兜底测试（ROADMAP §1「测试兜底」，2026-11）。
 *
 * 背景：0.10.6 的「点发送就崩」——模型配置检查放到了 startForegroundService
 * 之后，runTask 提前 return 时 finally 立刻 stopService，悬浮窗服务还没
 * startForeground 就被停。当时 237 例单测全绿，因为现有测试都在测纯函数，
 * 入口层没人测。把入口的两条**可测判定**抽成 [EntryGuard] 之后在这里钉住；
 * 结构性约定（提前 return 的检查必须在起服务之前）写在 EntryGuard 的
 * KDoc 里，靠评审保住。
 */
class EntryGuardTest {

    // ---------------------------------------------------------------
    // 1. 模型配置：免费/自定义 × 有 Key/无 Key 四种组合
    //    （入口 submit/runTask/learnRecording 与 LlmClient.chat 共用这一份判据）
    // ---------------------------------------------------------------

    @Test
    fun `免费模式_没填Key_也能发任务`() {
        // 2026-10-25 线上问题：入口只认 Key，把免费模式的新用户拦在门外
        assertFalse(LlmConfig.missingApiKey(useFreeModel = true, apiKey = ""))
    }

    @Test
    fun `免费模式_填了Key_也能发任务`() {
        assertFalse(LlmConfig.missingApiKey(useFreeModel = true, apiKey = "sk-xxx"))
    }

    @Test
    fun `自定义模式_没填Key_必须拦下`() {
        assertTrue(LlmConfig.missingApiKey(useFreeModel = false, apiKey = ""))
        assertTrue("空白 Key 等同没填", LlmConfig.missingApiKey(useFreeModel = false, apiKey = "   "))
    }

    @Test
    fun `自定义模式_填了Key_放行`() {
        assertFalse(LlmConfig.missingApiKey(useFreeModel = false, apiKey = "sk-xxx"))
    }

    // ---------------------------------------------------------------
    // 2. 任务中途发消息：不许静默吞掉（ROADMAP §3 的行为契约）
    // ---------------------------------------------------------------

    @Test
    fun `不忙时返回null_走正常新任务流程`() {
        assertNull(EntryGuard.busyInput(isRunning = false, isPaused = false))
        assertNull(EntryGuard.busyInput(isRunning = false, isPaused = true))
    }

    @Test
    fun `暂停中发的消息并进下一轮`() {
        assertEquals(
            EntryGuard.BusyInput.MERGE_AS_NOTE,
            EntryGuard.busyInput(isRunning = true, isPaused = true),
        )
    }

    @Test
    fun `运行中发的消息必须给提示_绝不静默丢弃`() {
        // 回归钉子：老行为是直接 return —— 输入没有任何去向、也没有提示，
        // 用户以为按坏了。现在必须返回 PROMPT_BUSY，由界面明示原因
        assertEquals(
            EntryGuard.BusyInput.PROMPT_BUSY,
            EntryGuard.busyInput(isRunning = true, isPaused = false),
        )
        assertNotNull(EntryGuard.BusyInput.PROMPT_BUSY)
    }
}
