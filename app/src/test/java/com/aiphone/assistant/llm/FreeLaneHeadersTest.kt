package com.aiphone.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 免费通道请求头的契约。
 *
 * 断言依据不是文档，而是**上游插件源码** `dsh-our-free-model/src/o2a/headers.js`
 * （2.8.3 移植的 opencode2api v1.4.7）。历史上三次翻车都是照着 README 写而没照
 * 代码写：v1 的 `x-opencode-request`、v2 的 `global` project、以及缺官方提示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeLaneHeadersTest {

    private fun headers(session: String = FreeLaneWire.newSessionId()): Map<String, String> =
        FreeLaneWire.headersFor(session, stream = true).mapKeys { it.key.lowercase() }

    @Test
    fun `端点与插件一致`() {
        assertEquals(
            "https://opencode.ai/inference/openai/v1/chat/completions",
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
        assertEquals("整串匹配，改一个字符就不是 v2 客户端", "opencode/latest/2.2.3/cli", h["user-agent"])
        assertEquals("cli", h["x-opencode-client"])
        assertEquals(session, h["x-opencode-session"])
        assertEquals(session, h["x-opencode-session-id"])
        assertEquals(session, h["x-session-affinity"])
        assertEquals(session, h["x-session-id"])
        // v1 专用头，带上就是 403
        assertFalse("不能带 x-opencode-request：$h", h.containsKey("x-opencode-request"))
    }

    @Test
    fun `project 是 40 位 hex 且由 session 派生`() {
        val session = FreeLaneWire.newSessionId()
        val project = headers(session)["x-opencode-project"].orEmpty()
        assertEquals("必须是 40 位小写 hex（共享的 global 会预烧额度）", 40, project.length)
        assertTrue("只能是 [0-9a-f]：$project", Regex("^[0-9a-f]{40}$").matches(project))
        assertNotEquals("global", project)
        assertEquals("同一会话必须落同一个配额桶", project, FreeLaneWire.projectIdFor(session))
    }

    @Test
    fun `带 b3 与 traceparent 且形状合法`() {
        val h = headers()
        val traceparent = h["traceparent"].orEmpty()
        assertTrue("traceparent 形态：$traceparent", Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(traceparent))
        val b3 = h["b3"].orEmpty()
        assertTrue("b3 形态：$b3", Regex("^[0-9a-f]{32}-[0-9a-f]{16}-1-[0-9a-f]{16}$").matches(b3))
        // 每请求新 trace：两次调用不应相同
        assertNotEquals(traceparent, headers()["traceparent"])
    }

    @Test
    fun `accept 是通配符`() {
        assertEquals("*/*", headers()["accept"])
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

    @Test
    fun `session 时间戳落在近期窗口`() {
        // 纯哈希供日期会落到 1970，上游直接 429；慢钟铸造必须通过可接受性检查。
        val id = FreeLaneWire.newSessionId()
        assertTrue("新铸的 session 必须通过时间可接受性检查：$id", FreeLaneWire.isOfficialSession(id))
    }

    @Test
    fun `Kilo_端点与免密头`() {
        assertEquals(
            "https://ai-gateway.kilo.ai/api/v1/chat/completions",
            KiloWire.chatUrl(),
        )
        assertEquals("https://ai-gateway.kilo.ai/api/v1/models", KiloWire.modelsUrl())
        val h = KiloWire.headersFor(stream = true).mapKeys { it.key.lowercase() }
        assertEquals("Kilo 免费池不能带鉴权", null, h["authorization"])
        assertEquals("dsh-our-free-model", h["user-agent"])
        assertEquals("text/event-stream", h["accept"])
        assertEquals("https://kilo.ai/", h["http-referer"])
        assertEquals("Our Free Model", h["x-title"])
    }
}
