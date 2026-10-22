package com.aiphone.assistant.llm

import com.aiphone.assistant.data.ThinkingMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 免费通道请求体的字段策略。
 *
 * 上游对非标准字段做**严格校验**，多发一个字段、甚至是字段值不在它的白名单里，
 * 都会 400 —— 这不是假想的风险，2026-10-16 的线上事故就是这么来的：
 *
 *  - 思考模式 OFF 多发 `thinking:{"type":"disabled"}` → space-bunny-free 上游
 *    `400 [invalid_request_error] invalid request`；
 *  - 消息带截图时多发 `image_url.detail:"original"` → 同一个上游同样 400
 *    （改成 "auto" 或不带字段都 200；把 image_url 摊平成纯字符串也会 400）。
 *
 * 同一天的 8 模型 × 2 形态实测矩阵：这些字段值只有个别上游拒，
 * 所以策略是"免费通道只发最小公倍数，个性化字段一律不带"。
 *
 * 这里钉的不是"打了什么"，而是"**绝对不打什么**"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeLaneBodyTest {

    /** 前两个字节是 JPEG 的魔数，够 [LlmClient] 的 mimeOf 认成 image/jpeg */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x00)

    private fun body(
        useFree: Boolean,
        thinking: ThinkingMode,
        image: ByteArray? = null,
        customEffort: String = "",
    ): JSONObject = LlmClient(
        LlmConfig(
            baseUrl = "https://api.example.com/v1",
            apiKey = "test-key",
            model = "test-model",
            useFreeModel = useFree,
            thinking = thinking,
            customEffort = customEffort,
        )
    ).buildBody("你是一个助手", listOf(ChatTurn(ChatTurn.USER, "打开设置", imageBytes = image)))

    // ---- 免费通道 ----

    @Test
    fun `免费通道_OFF档不带thinking字段`() {
        // 回归锚点：thinking:{type:disabled} 会让 space-bunny-free 400
        val b = body(useFree = true, thinking = ThinkingMode.OFF)
        assertFalse("免费通道的 OFF 档不能发 thinking 字段：$b", b.has("thinking"))
        // OFF 不是思考模式，temperature 照发
        assertTrue("OFF 档仍要带 temperature：$b", b.has("temperature"))
    }

    @Test
    fun `免费通道_LOW档保留thinking与effort`() {
        val b = body(useFree = true, thinking = ThinkingMode.LOW)
        assertEquals("enabled", b.getJSONObject("thinking").getString("type"))
        assertEquals("low", b.getString("reasoning_effort"))
        assertFalse("思考模式不传 temperature：$b", b.has("temperature"))
    }

    @Test
    fun `免费通道_默认档一个思考参数都不发`() {
        val b = body(useFree = true, thinking = ThinkingMode.DEFAULT)
        assertFalse(b.has("thinking"))
        assertFalse(b.has("reasoning_effort"))
    }

    @Test
    fun `免费通道_gate三件套齐全`() {
        // 上游免密门禁：stream、tools（bash + read 空桩）、tool_choice，
        // 少任何一件都会被 403 拦在门外（实测）
        val b = body(useFree = true, thinking = ThinkingMode.OFF)
        assertTrue("免费通道必须流式", b.getBoolean("stream"))
        assertEquals("none", b.getString("tool_choice"))
        val tools = b.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals("bash", tools.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("read", tools.getJSONObject(1).getJSONObject("function").getString("name"))
    }

    // ---- 截图消息（图片形态） ----

    @Test
    fun `免费通道_截图消息不带detail字段`() {
        // 回归锚点：detail:"original"（App 默认）会让 space-bunny-free 400
        val b = body(useFree = true, thinking = ThinkingMode.OFF, image = jpeg)
        val url = imagePart(b)
        assertFalse("免费通道的图片不能带 detail：$b", url.has("detail"))
        assertTrue(
            "图片必须是 data: URL 的对象形态：$url",
            url.getString("url").startsWith("data:image/jpeg;base64,"),
        )
    }

    @Test
    fun `自定义通道_截图消息保留detail`() {
        // 自定义通道的 "original" 是有意义的（手机 UI 文字多，压图会糊），不能一起删
        val b = body(useFree = false, thinking = ThinkingMode.OFF, image = jpeg)
        assertEquals("original", imagePart(b).getString("detail"))
    }

    /** 取消息里 image_url 那个对象 */
    private fun imagePart(b: JSONObject): JSONObject {
        val content = b.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        return content.getJSONObject(1).getJSONObject("image_url")
    }

    // ---- 自定义通道 ----

    @Test
    fun `自定义通道_OFF档照发disabled`() {
        // DeepSeek / Kimi 这类自定义渠道上 disabled 是有意义的（明确关思考），
        // 不能顺手一起删 —— 免费通道的例外不能传染到这里
        val b = body(useFree = false, thinking = ThinkingMode.OFF)
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun `自定义通道_不掺免费通道契约`() {
        val b = body(useFree = false, thinking = ThinkingMode.OFF)
        assertFalse("自定义通道不能带 tool_choice", b.has("tool_choice"))
        assertFalse("自定义通道保持非流式", b.getBoolean("stream"))
    }

    // ---- 自定义思考强度（0.10.2）----

    @Test
    fun `自定义档把用户填的 effort 原样发出去`() {
        val b = body(useFree = false, thinking = ThinkingMode.CUSTOM, customEffort = " medium ")
        assertEquals("enabled", b.getJSONObject("thinking").getString("type"))
        assertEquals("medium", b.getString("reasoning_effort"))
    }

    @Test
    fun `自定义档留空时一个思考字段都不发`() {
        // 留空的语义就是"用服务商默认"，绝不能发空串（那是最容易被 400 的形态）
        val b = body(useFree = false, thinking = ThinkingMode.CUSTOM, customEffort = "   ")
        assertFalse("留空不该发 reasoning_effort：$b", b.has("reasoning_effort"))
        assertFalse("留空不该发 thinking：$b", b.has("thinking"))
    }

    @Test
    fun `自定义档在免费通道也照发_填了才发`() {
        val b = body(useFree = true, thinking = ThinkingMode.CUSTOM, customEffort = "low")
        assertEquals("low", b.getString("reasoning_effort"))
    }
}
