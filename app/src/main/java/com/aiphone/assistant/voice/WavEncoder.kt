package com.aiphone.assistant.voice

import java.io.ByteArrayOutputStream

/**
 * 把裸 PCM 数据包成 16 位 WAV。
 *
 * DeepSeek 的 ASR 接口只收 **16 位 PCM WAV** 或 Ogg Opus（见
 * https://api-docs.deepseek.com/zh-cn/guides/asr）。录音端拿到的就是
 * PCM16，所以这里只补一个 44 字节的 RIFF 头 —— 不引第三方转码库
 * （工程约束：不引第三方运行时依赖）。
 *
 * 小端序是 WAV 的规范，Android 设备也都是小端，直接按字节写。
 */
object WavEncoder {

    private const val HEADER_BYTES = 44

    fun encode(
        pcm: ByteArray,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val out = ByteArrayOutputStream(HEADER_BYTES + pcm.size)

        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(HEADER_BYTES - 8 + pcm.size))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(le32(16))                 // fmt 块长度
        out.write(le16(1))                  // PCM
        out.write(le16(channels))
        out.write(le32(sampleRate))
        out.write(le32(byteRate))
        out.write(le16(blockAlign))
        out.write(le16(bitsPerSample))

        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(le32(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun le16(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
    )
}
