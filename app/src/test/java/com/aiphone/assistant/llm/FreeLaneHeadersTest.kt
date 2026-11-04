package com.aiphone.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 免费通道请求头的契约。
 *
 * 断言依据不是文档，而是**上游插件源码** `dsh-our-free-model/src/upstream.js`
 * 的 `gatewayHeaders()` —— 那个插件是这份免密通道的长期维护方，它怎么写就是
 * 上游认什么。历史上两次翻车都是因为照着 README 写而没照代码写。
 *
 * 最容易搞反的三条（前两版就错在这）：
 * - **必须带 `Authorization: Bearer public`** —— 不发不是"更干净"，是过不了鉴权；
 * - **`x-opencode-project` 是固定字符串 `global`**，不是 40 位 hex、也不是 `prj_`；
 * - **不发 `b3` / `traceparent`** —— 官方客户端会带，这个插件不带，多带无用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeLaneHeadersTest {

    private fun headers(session: String = FreeLaneWire.newSessionId()): Map<String, String> =
        FreeLaneWire.headersFor(session, stream = true).mapKeys { it.key.lowercase() }

    @Test
    fun `端点与插件一致`() {
        assertEquals(
            "https://opencode.ai/inference/v1/chat/completions",
            FreeLaneWire.chatUrl(),
        )
        assertEquals("https://opencode.ai/inference/v1/models", FreeLaneWire.modelsUrl())
    }

    @Test
    fun `必须带 Bearer public`() {
        val h = headers()
        assertEquals("公开池的唯一凭据就是这么发的", "Bearer public", h["authorization"])
    }

    @Test
    fun `客户端指纹与四个 session 头`() {
        val session = FreeLaneWire.newSessionId()
        val h = headers(session)
        assertEquals("整串匹配，改一个字符就不是 v2 客户端", "opencode/latest/2.0.26/cli", h["user-agent"])
        assertEquals("cli", h["x-opencode-client"])
        assertEquals(session, h["x-opencode-session"])
        assertEquals(session, h["x-opencode-session-id"])
        assertEquals(session, h["x-session-affinity"])
        assertEquals(session, h["x-session-id"])
        // v1 专用头，带上就是 403
        assertFalse("不能带 x-opencode-request：$h", h.containsKey("x-opencode-request"))
    }

    @Test
    fun `project 是 global`() {
        assertEquals("global", headers()["x-opencode-project"])
    }

    @Test
    fun `不带 b3 与 traceparent`() {
        val h = headers()
        assertFalse("插件不发 b3：$h", h.containsKey("b3"))
        assertFalse("插件不发 traceparent：$h", h.containsKey("traceparent"))
    }

    @Test
    fun `session 铸造后不再变`() {
        // 上游按 session 记账：同一个安装必须一直用同一个 id，
        // 每次请求换新 id 会被当成刷额度（实测全 429）。
        val first = FreeLaneWire.session()
        assertEquals("session 必须复用", first, FreeLaneWire.session())
        assertTrue("形状必须是 ses_ + 12 hex + 14 base62：$first", Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$").matches(first))
    }

    @Test
    fun `session 低 12 位是反相计数器`() {
        val session = FreeLaneWire.newSessionId()
        val hex = session.removePrefix("ses_").take(12)
        val low = hex.takeLast(3).toInt(16)
        assertTrue("低 12 位必须落在 0xfc0–0xfff（解码出很小的 seq），实际 $hex", low in 0xfc0..0xfff)
    }
}
