package com.aiphone.assistant.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * API Key 的落盘形态：**存进去是密文、读出来是明文、老明文自动迁移**。
 *
 * 这里用假 [ByteCipher]（JVM 上没有 Keystore），但**读的是真实
 * SharedPreferences 文件内容** —— 要钉住的正是"盘上那一格到底是什么"，
 * 只在内存里往返是测不到的：明文落盘这个安全问题就藏在这一步。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    /**
     * 假加密，与 SecretCryptoTest 同款：IV 前置 + 校验尾（模拟 GCM 的认证），
     * 篡改过的数据必须抛异常 —— "解不开的密文按未填写"这条路径靠它成立。
     */
    private class FakeCipher : ByteCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            val body = ByteArray(plain.size) { (plain[it].toInt() xor 0x33).toByte() }
            return ByteArray(12) { 1 } + tag(body) + body
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            check(blob.size >= 16) { "密文太短，不是本实现产出的格式" }
            val body = blob.copyOfRange(16, blob.size)
            check(tag(body).contentEquals(blob.copyOfRange(12, 16))) { "认证失败：数据被改过" }
            return ByteArray(body.size) { (body[it].toInt() xor 0x33).toByte() }
        }

        private fun tag(body: ByteArray): ByteArray {
            var h: Int = 0x811C9DC5.toInt()
            body.forEach { h = (h xor it.toInt()) * 0x01000193 }
            return byteArrayOf((h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte())
        }
    }

    private lateinit var context: Context
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = SettingsStore(context, SecretCrypto(FakeCipher()))
    }

    private fun rawStored(): String? =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("api_key", null)

    @Test
    fun `保存后_盘上是密文_读出来是明文`() {
        store.save(AppSettings(apiKey = "sk-1234567890"))
        val raw = rawStored()
        assertTrue("盘上必须是带信封前缀的密文：$raw", raw!!.startsWith(SecretCrypto.PREFIX))
        assertNotEquals("sk-1234567890", raw)
        assertEquals("sk-1234567890", store.load().apiKey)
    }

    @Test
    fun `老明文_读到即迁移_原地加密写回`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("api_key", "sk-legacy-plain").commit()

        // 读到的是原文（不能丢 Key）
        assertEquals("sk-legacy-plain", store.load().apiKey)
        // 且已经被顺手加密写回 —— 迁移无感、不需要用户再保存一次
        val raw = rawStored()
        assertTrue("迁移后盘上应是密文：$raw", raw!!.startsWith(SecretCrypto.PREFIX))
        assertEquals("sk-legacy-plain", store.load().apiKey)
    }

    @Test
    fun `解不开的密文_按未填写返回_不冒充明文`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("api_key", SecretCrypto.PREFIX + "bm90LXJlYWxseS1lbmNyeXB0ZWQ=").commit()
        assertEquals("", store.load().apiKey)
    }

    @Test
    fun `其他字段的往返不受加密影响`() {
        val s = AppSettings(
            baseUrl = "http://192.168.1.10:11434",
            apiKey = "sk-x",
            modelName = "qwen2.5",
            useFreeModel = false,
            thinking = ThinkingMode.HIGH,
            maxSteps = 12,
            contextPolicy = ContextPolicy.RESET_EACH_TIME,
            memoryEnabled = true,
            saveLogs = false,
            saveScreenshots = false,
            localDialogDismiss = false,
            tapReview = TapReviewMode.ALL,
        )
        store.save(s)
        assertEquals(s, store.load())
    }
}
