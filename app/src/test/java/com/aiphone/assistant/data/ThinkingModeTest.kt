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

    // ---------------------------------------------------------------
    // levelsFor：滑块上摆哪几档，必须是对这个服务商**真能发**的
    // ---------------------------------------------------------------

    @Test
    fun `DeepSeek 的滑块里没有 medium`() {
        // 文档只认 low/high/max；摆一档发过去 400 的，比不摆更糟
        val levels = ThinkingMode.levelsFor("https://api.deepseek.com", freeLane = false)
        assertFalse(levels.contains(ThinkingMode.MEDIUM))
        assertTrue(levels.contains(ThinkingMode.MAX))
    }

    @Test
    fun `免费通道不摆 max`() {
        // 上游各异，max 只是"被接受"，不代表真按它推理；不给用户这个错觉
        val levels = ThinkingMode.levelsFor("https://opencode.ai/zen", freeLane = true)
        assertFalse(levels.contains(ThinkingMode.MAX))
        assertTrue(levels.contains(ThinkingMode.HIGH))
    }

    @Test
    fun `其他服务商给 medium`() {
        val levels = ThinkingMode.levelsFor("https://api.openai.com", freeLane = false)
        assertTrue(levels.contains(ThinkingMode.MEDIUM))
        assertFalse(levels.contains(ThinkingMode.MAX))
    }

    @Test
    fun `默认档永远在最右`() {
        listOf(
            ThinkingMode.levelsFor("https://api.deepseek.com", false),
            ThinkingMode.levelsFor("https://opencode.ai/zen", true),
            ThinkingMode.levelsFor("https://whatever.example", false),
        ).forEach { levels ->
            assertEquals(ThinkingMode.DEFAULT, levels.last())
            assertEquals("最右一档是默认，用户不调就是它", levels.last().id, "default")
        }
    }
}
