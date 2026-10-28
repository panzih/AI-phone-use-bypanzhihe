package com.aiphone.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置浏览器地址白名单（整改清单第 9 项）。
 *
 * 两边的代价不对称：
 *   - **漏放**一个 `file://` / `intent://`，模型（或被它读到的恶意页面）
 *     就能拉起任意组件、读本地文件；
 *   - **误拒**一个正常网址，只是模型换一个写法重试。
 *
 * 所以用例以"必须拒绝"为主，正常网址只覆盖常见的省略协议头写法。
 * 纯 Kotlin，不需要 Robolectric。
 */
class BrowserUrlTest {

    private fun ok(raw: String): String =
        (BrowserUrl.normalize(raw) as BrowserUrl.Result.Ok).url

    private fun rejected(raw: String): String =
        (BrowserUrl.normalize(raw) as BrowserUrl.Result.Rejected).reason

    @Test
    fun `http 和 https 原样通过`() {
        assertEquals("https://example.com/a?b=1#c", ok("https://example.com/a?b=1#c"))
        assertEquals("http://192.168.1.10:11434/v1", ok("http://192.168.1.10:11434/v1"))
        // 协议头大小写不敏感，统一成小写
        assertEquals("https://example.com/", ok("HTTPS://example.com/"))
    }

    @Test
    fun `省略协议头时补 https`() {
        assertEquals("https://bilibili.com", ok("bilibili.com"))
        assertEquals("https://example.com:8080/a", ok("example.com:8080/a"))
        assertEquals("https://example.com", ok("//example.com"))
        assertEquals("https://example.com", ok("https:example.com"))
        assertEquals("http://example.com", ok("http:/example.com"))
    }

    @Test
    fun `本机和局域网省略协议头时补 http`() {
        assertEquals("http://192.168.1.1:8080", ok("192.168.1.1:8080"))
        assertEquals("http://localhost:3000", ok("localhost:3000"))
        assertEquals("http://nas.local", ok("nas.local"))
    }

    @Test
    fun `危险 scheme 一律拒绝`() {
        listOf(
            "file:///sdcard/secret.txt",
            "content://com.android.contacts/data/1",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(document.cookie)",
            "data:text/html,<script>alert(1)</script>",
            "chrome://settings",
            "ftp://example.com/a",
            "blob:https://example.com/123",
            "myapp://open",
            "myapp:do-something",
        ).forEach { raw ->
            val reason = rejected(raw)
            assertTrue("$raw 必须被拒绝，实际原因：$reason", reason.contains("只允许 http/https"))
        }
    }

    @Test
    fun `缺少主机名或带凭据的地址拒绝`() {
        listOf(
            "https://",
            "https:///path",
            "https://:8080",
            "https://user:pass@example.com",
        ).forEach { raw ->
            assertTrue("$raw 必须被拒绝", BrowserUrl.normalize(raw) is BrowserUrl.Result.Rejected)
        }
    }

    @Test
    fun `空白和控制字符拒绝`() {
        listOf(
            "https://exa mple.com",
            "https://exa\nmple.com",
            "https://exa\tmple.com",
            "https://example.com\\@evil.com",
        ).forEach { raw ->
            assertTrue("$raw 必须被拒绝", BrowserUrl.normalize(raw) is BrowserUrl.Result.Rejected)
        }
    }

    @Test
    fun `空串表示空白页`() {
        assertEquals("", ok(""))
        assertEquals("", ok("   "))
    }

    @Test
    fun `导航层只放行 http https 和 about blank`() {
        assertTrue(BrowserUrl.isAllowedPage("https://example.com"))
        assertTrue(BrowserUrl.isAllowedPage("http://192.168.1.1"))
        assertTrue(BrowserUrl.isAllowedPage("about:blank"))
        assertFalse(BrowserUrl.isAllowedPage("file:///sdcard/x"))
        assertFalse(BrowserUrl.isAllowedPage("intent://x"))
        assertFalse(BrowserUrl.isAllowedPage("javascript:alert(1)"))
    }
}
