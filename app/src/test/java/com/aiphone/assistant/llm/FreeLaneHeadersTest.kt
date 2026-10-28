package com.aiphone.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * 免费通道请求头的契约。
 *
 * 这一组断言对应的不是"文档"，而是**抓下来的官方 2.2.0 客户端实包**：
 * 把 `OpenCode.app` 自带的 CLI 的 provider baseURL 指到本地代理，让它自己发
 * 一次 chat/completions，逐项抄下头部。所以这里断言的是"和官方逐项一致"，
 * 不是"我觉得应该这样"。
 *
 * 最容易搞反的两条：
 * - **不能带 `Authorization`** —— 网关按客户端身份识别，加了反而被当外部调用；
 * - **`x-opencode-project` 是 40 位 hex**，不是 `global` 也不是 `prj_` 前缀。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeLaneHeadersTest {

    private fun headers(session: String = FreeLaneWire.newSessionId()): Map<String, String> {
        val conn = URL(FreeLaneWire.chatUrl()).openConnection() as HttpURLConnection
        FreeLaneWire.applyHeaders(conn, session, stream = true)
        return conn.requestProperties.entries.associate { it.key.lowercase() to it.value.first() }
    }

    @Test
    fun `端点与官方一致`() {
        assertEquals(
            "https://opencode.ai/inference/openai/v1/chat/completions",
            FreeLaneWire.chatUrl(),
        )
        // 模型目录仍在另一条路径上（openai/v1 那个是 404）
        assertEquals("https://opencode.ai/inference/v1/models", FreeLaneWire.modelsUrl())
    }

    @Test
    fun `不带 Authorization`() {
        val h = headers()
        assertFalse("免费通道不能带 Authorization：$h", h.containsKey("authorization"))
        assertNull(h["authorization"])
    }

    @Test
    fun `客户端指纹与四个 session 头`() {
        val session = FreeLaneWire.newSessionId()
        val h = headers(session)
        assertEquals("opencode/latest/2.2.0/cli", h["user-agent"])
        assertEquals("cli", h["x-opencode-client"])
        assertEquals(session, h["x-opencode-session"])
        assertEquals(session, h["x-opencode-session-id"])
        assertEquals(session, h["x-session-affinity"])
        assertEquals(session, h["x-session-id"])
        // v1 专用头，带上就是 403
        assertFalse("不能带 x-opencode-request：$h", h.containsKey("x-opencode-request"))
    }

    @Test
    fun `project 是 40 位 hex 且稳定`() {
        val project = FreeLaneWire.projectId
        assertTrue("project 必须是 40 位 hex：$project", Regex("^[0-9a-f]{40}$").matches(project))
        assertEquals("project 必须每次一样（换值等于换个额度桶）", project, FreeLaneWire.projectId)
        assertEquals(project, headers()["x-opencode-project"])
    }

    @Test
    fun `带 b3 与 traceparent 追踪头`() {
        val h = headers()
        val b3 = h["b3"]!!.split("-")
        assertEquals("b3 是 traceId-spanId-sampled-parentSpanId", 4, b3.size)
        assertEquals(32, b3[0].length)
        assertEquals(16, b3[1].length)
        assertEquals("1", b3[2])

        val tp = h["traceparent"]!!.split("-")
        assertEquals("traceparent 是 version-traceId-spanId-flags", 4, tp.size)
        assertEquals("00", tp[0])
        assertEquals(32, tp[1].length)
        assertEquals(16, tp[2].length)
        // 两个头必须指向同一条 trace
        assertEquals("b3 与 traceparent 必须是同一条 trace", b3[0], tp[1])
    }
}
