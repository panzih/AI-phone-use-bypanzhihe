package com.aiphone.assistant.llm

import com.aiphone.assistant.data.ThinkingMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 免费通道请求体的字段策略。
 *
 * 上游对非标准字段做**严格校验**，多发一个字段、甚至是字段值不在它的白名单里，
 * 都会 400/429：
 *
 *  - 思考模式 OFF 多发 `thinking:{"type":"disabled"}` → 400 invalid request；
 *  - 消息带截图时多发 `image_url.detail:"original"` → 400（省掉字段才 200）；
 *  - 缺官方 CLI 系统提示 / 缺官方 13 工具 → 429（2026-10-28 单变量实测）。
 *
 * 这里钉的不是"打了什么"，而是"**绝对不打什么**"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeLaneBodyTest {

    /** 前两个字节是 JPEG 的魔数，够 [LlmClient] 的 mimeOf 认成 image/jpeg */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x00)

    @Before
    fun installAssets() {
        FreeLaneWire.installAssets(RuntimeEnvironment.getApplication())
    }

    private fun body(
        useFree: Boolean,
        thinking: ThinkingMode,
        image: ByteArray? = null,
        customEffort: String = "",
        // 免费通道默认用池子里真实存在的 id：能力表按 id 查，用假 id 会落到
        // 保守兜底（纯文本、无档位），测不到真实的档位映射
        model: String = if (useFree) "deepseek-v4.1-flash-free" else "test-model",
        system: String = "你是一个助手",
    ): JSONObject = LlmClient(
        LlmConfig(
            baseUrl = "https://api.example.com/v1",
            apiKey = "test-key",
            model = model,
            useFreeModel = useFree,
            thinking = thinking,
            customEffort = customEffort,
        )
    ).buildBody(system, listOf(ChatTurn(ChatTurn.USER, "打开设置", imageBytes = image)))

    // ---- 免费通道 ----

    @Test
    fun `免费通道_OFF档不带thinking字段`() {
        // 回归锚点：thinking:{type:disabled} 会让免费上游 400
        val b = body(useFree = true, thinking = ThinkingMode.OFF)
        assertFalse("免费通道的 OFF 档不能发 thinking 字段：$b", b.has("thinking"))
        // OFF 不是思考模式，temperature 照发
        assertTrue("OFF 档仍要带 temperature：$b", b.has("temperature"))
    }

    @Test
    fun `免费通道_LOW档只发effort不发thinking`() {
        // 免费池只认 reasoning_effort 一个表达，多发 thinking 对象是额外风险
        val b = body(useFree = true, thinking = ThinkingMode.LOW)
        assertFalse("免费通道不该带 thinking：$b", b.has("thinking"))
        assertEquals("low", b.getString("reasoning_effort"))
        assertFalse("思考模式不传 temperature：$b", b.has("temperature"))
    }

    @Test
    fun `免费通道_不认的档位降到模型认的`() {
        // nemotron-3.5-super 声明的档位最高到 xhigh，发 max 会被网关判 400 invalid_value
        val b = body(useFree = true, thinking = ThinkingMode.MAX, model = "nemotron-3.5-super-vl-free")
        assertEquals("xhigh", b.getString("reasoning_effort"))
    }

    @Test
    fun `免费通道_没有推理的模型一个档位都不发`() {
        // 认不出来的模型走保守兜底（无推理），带 reasoning_effort 会被拒
        val b = body(useFree = true, thinking = ThinkingMode.HIGH, model = "jev-1.13-free")
        assertFalse("无推理模型不能带 reasoning_effort：$b", b.has("reasoning_effort"))
        assertFalse("免费通道本来就不带 thinking：$b", b.has("thinking"))
    }

    @Test
    fun `免费通道_不看图的模型把图摘掉`() {
        // 收图片会直接报错的模型：与其整轮失败，不如退回纯文本（只靠控件树）
        val b = body(
            useFree = true,
            thinking = ThinkingMode.OFF,
            image = jpeg,
            model = "nemotron-3.5-lightning-free",
        )
        val content = b.getJSONArray("messages").getJSONObject(1).get("content")
        assertTrue("不看图的模型应该只发纯文本：$content", content is String)
    }

    @Test
    fun `免费通道_看图的模型照发图片`() {
        val b = body(useFree = true, thinking = ThinkingMode.OFF, image = jpeg, model = "glm-5.3-flash-free")
        val content = b.getJSONArray("messages").getJSONObject(1).get("content")
        assertTrue("看图的模型应该发图文数组：$content", content is org.json.JSONArray)
    }

    @Test
    fun `免费通道_默认档一个思考参数都不发`() {
        val b = body(useFree = true, thinking = ThinkingMode.DEFAULT)
        assertFalse(b.has("thinking"))
        assertFalse(b.has("reasoning_effort"))
    }

    @Test
    fun `免费通道_补齐官方13工具集`() {
        // 依据是 src/o2a/official-tools.js：13 个官方工具全量，chat 线带 strict:false。
        val b = body(useFree = true, thinking = ThinkingMode.OFF)
        assertTrue("免费通道必须流式", b.getBoolean("stream"))
        assertFalse("绝不能下发 tool_choice（v2 见到 none 就 403）：$b", b.has("tool_choice"))

        val tools = b.getJSONArray("tools")
        assertEquals("必须是官方 13 个工具", 13, tools.length())
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(FreeLaneWire.OFFICIAL_TOOL_ORDER, names)

        val byName = (0 until tools.length()).associate {
            val fn = tools.getJSONObject(it).getJSONObject("function")
            fn.getString("name") to fn
        }
        val read = byName.getValue("read")
        assertTrue(
            "read 的描述必须是官方原文",
            read.getString("description").startsWith(
                "Read the contents of a file or directory. Supports text files, images, and PDFs.",
            ),
        )
        val shell = byName.getValue("shell")
        assertTrue(
            "shell 的描述必须是官方原文",
            shell.getString("description").startsWith(
                "Execute a shell command and return its output. Commands run on Linux using bash.",
            ),
        )
        assertEquals(
            "chat 线的官方工具必须带 strict:false",
            false,
            read.getBoolean("strict"),
        )
    }

    @Test
    fun `免费通道_带测试用量的流式参数与默认输出上限`() {
        val b = body(useFree = true, thinking = ThinkingMode.OFF)
        assertTrue("必须让网关回 usage 帧", b.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals(32000, b.getInt("max_completion_tokens"))
    }

    @Test
    fun `免费通道_system必须有官方提示与env块`() {
        // 缺官方 CLI 提示（base prompt + <env> + skills）就是 429（2026-10-28 实测）。
        val b = body(useFree = true, thinking = ThinkingMode.OFF)
        val system = b.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue("必须有官方提示标记：${system.take(120)}", system.contains(FreeLaneWire.OFFICIAL_PROMPT_MARKER))
        assertTrue("必须有 <env>：$system", system.contains("<env>"))
        assertTrue("必须有工作目录：$system", system.contains("Working directory:"))
        assertTrue("必须有平台：$system", system.contains("Platform: android"))
        // 注入的是真工具，必须同时告诉模型别调
        assertTrue("必须带上占位说明：$system", system.contains(FreeLaneWire.PLACEHOLDER_NOTE))
        assertTrue("原有系统提示不能丢：$system", system.contains("你是一个助手"))
    }

    @Test
    fun `免费通道_已有官方提示就不再重复注入`() {
        val already = "${FreeLaneWire.OFFICIAL_PROMPT_MARKER} 之后的正文\n\n<env>\n  Working directory: /x\n  Platform: android\n</env>"
        val b = body(useFree = true, thinking = ThinkingMode.OFF, system = already)
        val system = b.getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals("官方提示只能出现一次：$system", 1, Regex("You are opencode").findAll(system).count())
        assertEquals("<env> 只能出现一次：$system", 1, Regex("<env>").findAll(system).count())
        assertTrue("占位说明仍要补：$system", system.contains(FreeLaneWire.PLACEHOLDER_NOTE))
    }

    @Test
    fun `自定义通道不掺标准背书`() {
        val b = body(useFree = false, thinking = ThinkingMode.OFF)
        val system = b.getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals("自定义通道的 system 必须原样", "你是一个助手", system)
        assertFalse("自定义通道不该有 tools", b.has("tools"))
        assertFalse("自定义通道不该有 stream_options", b.has("stream_options"))
    }

    @Test
    fun `免费通道会话id编码合法`() {
        // 网关校验的是编码不是形状：12 位 hex 必须是降序 ~(ts<<12|counter)，
        // 低 12 位落在 0xfc0–0xfff（counter 1–63）
        val id = FreeLaneWire.newSessionId()
        assertTrue("形状不对：$id", Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$").matches(id))
        val timePart = id.substring(4, 16)
        val low12 = timePart.substring(9).toInt(16)
        assertTrue("低 12 位必须在 0xfc0–0xfff，实际 ${low12.toString(16)}：$id", low12 in 0xfc0..0xfff)
    }

    // ---- 截图消息（图片形态） ----

    @Test
    fun `免费通道_截图消息不带detail字段`() {
        // 回归锚点：detail:"original" 会让部分免费上游 400
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
