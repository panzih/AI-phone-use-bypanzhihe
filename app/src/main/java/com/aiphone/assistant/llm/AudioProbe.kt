package com.aiphone.assistant.llm

import java.io.ByteArrayOutputStream

/**
 * 语音能力探针（T40 的第一步）。
 *
 * ## 为什么先做探针，而不是直接做「按住说话」
 *
 * 结论已经有了一半：models.dev 上 `MiMo-V2.6-Pro / V2.6-Flash` 的
 * `modalities.input` 是 `text, image, audio, video` —— **原生吃音频**，
 * 不需要先转文字。但那说的是模型，不是网关：opencode 的免费通道**收不收**
 * `input_audio` 这一段，没有任何文档说，只能真发一次看返回码。
 *
 * 所以这里发一段 1 秒**静音** WAV：不涉及任何隐私，只回答一个问题 ——
 * 这个通道能不能收音频。返回 200 = 能，400/415 = 收不了，走端侧 SenseVoice。
 */
object AudioProbe {

    data class Outcome(val ok: Boolean, val code: Int, val detail: String)

    /** 探针结果里带上这个，界面/日志一眼能分清是"能力问题"还是"网络问题" */
    fun describe(o: Outcome): String = when {
        o.ok -> "语音输入可用（HTTP ${o.code}）"
        o.code == 400 || o.code == 415 || o.code == 422 ->
            "这个通道不收音频（HTTP ${o.code}）：${o.detail.take(160)}"
        o.code == 401 || o.code == 403 ->
            "通道拒绝了这次请求（HTTP ${o.code}），先确认 Key / 免费通道状态"
        o.code == 0 -> "没连上：${o.detail.take(160)}"
        else -> "HTTP ${o.code}：${o.detail.take(160)}"
    }

    /**
     * 1 秒静音 PCM16 WAV（16k 单声道）。
     *
     * 自己拼 44 字节头而不是引第三方库：项目硬约束是"不引第三方运行时依赖"。
     * 静音的好处是**可复现**——不管在谁的手机上跑，发出去的都是同一串字节。
     */
    fun silentWav(seconds: Int = 1, sampleRate: Int = 16000): ByteArray {
        val dataSize = sampleRate * seconds * 2 // 16bit mono
        val out = ByteArrayOutputStream(44 + dataSize)
        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        out.write("RIFF".toByteArray())
        out.write(le32(36 + dataSize))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))              // PCM
        out.write(le16(1))              // mono
        out.write(le32(sampleRate))
        out.write(le32(sampleRate * 2)) // byte rate
        out.write(le16(2))              // block align
        out.write(le16(16))             // bits
        out.write("data".toByteArray())
        out.write(le32(dataSize))
        out.write(ByteArray(dataSize))  // 全 0 = 静音
        return out.toByteArray()
    }

    /**
     * 请求体。**手拼字符串**而不是 JSONObject：
     *
     * 一是这几个字段全是固定结构、值也只有 base64（不含引号），拼起来没有转义风险；
     * 二是单测里 `org.json` 是未实现的桩（`Method put ... not mocked`），
     * 用 JSONObject 的话这段逻辑根本没法测 —— 而它恰恰是最容易写错的一段。
     */
    fun buildBody(model: String, audioBase64: String): String =
        """{"model":"$model","max_tokens":16,"messages":[{"role":"user","content":[""" +
            """{"type":"text","text":"只回答两个字：收到"},""" +
            """{"type":"input_audio","input_audio":{"data":"$audioBase64","format":"wav"}}""" +
            """]}]}"""
}
