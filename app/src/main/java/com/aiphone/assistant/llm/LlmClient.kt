package com.aiphone.assistant.llm

import com.aiphone.assistant.data.ThinkingMode
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val useFreeModel: Boolean = false,
    val detail: String = "original",
    val temperature: Double = 1.0,
    val thinking: ThinkingMode = ThinkingMode.DEFAULT,
    val timeoutMs: Int = 120_000,
    val maxAttempts: Int = 3,
)

data class ChatTurn(
    val role: String,
    val text: String,
    val imageBytes: ByteArray? = null,
) {
    val imageId: String? by lazy { imageBytes?.let { imageIdOf(it) } }
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        private const val HEX = "0123456789abcdef"
        fun imageIdOf(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(24)
            for (i in 0 until 8) {
                val b = digest[i].toInt() and 0xFF
                sb.append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
            return sb.append('-').append(bytes.size).toString()
        }
    }
}

sealed class LlmResult {
    data class Ok(
        val text: String,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val cacheHitTokens: Int = 0,
        val cacheMissTokens: Int = 0,
        val prefixReused: Int = 0,
        val prefixTotal: Int = 0,
    ) : LlmResult()
    data class Fail(val message: String) : LlmResult()
}

class LlmClient(private val cfg: LlmConfig) {
    private val activeConn = AtomicReference<HttpURLConnection?>(null)
    private val freeSessionId = "ses_" + java.util.UUID.randomUUID().toString().replace("-", "").take(26)
    private val abortFlag = AtomicBoolean(false)

    fun abort() {
        abortFlag.set(true)
        runCatching { activeConn.get()?.disconnect() }
    }

    private sealed class Try {
        class Ok(val result: LlmResult.Ok) : Try()
        class Retry(val why: String) : Try()
        class Fatal(val message: String) : Try()
    }

    private var lastFingerprints: List<Int> = emptyList()
    fun fingerprintChain(): List<Int> = lastFingerprints
    fun seedFingerprints(chain: List<Int>) { lastFingerprints = chain }

    fun chat(
        system: String,
        history: List<ChatTurn>,
        onUploaded: (() -> Unit)? = null,
        onRetry: ((attempt: Int, maxAttempts: Int, why: String, waitMs: Long) -> Unit)? = null,
        shouldAbort: (() -> Boolean)? = null,
    ): LlmResult {
        if (!cfg.useFreeModel && cfg.apiKey.isBlank()) {
            return LlmResult.Fail("还没填 API Key。到「设置 → 模型」里填一个。")
        }
        warnIfPublicHttp(cfg.baseUrl)
        abortFlag.set(false)
        val fingerprints = fingerprint(system, history)
        val previous = lastFingerprints
        val reused = commonPrefixLength(previous, fingerprints)
        lastFingerprints = fingerprints
        val body = buildBody(system, history)
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + RETRY_TOTAL_BUDGET_MS
        val maxAttempts = cfg.maxAttempts.coerceAtLeast(1)
        var attempts = 0

        while (true) {
            if (isAborted() || shouldAbort?.invoke() == true) return LlmResult.Fail(ABORT_MESSAGE)
            attempts++
            val outcome = try {
                attemptOnce(body, onUploaded, reused, previous.size)
            } catch (t: Throwable) {
                throwableToTry(t)
            }
            when (outcome) {
                is Try.Ok -> return outcome.result
                is Try.Fatal -> return LlmResult.Fail(outcome.message)
                is Try.Retry -> {
                    Log.w(TAG, "第 $attempts 次请求没成：${outcome.why}")
                    if (attempts >= maxAttempts) return giveUp(attempts, outcome.why, startedAt)
                    if (System.currentTimeMillis() + cfg.timeoutMs > deadline)
                        return giveUp(attempts, "${outcome.why}，且总耗时已超预算", startedAt)
                    val wait = backoffMs(attempts)
                    onRetry?.invoke(attempts, maxAttempts, outcome.why, wait)
                    if (!sleepWithAbort(wait, shouldAbort)) return LlmResult.Fail(ABORT_MESSAGE)
                }
            }
        }
    }

    private fun attemptOnce(
        body: JSONObject,
        onUploaded: (() -> Unit)?,
        prefixReused: Int,
        prefixTotal: Int,
    ): Try {
        val conn = (URL(endpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = cfg.timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (cfg.useFreeModel) {
                setRequestProperty("Authorization", "Bearer public")
                setRequestProperty("User-Agent", "opencode/1.18.31")
                setRequestProperty("x-opencode-client", "desktop")
                setRequestProperty("x-opencode-session", freeSessionId)
                setRequestProperty("x-opencode-request", "msg_" + java.util.UUID.randomUUID().toString().replace("-", "").take(27))
                setRequestProperty("x-opencode-project", "global")
            } else {
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
        }
        activeConn.set(conn)
        val watched = Watchdog(conn)
        watched.start()
        return try {
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            if (isAborted()) { runCatching { conn.disconnect() }; return Try.Fatal(ABORT_MESSAGE) }
            onUploaded?.invoke()
            val code = conn.responseCode
            val text = if (code in 200..299) readAll(conn.inputStream) else readAll(conn.errorStream)
            if (code !in 200..299) httpFailure(code, text) else parseResponse(text, prefixReused, prefixTotal)
        } catch (t: Throwable) {
            when {
                isAborted() -> Try.Fatal(ABORT_MESSAGE)
                watched.tripped -> Try.Retry("超时（${cfg.timeoutMs / 1000}s 没等到响应）")
                else -> throwableToTry(t)
            }
        } finally {
            watched.stop()
            activeConn.compareAndSet(conn, null)
        }
    }

    private fun readAll(stream: java.io.InputStream?): String =
        stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""

    private fun backoffMs(attempt: Int): Long {
        val base = RETRY_BASE_DELAY_MS shl (attempt - 1)
        val jitter = (base * 0.2 * (Math.random() * 2 - 1)).toLong()
        return (base + jitter).coerceAtLeast(400L)
    }

    private fun sleepWithAbort(totalMs: Long, shouldAbort: (() -> Boolean)?): Boolean {
        var left = totalMs
        while (left > 0) {
            if (isAborted() || shouldAbort?.invoke() == true) return false
            val step = minOf(left, ABORT_POLL_MS)
            try { Thread.sleep(step) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return false }
            left -= step
        }
        return true
    }

    private fun isAborted(): Boolean = abortFlag.get()

    private fun giveUp(attempts: Int, why: String, startedAt: Long): LlmResult.Fail {
        val spent = (System.currentTimeMillis() - startedAt) / 1000
        return LlmResult.Fail("模型连续 $attempts 次没有正常响应（最后一次：$why），共花了 ${spent}s。\n网络不稳就重发一次任务；每次都这样，检查手机网络，或者看「设置 → 模型」里的地址和服务商状态。")
    }

    private inner class Watchdog(private val conn: HttpURLConnection) {
        @Volatile var tripped: Boolean = false
        private val alive = AtomicBoolean(true)
        private val thread = Thread {
            val limit = cfg.timeoutMs + WATCHDOG_GRACE_MS
            val deadline = System.currentTimeMillis() + limit
            while (alive.get()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    if (alive.getAndSet(false)) {
                        tripped = true
                        Log.w(TAG, "单次请求超过 ${limit}ms 还没回来，主动断开")
                        runCatching { conn.disconnect() }
                    }
                    return@Thread
                }
                try { Thread.sleep(minOf(left, ABORT_POLL_MS)) } catch (e: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "llm-watchdog" }
        fun start() { thread.start() }
        fun stop() { alive.set(false); thread.interrupt() }
    }

    private fun endpoint(): String {
        if (cfg.useFreeModel) return "https://opencode.ai/zen/v1/chat/completions"
        val base = cfg.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.deepseek.com/v1/chat/completions"
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private var httpWarned = false
    private fun warnIfPublicHttp(baseUrl: String) {
        if (httpWarned) return
        val url = baseUrl.trim().lowercase()
        if (!url.startsWith("http://")) return
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
        val isLocal = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            (host.startsWith("172.") && host.substringAfter("172.").substringBefore(".").toIntOrNull() in 16..31)
        if (!isLocal) {
            Log.w("LlmClient", "⚠️ 正在使用公网明文 HTTP 地址（$baseUrl），API Key 会明文经过网络。建议改用 HTTPS。")
            httpWarned = true
        }
    }

    private fun buildBody(system: String, history: List<ChatTurn>): JSONObject {
        val messages = JSONArray()
        messages.put(JSONObject().apply { put("role", "system"); put("content", system) })
        history.forEach { turn ->
            messages.put(JSONObject().apply {
                put("role", turn.role)
                val image = turn.imageBytes
                if (image != null && image.isNotEmpty()) {
                    val parts = JSONArray()
                    parts.put(JSONObject().apply { put("type", "text"); put("text", turn.text) })
                    parts.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:${mimeOf(image)};base64,${b64(image)}")
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
            if (!cfg.thinking.thinkingOn) put("temperature", cfg.temperature)
            cfg.thinking.toggle?.let { toggle -> put("thinking", JSONObject().put("type", toggle)) }
            cfg.thinking.effort?.let { put("reasoning_effort", it) }
            put("stream", false)
        }
    }

    private fun fingerprint(system: String, history: List<ChatTurn>): List<Int> =
        buildList {
            add("system".hashCode() * 31 + system.hashCode())
            history.forEach { t ->
                val image = t.imageBytes
                add(t.role.hashCode() * 31 + t.text.hashCode() * 7 +
                    if (image == null) 0 else (t.imageId?.hashCode() ?: 0) * 13 + image.size)
            }
        }

    private fun commonPrefixLength(a: List<Int>, b: List<Int>): Int {
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return i
    }

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun mimeOf(bytes: ByteArray): String =
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) "image/jpeg" else "image/png"

    private fun parseResponse(raw: String, prefixReused: Int, prefixTotal: Int): Try {
        val root = try { JSONObject(raw) } catch (t: Throwable) {
            return Try.Retry("响应不是合法 JSON（${t.message}）").also { Log.w(TAG, "响应不是合法 JSON，原始内容前 300 字：${raw.take(300)}") }
        }
        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            Log.w(TAG, "返回里没有 choices：${raw.take(300)}")
            return Try.Retry("模型没返回任何内容")
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: return Try.Retry("返回结构里没有 message 字段").also { Log.w(TAG, "返回结构里没有 message：${raw.take(300)}") }
        val content = message.optString("content", "").ifBlank { message.optString("reasoning_content", "") }
        if (content.isBlank()) {
            Log.w(TAG, "模型返回了空内容：${raw.take(300)}")
            return Try.Retry("模型返回了空内容")
        }
        val usage = root.optJSONObject("usage")
        return Try.Ok(LlmResult.Ok(
            text = content,
            promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
            cacheHitTokens = usage?.optInt("prompt_cache_hit_tokens", 0) ?: 0,
            cacheMissTokens = usage?.optInt("prompt_cache_miss_tokens", 0) ?: 0,
            prefixReused = prefixReused,
            prefixTotal = prefixTotal,
        ))
    }

    private fun httpFailure(code: Int, body: String): Try {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull().orEmpty().ifBlank { body.take(200) }
        Log.w(TAG, "HTTP $code：$detail")
        val reason = when (code) {
            401, 403 -> "API Key 不对或没有权限。检查「设置 → 模型」里的 Key。"
            404 -> "接口地址或模型名不对。地址是 ${endpoint()}，模型是 ${cfg.model}。"
            408 -> "服务端等我们等超时了。"
            429 -> "请求太频繁或额度用完了。"
            400 -> when {
                detail.contains("image", true) || detail.contains("vision", true) -> "这个模型不接受图片输入。要多模态模型（比如 deepseek-flash）。"
                detail.contains("model", true) -> "模型名不对：${cfg.model}。"
                else -> "请求被拒绝。"
            }
            in 500..599 -> "服务端出错了。"
            else -> "请求失败。"
        }
        val retryable = code == 408 || code == 429 || code in 500..599
        return if (retryable) Try.Retry("HTTP $code（$reason）")
        else Try.Fatal("$reason\n服务端说明：$detail")
    }

    private fun throwableToTry(t: Throwable): Try = when (t) {
        is java.net.SocketTimeoutException -> Try.Retry("超时（${cfg.timeoutMs / 1000}s 没等到响应）")
        is javax.net.ssl.SSLException -> Try.Fatal("HTTPS 握手失败：${t.message}")
        is java.io.InterruptedIOException -> Try.Retry("连接被中断")
        is java.net.ConnectException -> Try.Retry("连不上服务端")
        is java.net.UnknownHostException -> Try.Retry("域名解析失败（${cfg.baseUrl}）")
        is java.net.SocketException -> Try.Retry("连接断了（${t.message}）")
        is java.io.IOException -> Try.Retry("网络出错（${t.message}）")
        else -> Try.Fatal("请求出错：${t.javaClass.simpleName} ${t.message}")
    }

    fun describe(): String =
        "模型=${cfg.model} 地址=${endpoint()} 图片=${cfg.detail} 思考=${cfg.thinking.label} 重发=${cfg.maxAttempts}次"

    companion object {
        private const val TAG = "LlmClient"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val RETRY_BASE_DELAY_MS = 1_500L
        const val WATCHDOG_GRACE_MS = 5_000L
        const val ABORT_POLL_MS = 100L
        const val RETRY_TOTAL_BUDGET_MS = 300_000L
        const val ABORT_MESSAGE = "已停止（请求被中断）"
    }
}
