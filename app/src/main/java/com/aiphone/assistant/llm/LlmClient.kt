package com.aiphone.assistant.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val detail: String = "original",
    val temperature: Double = 1.0,
    val timeoutMs: Int = 120_000,
)

data class ChatTurn(val role: String, val text: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

sealed class LlmResult {
    data class Ok(
        val text: String,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val cacheHitTokens: Int = 0,
        val cacheMissTokens: Int = 0,
    ) : LlmResult()
    data class Fail(val message: String) : LlmResult()
}

class LlmClient(private val cfg: LlmConfig) {

    fun chat(system: String, history: List<ChatTurn>, imagePng: ByteArray?): LlmResult {
        if (cfg.apiKey.isBlank()) {
            return LlmResult.Fail("还没填 API Key。到「设置 → 模型」里填一个。")
        }
        val body = buildBody(system, history, imagePng)
        return try {
            val conn = (URL(endpoint()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = cfg.timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) return LlmResult.Fail(explainHttpError(code, text))
            parseResponse(text)
        } catch (t: Throwable) {
            LlmResult.Fail(explainThrowable(t))
        }
    }

    private fun endpoint(): String {
        val base = cfg.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.deepseek.com/v1/chat/completions"
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun buildBody(system: String, history: List<ChatTurn>, imagePng: ByteArray?): JSONObject {
        val messages = JSONArray()
        messages.put(JSONObject().apply { put("role", "system"); put("content", system) })
        val lastUserIndex = history.indexOfLast { it.role == ChatTurn.USER }
        history.forEachIndexed { i, turn ->
            messages.put(JSONObject().apply {
                put("role", turn.role)
                if (i == lastUserIndex && imagePng != null && imagePng.isNotEmpty()) {
                    val parts = JSONArray()
                    parts.put(JSONObject().apply { put("type", "text"); put("text", turn.text) })
                    parts.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/png;base64,${b64(imagePng)}")
                            put("detail", cfg.detail)
                        })
                    })
                    put("content", parts)
                } else {
                    put("content", turn.text)
                }
            })
        }
        return JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            put("temperature", cfg.temperature)
            put("stream", false)
        }
    }

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun parseResponse(raw: String): LlmResult {
        return try {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0)
                return LlmResult.Fail("模型没有返回任何内容。原始响应：${raw.take(300)}")
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: return LlmResult.Fail("返回结构里没有 message 字段：${raw.take(300)}")
            val content = message.optString("content", "").ifBlank {
                message.optString("reasoning_content", "")
            }
            if (content.isBlank()) return LlmResult.Fail("模型返回了空内容：${raw.take(300)}")
            val usage = root.optJSONObject("usage")
            LlmResult.Ok(
                text = content,
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
                cacheHitTokens = usage?.optInt("prompt_cache_hit_tokens", 0) ?: 0,
                cacheMissTokens = usage?.optInt("prompt_cache_miss_tokens", 0) ?: 0,
            )
        } catch (t: Throwable) {
            LlmResult.Fail("响应不是合法 JSON：${t.message}\n原始内容：${raw.take(300)}")
        }
    }

    private fun explainHttpError(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull().orEmpty().ifBlank { body.take(200) }
        val base = when (code) {
            401, 403 -> "API Key 不对或没有权限。检查「设置 → 模型」里的 Key。"
            404 -> "接口地址或模型名不对。地址是 ${endpoint()}，模型是 ${cfg.model}。"
            429 -> "请求太频繁或额度用完了。等一会儿再试。"
            400 -> when {
                detail.contains("image", true) || detail.contains("vision", true) ->
                    "这个模型不接受图片输入。要多模态模型（比如 deepseek-flash）。"
                detail.contains("model", true) -> "模型名不对：${cfg.model}。"
                else -> "请求被拒绝。"
            }
            500, 502, 503, 504 -> "服务端出错了（$code），稍后重试。"
            else -> "请求失败，HTTP $code。"
        }
        return "$base\n服务端说明：$detail"
    }

    private fun explainThrowable(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "请求超时。可能是网络慢，或者接口地址填错了。"
        is java.net.UnknownHostException -> "连不上 ${cfg.baseUrl}。检查手机网络，或者接口地址有没有写错。"
        is javax.net.ssl.SSLException -> "HTTPS 握手失败：${t.message}"
        else -> "请求出错：${t.javaClass.simpleName} ${t.message}"
    }

    fun describe(): String = "模型=${cfg.model} 地址=${endpoint()} 精度=${cfg.detail}"
}
