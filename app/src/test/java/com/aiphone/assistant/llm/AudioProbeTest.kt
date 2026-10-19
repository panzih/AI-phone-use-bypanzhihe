package com.aiphone.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音探针里最容易写错的两块：WAV 头、请求体结构。
 *
 * 这两块错了，网关只会回一个 400 —— 而 400 也正是"不支持音频"的返回码，
 * 于是我们会把"自己参数写错"误判成"这个通道不能收音频"，把 T40 判死。
 */
class AudioProbeTest {

    @Test
    fun `WAV 头是标准的 44 字节 PCM`() {
        val wav = AudioProbe.silentWav(seconds = 1, sampleRate = 16000)
        assertEquals(44 + 16000 * 2, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals("data", String(wav.copyOfRange(36, 40)))
        // 全是静音：PCM 数据区必须逐字节为 0
        assertTrue(wav.copyOfRange(44, wav.size).all { it == 0.toByte() })
    }

    @Test
    fun `请求体里带 input_audio 且格式写成 wav`() {
        val body = AudioProbe.buildBody("mimo-v2.6-flash-free", "AAA=")
        // 单测里 org.json 是未实现的桩，只能按结构断言
        assertTrue(body.contains("\"model\":\"mimo-v2.6-flash-free\""))
        assertTrue(body.contains("\"type\":\"input_audio\""))
        assertTrue(body.contains("\"format\":\"wav\""))
        assertTrue(body.contains("\"data\":\"AAA=\""))
    }

    @Test
    fun `参数写错和不支持要能分开说`() {
        // 同样的 400，措辞必须让用户知道下一步该干什么
        val unsupported = AudioProbe.describe(AudioProbe.Outcome(false, 400, "bad request"))
        assertTrue(unsupported.contains("不收音频"))
        val auth = AudioProbe.describe(AudioProbe.Outcome(false, 401, "unauthorized"))
        assertTrue(auth.contains("Key"))
    }
}
