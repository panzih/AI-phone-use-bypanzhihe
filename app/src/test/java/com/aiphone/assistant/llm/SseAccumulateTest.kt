package com.aiphone.assistant.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 免费通道的 SSE 累积器。
 *
 * 这一块有两条硬要求：
 *
 *  1. `content` 按顺序拼起来（正常回答）；
 *  2. 模型只回 `reasoning_content` 时**必须兜底** —— 免费池里有"固定思考、
 *     关不掉"的模型（网关会回 1210），它们的全部输出都可能落在
 *     `reasoning_content`、`content` 留空。以前只拼 content，整个回复会被
 *     当成空响应触发三连重发，最后报"模型返回了空内容"，从错误里完全
 *     看不出是这回事。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SseAccumulateTest {

    private val client = LlmClient(
        LlmConfig(
            baseUrl = "https://example.com/v1",
            apiKey = "k",
            model = "m",
            useFreeModel = true,
        )
    )

    /** 把若干段 delta JSON 拼成 SSE 原文，并以 [DONE] 收尾 */
    private fun sse(vararg deltas: String): String =
        deltas.joinToString("\n") { "data: {\"choices\":[{\"delta\":$it}]}" } + "\ndata: [DONE]\n"

    private fun contentOf(raw: String): String =
        JSONObject(client.accumulateSse(raw))
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

    @Test
    fun `正常回答按顺序拼接`() {
        // 模拟模型分两段吐出 {"thought":"ok"}
        val raw = sse(
            """{"content":"{\"thought\":\""}""",
            """{"content":"ok\"}"}""",
        )
        assertEquals("""{"thought":"ok"}""", contentOf(raw))
    }

    @Test
    fun `只有思维链时用它兜底`() {
        // 固定思考的模型：全部输出都在 reasoning_content，content 空
        val raw = sse(
            """{"reasoning_content":"想一下"}""",
            """{"reasoning_content":"再想一下"}""",
        )
        assertEquals("想一下再想一下", contentOf(raw))
    }

    @Test
    fun `思维链和回答都有时以回答为准`() {
        val raw = sse(
            """{"reasoning_content":"这是推理"}""",
            """{"content":"这是回答"}""",
        )
        assertEquals("这是回答", contentOf(raw))
    }

    @Test
    fun `垃圾行与结束标记被跳过`() {
        val raw = "data: not-json\n" +
            ": keep-alive\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n" +
            "data: [DONE]\n"
        assertEquals("A", contentOf(raw))
    }

    @Test
    fun `content 为 JSON null 的 delta 不产生字面 null`() {
        // 网关从 Anthropic/Gemini 归一成 OpenAI 流时，推理段常带 "content": null。
        // Android org.json 的 optString 对 JSON null 返回字符串 "null"（不是空串），
        // 不拦的话回答就是 "nullnull…"，ActionParser 永远解析不出 JSON ——
        // 表现成"免费模型在 App 里完全不能用"，而模型本身是好的。
        val raw = sse(
            """{"content":null,"reasoning_content":"先想"}""",
            """{"content":null,"reasoning_content":"再想"}""",
            """{"content":"答案"}""",
        )
        assertEquals("回答非空时以回答为准", "答案", contentOf(raw))
    }

    @Test
    fun `content 全为 null 且只有思维链时兜底`() {
        val raw = sse(
            """{"content":null,"reasoning_content":"想"}""",
            """{"content":null,"reasoning_content":"还想"}""",
        )
        assertEquals("想还想", contentOf(raw))
    }

    @Test
    fun `模型只调占位工具时回灌提示而不是空响应`() {
        // read/shell 是网关门禁逼着声明的真工具；模型不理会占位说明直接去调时，
        // 老逻辑攒出空内容 → 三连重发 → 任务失败。现在必须回一句提示让它自纠。
        val raw = sse(
            """{"tool_calls":[{"index":0,"id":"call_1","type":"function",""" +
                """"function":{"name":"read","arguments":"{\"path\":\"/sdcard\"}"}}]}""",
        )
        val text = contentOf(raw)
        assertTrue("必须提示占位工具不可调：$text", text.contains("占位工具"))
    }
}
