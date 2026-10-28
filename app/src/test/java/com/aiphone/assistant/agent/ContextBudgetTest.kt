package com.aiphone.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文预算按模型窗口缩放（2026-10-27 评估 P1-5）。
 *
 * 固定 100K/120K 的问题：32K 模型先撞 API 上限，1M 模型在 12% 就被停下。
 */
class ContextBudgetTest {

    @Test
    fun `32K 窗口必须给输出留出空间`() {
        val b = AgentLimits.contextBudget(32_768, 4_096)
        assertEquals(32_768, b.window)
        assertTrue("stop 必须小于窗口", b.stopTokens < b.window)
        assertTrue("stop 要给输出留够 4K", b.stopTokens + 4_096 <= b.window)
        assertTrue("warn 必须早于 stop", b.warnTokens < b.stopTokens)
    }

    @Test
    fun `128K 窗口不会在 100K 附近就停`() {
        val b = AgentLimits.contextBudget(131_072, 32_768)
        assertTrue("stop 应明显大于旧的 120K", b.stopTokens > 80_000)
        assertTrue(b.stopTokens + 32_768 <= 131_072)
        assertTrue(b.warnTokens < b.stopTokens)
    }

    @Test
    fun `1M 窗口能用满大部分容量`() {
        val b = AgentLimits.contextBudget(1_048_576, 131_072)
        assertTrue("1M 窗口的 stop 应超过 80 万", b.stopTokens > 800_000)
        assertTrue(b.stopTokens <= 1_048_576)
        assertTrue(b.warnTokens > 600_000)
    }

    @Test
    fun `免费模型按目录窗口算，自定义模型有保守默认`() {
        val free = AgentLimits.contextBudgetFor(useFreeModel = true, modelName = "jev-1.13-free")
        assertEquals(32_768, free.window)

        val custom = AgentLimits.contextBudgetFor(useFreeModel = false, modelName = "unknown-model")
        assertEquals(AgentLimits.DEFAULT_CONTEXT_WINDOW, custom.window)
    }
}
