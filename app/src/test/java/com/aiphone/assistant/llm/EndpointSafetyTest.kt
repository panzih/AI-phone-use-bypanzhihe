package com.aiphone.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EndpointSafety] 的判据。
 *
 * 两边的代价不对称，所以两边的用例都要有：
 *   - **漏判**（公网 HTTP 没报）→ API Key 在用户不知情的情况下明文过网；
 *   - **误判**（局域网地址被报）→ 每次用本地模型都看到一条假警告，
 *     久了用户对所有警告免疫，真警告也会被忽略。
 *
 * 纯 Kotlin，不需要 Robolectric。
 */
class EndpointSafetyTest {

    // ---------- 必须警告：公网明文 HTTP ----------

    @Test
    fun `公网http_警告`() {
        listOf(
            "http://api.example.com",
            "http://api.example.com/v1",
            "http://1.2.3.4:8000/v1",
            "http://my-vps.duckdns.org:11434",
            "HTTP://API.Example.COM", // 大小写不敏感
        ).forEach { assertNotNull(it, EndpointSafety.cleartextWarning(it)) }
    }

    @Test
    fun `https_一律不警告`() {
        listOf("https://api.deepseek.com", "https://example.com/v1", " https://a.b.c ").forEach {
            assertNull(it, EndpointSafety.cleartextWarning(it))
        }
    }

    // ---------- 不该警告：本机 / 局域网 ----------

    @Test
    fun `本机和局域网http_不警告`() {
        listOf(
            "http://localhost:11434",
            "http://127.0.0.1:8000/v1",
            "http://127.1.2.3",
            "http://192.168.1.10:11434/v1",
            "http://10.0.2.2:8080",
            "http://10.1.1.1",
            "http://172.16.0.9:3000",
            "http://172.31.255.1",
            "http://169.254.10.10",
            "http://[::1]:11434",
            "http://myserver:11434",          // 单标签主机名 → 内网
            "http://ollama.lan:11434",        // 内网后缀
            "http://nas.local",
        ).forEach { assertNull(it, EndpointSafety.cleartextWarning(it)) }
    }

    @Test
    fun `172段的边界_只在16到31`() {
        assertNull(EndpointSafety.cleartextWarning("http://172.16.0.1"))
        assertNull(EndpointSafety.cleartextWarning("http://172.31.0.1"))
        assertNotNull("172.32 不在私有段", EndpointSafety.cleartextWarning("http://172.32.0.1"))
        assertNotNull("172.15 不在私有段", EndpointSafety.cleartextWarning("http://172.15.0.1"))
    }

    @Test
    fun `空地址与杂项_不警告`() {
        assertNull(EndpointSafety.cleartextWarning(""))
        assertNull(EndpointSafety.cleartextWarning("   "))
        assertNull("没有 scheme 的裸地址交给系统兜底", EndpointSafety.cleartextWarning("api.example.com"))
    }

    // ---------- extractHost ----------

    @Test
    fun `主机名提取_端口与路径与IPv6`() {
        assertEquals("api.example.com", EndpointSafety.extractHost("api.example.com/v1"))
        assertEquals("api.example.com", EndpointSafety.extractHost("api.example.com:8080/v1"))
        assertEquals("::1", EndpointSafety.extractHost("[::1]:11434/v1"))
    }

    @Test
    fun `公网明文主机_返回主机名_本地和https返回null`() {
        assertEquals(
            "api.example.com",
            EndpointSafety.publicCleartextHost("http://api.example.com/v1"),
        )
        assertNull(EndpointSafety.publicCleartextHost("http://192.168.1.1:11434"))
        assertNull(EndpointSafety.publicCleartextHost("http://localhost:11434"))
        assertNull(EndpointSafety.publicCleartextHost("https://api.example.com"))
        assertNull(EndpointSafety.publicCleartextHost(""))
    }

    @Test
    fun `isLocalHost_直接判定`() {
        assertTrue(EndpointSafety.isLocalHost("localhost"))
        assertTrue(EndpointSafety.isLocalHost("192.168.0.1"))
        assertFalse(EndpointSafety.isLocalHost("api.example.com"))
        assertFalse(EndpointSafety.isLocalHost("8.8.8.8"))
    }
}
