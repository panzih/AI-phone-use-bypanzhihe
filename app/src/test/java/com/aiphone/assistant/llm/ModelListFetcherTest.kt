package com.aiphone.assistant.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关目录里"哪些算免费通道能选"。
 *
 * 实测 `/zen/v1/models` 现在返回 85 个条目 —— 免费池之外还有整份**付费目录**
 * （claude / gpt / gemini / kimi / grok …）。这条过滤错了，用户会在免费模式下
 * 选到付费模型，然后拿到一个看不懂的报错。
 */
class ModelListFetcherTest {

    @Test
    fun `免费池的模型留下`() {
        listOf(
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "beam-free",
            "nemotron-3.5-super-vl-free",
            "space-bunny-free",
            "big-pickle",
        ).forEach { assertTrue(it, ModelListFetcher.keepFreeModel(it)) }
    }

    @Test
    fun `付费目录和测试占位被剔掉`() {
        listOf(
            "test",                 // 目录里真实存在的占位条目
            "claude-opus-5-5",
            "gpt-6-astra",
            "gemini-4-argon",
            "deepseek-v4-pro",      // 同为 DeepSeek，但不带 -free
            "glm-5.3",
            "jev-1.13",             // 注意：jev-1.13-free 是免费的，这个不是
        ).forEach { assertFalse(it, ModelListFetcher.keepFreeModel(it)) }
    }

    /**
     * 0.16.0：地址写错时**先说人话**。
     *
     * 现场：用户把 `http://10.0.2.2:8899` 里的冒号打成了分号，界面上显示的是
     * `获取失败: no protocol: http://10.0.2.2;8899/v1/models` —— 一句英文
     * Java 异常。现在要在发请求之前就拦住，并指出该看哪里。
     */
    @Test
    fun `地址没有 scheme 时给中文提示，而不是 Java 异常`() {
        val e = assertThrows(IllegalStateException::class.java) {
            ModelListFetcher.fetch("10.0.2.2:8899", "sk-test")
        }
        val msg = e.message.orEmpty()
        assertTrue(msg, msg.contains("http://"))
        assertFalse(msg, msg.contains("no protocol"))
        assertFalse(msg, msg.contains("MalformedURL"))
    }

    @Test
    fun `冒号和斜杠混进中文标点也会被拦住`() {
        val e = assertThrows(IllegalStateException::class.java) {
            ModelListFetcher.fetch("http：//192.168.3.11:8899", "sk-test")
        }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("格式不对"))
    }
}
