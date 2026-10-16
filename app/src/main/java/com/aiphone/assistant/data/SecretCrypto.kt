package com.aiphone.assistant.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 绑定在系统密钥库里的对称加密原语。
 *
 * 抽成接口只有一个目的：让 [SecretCrypto] 的信封逻辑能做单测 ——
 * 单元测试跑在 JVM 上，没有 Android Keystore，用假实现替换即可。
 */
interface ByteCipher {
    /** 加密。返回值必须自带随机 IV（解密时只给它这一份数据） */
    fun encrypt(plain: ByteArray): ByteArray

    /** 解密 [encrypt] 的产物。数据被篡改/密钥不对时必须抛异常 */
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * Android Keystore 支持的 AES-256-GCM。
 *
 * 为什么不用 EncryptedSharedPreferences（Jetpack Security）：
 *   1. 它已被 Google 标记为弃用，官方指引就是"自己用 Keystore"；
 *   2. 引入 security-crypto + Tink 会给这个零第三方依赖的工程
 *      拖进一整棵依赖树 —— 而需求只有一条"把 API Key 加密落盘"。
 *
 * 密钥存在系统 Keystore 里（`AndroidKeyStore` provider），**不出硬件/系统**：
 * 应用沙箱里拿到的只有密文；把 SharedPreferences 文件拷走（备份、root 浏览）
 * 也解不开。密钥不可导出（setKeySize + 不用 setUserAuthentication 之外的
 * 导出路径），删应用即删密钥。
 *
 * 输出格式：`IV(12B) || 密文+GCM tag`  —— GCM 每次加密都必须换新 IV，
 * 所以把 IV 拼在密文前面一起存；解密时同样的方法反着来。
 */
object AndroidKeystoreCipher : ByteCipher {

    private const val TAG = "SecretCrypto"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "aiphone_secrets_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    /** 避免多线程同时首次生成密钥造出两把（一把永远解不开另一把的密文） */
    @Volatile
    private var cached: SecretKey? = null

    private fun key(): SecretKey = cached ?: synchronized(this) {
        cached ?: loadOrCreateKey().also { cached = it }
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证 / 不做指纹绑定：这是无人值守的自动化工具，
                // 每次读配置都弹指纹会直接把功能废掉
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "GCM IV 长度异常：${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "密文太短，不是本实现产出的格式" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, blob, 0, IV_BYTES),
        )
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }
}

/**
 * API Key 的"信封"：把敏感字符串加密成可安全落盘的文本。
 *
 * ## 格式
 *
 * ```
 *   enc:v1:<Base64(IV || 密文)>
 * ```
 *
 * 带 `enc:v1:` 前缀有两个作用：
 *   1. 一眼看出这条记录是密文还是历史遗留的明文；
 *   2. 以后换算法可以升 v2，老数据按前缀分流，不需要迁移窗口。
 *
 * ## 兼容策略（这一段是行为契约，别改）
 *
 *   - 读到**没有前缀**的值 = 老版本遗留的明文 → 原样返回（不报错、不丢 Key），
 *     下次保存会自动加密写回；
 *   - 读到有前缀但**解不开**的值（换过手机、系统密钥被清、文件被改坏）
 *     → 记日志、按"未填写"处理。**绝不把密文当明文用** ——
 *     那会把一段乱码当 API Key 发出去，用户看到的是莫名其妙的 401；
 *   - 加密失败（极罕见）→ 降级为明文保存 + 日志。宁可回到原来的安全水平，
 *     也不能让用户"填了 Key 存不上"。
 *
 * @param cipher null = 不做加密（只在测试里出现）
 */
class SecretCrypto(private val cipher: ByteCipher?) {

    /** 明文 → 可安全落盘的文本。空串原样返回（没填就是没填） */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return plain
        val c = cipher ?: return plain
        return runCatching {
            PREFIX + Base64.encodeToString(c.encrypt(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }.getOrElse {
            Log.w(TAG, "加密失败，本次按明文保存（下次保存会重试）", it)
            plain
        }
    }

    /** 落盘文本 → 明文。老明文原样放行；解不开的密文按"未填写"处理 */
    fun unseal(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        val c = cipher ?: return ""
        val blob = runCatching {
            Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        }.getOrElse {
            Log.w(TAG, "密文不是合法 Base64，按未填写处理", it)
            return ""
        }
        return runCatching {
            String(c.decrypt(blob), Charsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "解不开已加密的 API Key（系统密钥被清或换了设备），按未填写处理", it)
            ""
        }
    }

    /** 这条记录是不是已加密（用于一次性迁移判断） */
    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        private const val TAG = "SecretCrypto"
        const val PREFIX = "enc:v1:"

        /** 生产实现：绑定 Android Keystore */
        fun withKeystore(): SecretCrypto = SecretCrypto(AndroidKeystoreCipher)
    }
}
