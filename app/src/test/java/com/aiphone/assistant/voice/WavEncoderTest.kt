package com.aiphone.assistant.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WAV 头的字节级契约。
 *
 * DeepSeek ASR 只认 16 位 PCM WAV / Ogg Opus，格式不对直接 400 —— 而
 * 自拼头部最容易错的就是长度字段（少 36 / 少 8 / 用了裸数据长度）。
 * 这里把 44 字节逐字段钉住，改格式时不用靠真机试。
 */
class WavEncoderTest {

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun ascii(b: ByteArray, from: Int, len: Int): String =
        b.copyOfRange(from, from + len).toString(Charsets.US_ASCII)

    @Test
    fun `16k单声道PCM16_头部各字段正确`() {
        val pcm = ByteArray(32_000) { it.toByte() } // 1 秒
        val wav = WavEncoder.encode(pcm)

        assertEquals("头部 44 字节 + 数据", 44 + pcm.size, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))

        assertEquals("RIFF 块长度 = 36 + 数据", 36 + pcm.size, le32(wav, 4))
        assertEquals("fmt 块长度 16", 16, le32(wav, 16))
        assertEquals("PCM = 1", 1, le16(wav, 20))
        assertEquals("单声道", 1, le16(wav, 22))
        assertEquals("采样率 16000", 16_000, le32(wav, 24))
        assertEquals("字节率 32000", 32_000, le32(wav, 28))
        assertEquals("块对齐 2", 2, le16(wav, 32))
        assertEquals("位深 16", 16, le16(wav, 34))
        assertEquals("数据长度", pcm.size, le32(wav, 40))
    }

    @Test
    fun `PCM数据原样跟在头后面`() {
        val pcm = ByteArray(1_000) { (it * 7).toByte() }
        val wav = WavEncoder.encode(pcm, sampleRate = 22_050)
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertEquals("采样率按参数写入", 22_050, le32(wav, 24))
        assertEquals("字节率随采样率变", 44_100, le32(wav, 28))
    }
}
