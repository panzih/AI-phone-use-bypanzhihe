package com.aiphone.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 语音识别接口的报文契约。
 *
 * 钉三件事：
 *   1. 端点拼接不会出现 `v1/v1`，也不会把完整 ASR 地址再拼一遍；
 *   2. multipart 的文件字段名/文件名/内容类型符合文档（`file` + WAV）；
 *   3. 失败原因能翻译成人话，而且**公网明文和缺 Key 在发出任何字节之前就被拦下**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsrClientTest {

    private val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4)

    @Test
    fun `端点_默认官方地址补v1`() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            AsrClient.endpoint("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            AsrClient.endpoint(" https://api.deepseek.com/ "),
        )
    }

    @Test
    fun `端点_已经带v1时不重复拼接`() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            AsrClient.endpoint("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            AsrClient.endpoint("https://api.deepseek.com/v1/tools/asr"),
        )
    }

    @Test
    fun `端点_空地址回落到官方`() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            AsrClient.endpoint("   "),
        )
    }

    @Test
    fun `multipart_字段名文件名与内容类型符合文档`() {
        val boundary = "test-boundary"
        val body = AsrClient.buildMultipart(wav, boundary)
        val text = body.toString(Charsets.ISO_8859_1)

        assertTrue(
            "文件字段必须是 file，文件名用 .wav：$text",
            text.startsWith(
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n"
            ),
        )
        assertTrue("必须有结束边界：$text", text.endsWith("\r\n--$boundary--\r\n"))

        val dataStart = text.indexOf("\r\n\r\n") + 4
        assertEquals(
            "文件内容必须原样跟在头后面",
            wav.toList(),
            body.copyOfRange(dataStart, dataStart + wav.size).toList(),
        )
    }

    @Test
    fun `响应_解析文本`() {
        val json = """{"request_id":"abc","text":"上海今天天气怎么样？","duration_ms":3200}"""
        assertEquals("上海今天天气怎么样？", AsrClient.parseText(json))
    }

    @Test
    fun `响应_文本里的转义不丢`() {
        val json = """{"text":"他说：\"打开设置\"","duration_ms":900}"""
        assertEquals("他说：\"打开设置\"", AsrClient.parseText(json))
    }

    @Test
    fun `响应_空文本返回null`() {
        assertEquals(null, AsrClient.parseText("""{"text":"   "}"""))
        assertEquals(null, AsrClient.parseText("not json"))
    }

    @Test
    fun `错误_常见状态码翻成人话`() {
        assertTrue(AsrClient.describeError(413, "").contains("录音太大"))
        assertTrue(AsrClient.describeError(429, "").contains("并发请求超过上限"))
        assertTrue(AsrClient.describeError(503, "").contains("服务繁忙"))
        assertTrue(
            AsrClient.describeError(401, """{"error":{"message":"Invalid API key"}}""")
                .contains("Invalid API key"),
        )
    }

    @Test
    fun `缺Key_不发请求直接失败`() {
        val r = AsrClient.transcribe("https://api.deepseek.com", "", wav, false)
        assertTrue(r is AsrClient.Result.Fail)
        assertTrue((r as AsrClient.Result.Fail).message.contains("API Key"))
    }

    @Test
    fun `公网明文_默认在发送前拦截`() {
        val r = AsrClient.transcribe("http://example.com", "sk-test", wav, false)
        assertTrue("必须是失败：$r", r is AsrClient.Result.Fail)
        val msg = (r as AsrClient.Result.Fail).message
        assertTrue("必须说明是明文拦截：$msg", msg.contains("公网明文"))
        assertFalse("不能把音频发出去", msg.contains("没连上"))
    }

    @Test
    fun `空音频_不发请求`() {
        val r = AsrClient.transcribe("https://api.deepseek.com", "sk-test", ByteArray(0), false)
        assertTrue(r is AsrClient.Result.Fail)
        assertTrue((r as AsrClient.Result.Fail).message.contains("没有录到声音"))
    }
}
