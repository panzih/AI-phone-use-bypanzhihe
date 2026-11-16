package com.aiphone.assistant.llm

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * DeepSeek 语音识别（ASR）客户端。
 *
 * ## 接口是什么
 *
 * `POST https://api.deepseek.com/v1/tools/asr`，`multipart/form-data` 的
 * `file` 字段里放 16 位 PCM WAV 或 Ogg Opus，一次请求返回完整文本，
 * 不支持流式。限制：最大 8 MiB、最长 120 秒（文档 guides/asr，2026-11）。
 *
 * 语种由服务端自动识别，**没有语种参数** —— 所以这里也不提供语言设置。
 *
 * ## 为什么不用录音通道的接口
 *
 * 免费通道（OpenCode / Kilo）和自定义 OpenAI 兼容网关都没有这个路径；
 * 语音识别是 DeepSeek 官方工具接口，只认 DeepSeek 的 Key。所以：
 * 走哪条模型通道不影响这里，**没有 Key 就用不了语音输入**，界面上直接说清楚。
 *
 * ## 端点怎么拼
 *
 * 默认 `baseUrl = https://api.deepseek.com` → `/v1/tools/asr`；
 * 有人习惯把地址填成 `.../v1`，那就只补 `/tools/asr`，不会出现 `v1/v1`。
 * 路径完全由用户填的接口地址决定，不做域名白名单 —— 自建代理同样能转发。
 */
object AsrClient {

    const val OFFICIAL_BASE = "https://api.deepseek.com"
    const val MAX_BYTES = 8 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Fail(val message: String) : Result
    }

    /**
     * 按用户的接口地址得到 ASR 端点。
     *
     * 特殊处理已经是完整 ASR 地址的写法，避免重复拼接。
     */
    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/').ifBlank { OFFICIAL_BASE }
        return when {
            base.endsWith("/v1/tools/asr") -> base
            base.endsWith("/v1") -> "$base/tools/asr"
            else -> "$base/v1/tools/asr"
        }
    }

    /**
     * 转写一段 WAV。
     *
     * 网络调用是阻塞的，调用方要放在 IO 线程。
     *
     * @param allowPublicCleartext 与模型请求同一道闸门：公网明文 HTTP 默认拦截，
     *        否则 API Key 和录音都会明文过网。
     */
    fun transcribe(
        baseUrl: String,
        apiKey: String,
        wav: ByteArray,
        allowPublicCleartext: Boolean,
    ): Result {
        if (apiKey.isBlank()) {
            return Result.Fail("语音输入需要 DeepSeek API Key：到「设置 → 模型」里填一个再试")
        }
        if (wav.isEmpty()) return Result.Fail("没有录到声音，请重试")
        if (wav.size > MAX_BYTES) return Result.Fail("录音超过 8 MB 上限，请缩短后再试")

        val endpoint = endpoint(baseUrl)
        EndpointSafety.publicCleartextHost(endpoint)?.let { host ->
            if (!allowPublicCleartext) {
                return Result.Fail(
                    "接口地址是公网明文 HTTP（$host），录音和 API Key 会明文过网，" +
                        "已在发送前拦截。换成 https://，或到设置里显式允许公网明文。"
                )
            }
        }

        val boundary = "paperbox-" + UUID.randomUUID().toString().replace("-", "")
        val body = buildMultipart(wav, boundary)

        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setFixedLengthStreamingMode(body.size)
            }
            conn = c
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = runCatching {
                (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            }.getOrDefault("")

            if (code !in 200..299) {
                Result.Fail(describeError(code, text))
            } else {
                val transcript = parseText(text)
                if (transcript.isNullOrBlank()) {
                    Result.Fail("识别接口返回了空文本，请重试")
                } else {
                    Result.Ok(transcript)
                }
            }
        } catch (t: Throwable) {
            Result.Fail("没连上语音识别接口：${t.javaClass.simpleName}: ${t.message ?: ""}".trim())
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * multipart 请求体。
     *
     * 手拼字节而不是找 multipart 库：只有一个文件字段，格式固定；
     * 而 JVM 单测里能直接检查边界和文件头，比启动服务器验证省事。
     */
    fun buildMultipart(wav: ByteArray, boundary: String): ByteArray {
        val out = ByteArrayOutputStream(wav.size + 256)
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n")
            append("Content-Type: audio/wav\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(wav)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /** 响应里的 `text`；解析不了返回 null */
    fun parseText(json: String): String? = runCatching {
        JSONObject(json).optString("text").trim().ifBlank { null }
    }.getOrNull()

    /**
     * 把 HTTP 错误翻译成用户能照着做的句子。
     *
     * 文档列了 400/408/413/429/503/504 六种；401/403 是 Key 问题，
     * 其余归到"服务端返回了什么就显示什么"。
     */
    fun describeError(code: Int, body: String): String {
        val detail = errorMessage(body)
        val suffix = if (detail.isNullOrBlank()) "" else "：${detail.take(180)}"
        return when (code) {
            400 -> "音频格式不被接受（HTTP 400）$suffix"
            401, 403 -> "DeepSeek 拒绝了这次请求（HTTP $code），请检查 API Key 是否正确$suffix"
            408, 504 -> "语音识别超时（HTTP $code），请稍后重试$suffix"
            413 -> "录音太大（HTTP 413），请缩短录音"
            429 -> "语音识别并发请求超过上限（HTTP 429），等一会儿再试$suffix"
            503 -> "语音识别服务繁忙（HTTP 503），请稍后重试$suffix"
            else -> "语音识别失败（HTTP $code）$suffix"
        }
    }

    private fun errorMessage(body: String): String? = runCatching {
        val obj = JSONObject(body)
        obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: obj.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
}
