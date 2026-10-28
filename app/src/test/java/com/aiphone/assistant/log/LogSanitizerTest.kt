package com.aiphone.assistant.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出脱敏（整改清单第 10 项）。
 *
 * 只钉"确定能识别"的几类凭据；自由文本和截图不可能靠正则保证干净，
 * 这一点由 manifest 和导出前提示如实告知。
 */
class LogSanitizerTest {

    @Test
    fun `sk 开头的 API Key 被抹掉`() {
        val out = LogSanitizer.redact("key=sk-abcdefghijklmnop1234 end")
        assertTrue(out.contains("sk-***"))
        assertFalse(out.contains("abcdefghijklmnop1234"))
    }

    @Test
    fun `Authorization Bearer 被抹掉`() {
        val out = LogSanitizer.redact("Authorization: Bearer abcdefghijklmnopqrstuvwxyz")
        assertTrue(out.contains("Bearer ***"))
        assertFalse(out.contains("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `JSON 里的 key 和 password 被抹掉且保留引号`() {
        val out = LogSanitizer.redact(
            """{"api_key":"sk-abcdefghijklmnop","password":"hunter2","model":"deepseek"}"""
        )
        assertTrue(out.contains(""""api_key":"***""""))
        assertTrue(out.contains(""""password":"***""""))
        assertTrue("普通字段不能动", out.contains(""""model":"deepseek""""))
    }

    @Test
    fun `JSON 里的数字验证码被抹掉`() {
        val out = LogSanitizer.redact("""{"otp":123456,"ok":true}""")
        assertTrue(out.contains(""""otp":"***""""))
        assertFalse(out.contains("123456"))
        assertTrue("布尔值不能动", out.contains(""""ok":true"""))
    }

    @Test
    fun `纯文本的 password 和验证码被抹掉`() {
        val out = LogSanitizer.redact(
            "password=hunter2\n验证码：123456\n密码是 hunter3\n其他内容保留"
        )
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("hunter3"))
        assertFalse(out.contains("123456"))
        assertTrue(out.contains("其他内容保留"))
    }

    @Test
    fun `普通日志不被误伤`() {
        val text = "HTTP 200，code=200，今天天气不错，model=deepseek"
        assertEquals(text, LogSanitizer.redact(text))
    }
}
