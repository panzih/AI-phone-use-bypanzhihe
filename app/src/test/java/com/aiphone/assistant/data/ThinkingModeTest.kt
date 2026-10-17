package com.aiphone.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考档位的两个约定：
 *
 *  1. 老配置存过的 id 必须还能读回来（fromId 的兜底不能变）；
 *  2. 界面上显示的「接口参数」必须和真实请求一致 —— 免费通道的 OFF 不发字段，
 *     这里就要如实显示"不发"，不能让用户看着说明和抓包对不上。
 */
class ThinkingModeTest {

    @Test
    fun `老配置的id都能读回来`() {
        assertEquals(ThinkingMode.OFF, ThinkingMode.fromId("off"))
        assertEquals(ThinkingMode.MAX, ThinkingMode.fromId("max"))
        assertEquals(ThinkingMode.DEFAULT, ThinkingMode.fromId("default"))
        assertEquals(ThinkingMode.DEFAULT, ThinkingMode.fromId(null))
        assertEquals(ThinkingMode.DEFAULT, ThinkingMode.fromId("随便写的"))
    }

    @Test
    fun `免费通道的OFF显示为不发`() {
        assertEquals("不发（免费上游不接受 disabled）", ThinkingMode.OFF.apiFor(freeLane = true))
        assertEquals("thinking.type = disabled", ThinkingMode.OFF.apiFor(freeLane = false))
    }

    @Test
    fun `其余档位两个通道显示一致`() {
        listOf(ThinkingMode.LOW, ThinkingMode.HIGH, ThinkingMode.MAX, ThinkingMode.DEFAULT)
            .forEach { assertEquals(it.name, it.api, it.apiFor(freeLane = true)) }
    }

    @Test
    fun `默认档的说明只讲接口事实`() {
        // 以前那句「Kimi 默认 balance」没有任何依据 —— 这次一并删掉
        val note = ThinkingMode.DEFAULT.note
        assertFalse("不能再出现没依据的 Kimi 说法：$note", note.contains("balance"))
        assertFalse("不能再出现没依据的 Kimi 说法：$note", note.contains("Kimi"))
        assertTrue("要写明 DeepSeek 文档的事实：$note", note.contains("DeepSeek"))
        // 文档原文：思考默认开启、默认 effort = high
        assertTrue("要写明默认档等于 high：$note", note.contains("high"))
    }
}
