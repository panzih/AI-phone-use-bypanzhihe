package com.aiphone.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SecretCrypto] 的信封逻辑：**加密后的值能原样解回来、老明文能平滑迁移、
 * 解不开的密文绝不当明文用**。
 *
 * 用假 [ByteCipher]（固定 IV + 异或）而不是真 Keystore：JVM 单测里没有
 * Android Keystore，而且这里要钉的是"格式与兼容策略"，不是 AES 本身 ——
 * AES-GCM 的正确性由系统保证，不需要我们测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretCryptoTest {

    /**
     * 假加密：IV(12B 固定) || 校验尾(4B) || 异或密文。格式与真实现一致（IV 前置）。
     *
     * 校验尾是**故意加上的**：AES-GCM 对篡改过的数据会认证失败、抛异常，
     * "解不开的密文按未填写处理"这条路径就建立在它上面。纯异或没有完整性
     * 校验，被改坏的数据照样能"解"出一段乱码 —— 不模拟这一点，
     * 测到的就是假行为（真机走抛异常分支，测试里却走乱码分支）。
     */
    private class FakeCipher(private val broken: Boolean = false) : ByteCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            val body = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
            return ByteArray(12) { 7 } + tag(body) + body
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("密钥没了")
            check(blob.size >= 16) { "密文太短，不是本实现产出的格式" }
            val body = blob.copyOfRange(16, blob.size)
            check(tag(body).contentEquals(blob.copyOfRange(12, 16))) { "认证失败：数据被改过" }
            return ByteArray(body.size) { (body[it].toInt() xor 0x5A).toByte() }
        }

        /** 简易校验尾：对密文体做一次 FNV 式滚动哈希，取 4 字节 */
        private fun tag(body: ByteArray): ByteArray {
            var h: Int = 0x811C9DC5.toInt()
            body.forEach { h = (h xor it.toInt()) * 0x01000193 }
            return byteArrayOf((h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte())
        }
    }

    private val crypto = SecretCrypto(FakeCipher())

    @Test
    fun `密文往返_原样解回`() {
        val key = "sk-1234567890abcdef"
        val sealed = crypto.seal(key)
        assertEquals(key, crypto.unseal(sealed))
    }

    @Test
    fun `密文带前缀_且不包含明文`() {
        val key = "sk-super-secret-value"
        val sealed = crypto.seal(key)
        assertTrue(sealed, sealed.startsWith(SecretCrypto.PREFIX))
        assertFalse("密文里不能出现明文", sealed.contains(key))
        assertTrue(crypto.isSealed(sealed))
    }

    @Test
    fun `中文和空串都能往返`() {
        assertEquals("中文-密钥-🔑", crypto.unseal(crypto.seal("中文-密钥-🔑")))
        assertEquals("", crypto.seal(""))
        assertEquals("", crypto.unseal(""))
    }

    @Test
    fun `老明文_原样放行`() {
        // 老版本存的明文没有前缀 —— 读的时候必须原样返回，不能当成坏数据
        assertFalse(crypto.isSealed("sk-old-plaintext"))
        assertEquals("sk-old-plaintext", crypto.unseal("sk-old-plaintext"))
    }

    @Test
    fun `解不开的密文_按未填写处理_绝不返回密文`() {
        val sealed = crypto.seal("sk-1234")
        // 换了个"密钥丢了"的环境
        val lost = SecretCrypto(FakeCipher(broken = true))
        assertEquals("", lost.unseal(sealed))
    }

    @Test
    fun `密文被改坏_按未填写处理`() {
        val sealed = crypto.seal("sk-1234")
        val corrupted = sealed.dropLast(4) + "!!!!"
        assertEquals("", crypto.unseal(corrupted))
    }

    @Test
    fun `没有加密器_envoy 降级为明文而不是崩溃`() {
        val none = SecretCrypto(null)
        assertEquals("sk-abc", none.seal("sk-abc"))
        // 密文交给没有密钥的环境 → 只能按"未填写"，而非瞎猜
        assertEquals("", none.unseal(crypto.seal("sk-abc")))
    }
}
