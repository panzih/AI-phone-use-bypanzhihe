package com.aiphone.assistant.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这套模型配置能不能发请求」的唯一判据。
 *
 * 回归锚点（2026-10-25 线上问题）：免费模式不需要 API Key，入口却只判
 * `apiKey.isBlank()` —— 新用户点发送直接撞上 Key 拦截；更糟的是拦截发生在
 * 悬浮窗 startForegroundService 之后，runTask 立刻 return 导致服务被秒停，
 * 系统抛 ForegroundServiceDidNotStartInTimeException，表现成「点发送就崩溃」。
 *
 * 所以入口和 [LlmClient.chat] 必须共用 [LlmConfig.missingApiKey]；
 * 这里把四种组合钉死，防止再写回「只认 Key」。
 */
class ModelConfigTest {

    @Test
    fun `免费模式_没有 Key 也能用`() {
        assertFalse(LlmConfig.missingApiKey(useFreeModel = true, apiKey = ""))
        assertFalse(LlmConfig.missingApiKey(useFreeModel = true, apiKey = "   "))
    }

    @Test
    fun `免费模式_有 Key 也不受影响`() {
        assertFalse(LlmConfig.missingApiKey(useFreeModel = true, apiKey = "sk-test"))
    }

    @Test
    fun `自定义模式_空 Key 必须拦下`() {
        assertTrue(LlmConfig.missingApiKey(useFreeModel = false, apiKey = ""))
        assertTrue(LlmConfig.missingApiKey(useFreeModel = false, apiKey = "   "))
    }

    @Test
    fun `自定义模式_填了 Key 就放行`() {
        assertFalse(LlmConfig.missingApiKey(useFreeModel = false, apiKey = "sk-test"))
    }
}
