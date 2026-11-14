package com.aiphone.assistant.llm

import org.json.JSONObject

/**
 * Kilo 免密免费池的报文契约。
 *
 * 依据上游 `dsh-our-free-model/src/kilo.js`（2026-11-14 复核）：
 *
 * | 项 | 值 |
 * |---|---|
 * | 网关 | `https://ai-gateway.kilo.ai/api/v1`（旧 `api.kilo.ai/api/gateway` 同源） |
 * | 模型清单 | `GET /models`，只取 `isFree: true` 的行 |
 * | 对话 | `POST /chat/completions`，OpenAI Chat Completions + SSE |
 * | 凭据 | **无** —— 免费池不需要账号、Key 或登录态 |
 * | 归属头 | `http-referer: https://kilo.ai/`、`x-title: Our Free Model` |
 * | 思考 | 统一 `reasoning` 对象：`{"effort":"low|medium|high"}`；关闭是 `{"enabled":false}` |
 *
 * 和 OpenCode 车道不同，这里**没有客户端指纹闸门**，也不要求官方提示/工具集，
 * 所以请求体保持最小：model + messages + stream。
 *
 * 限流是**按模型 × 每日**（`limit_rpd/...`），不是按 IP。一个模型打满只影响
 * 它自己；`kilo-auto/free` 会自动轮换，所以作为兜底模型。
 */
object KiloWire {

    const val BASE_URL = "https://ai-gateway.kilo.ai/api/v1"

    const val CHAT_PATH = "/chat/completions"

    const val MODELS_PATH = "/models"

    /** 自动路由，免费池里最稳的兜底。 */
    const val FALLBACK_MODEL = "kilo-auto/free"

    const val USER_AGENT = "dsh-our-free-model"

    private val ATTRIBUTION = linkedMapOf(
        "http-referer" to "https://kilo.ai/",
        "x-title" to "Our Free Model",
    )

    fun chatUrl(): String = BASE_URL + CHAT_PATH

    fun modelsUrl(): String = BASE_URL + MODELS_PATH

    /**
     * 一次请求的头。免费池**不要 Authorization**；多发一个空 Bearer 反而可能被
     * 当成付费 id 的凭据去校验。
     */
    fun headersFor(stream: Boolean, accept: String? = null): LinkedHashMap<String, String> {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to (accept ?: if (stream) "text/event-stream" else "application/json"),
            "User-Agent" to USER_AGENT,
        )
        headers.putAll(ATTRIBUTION)
        return headers
    }

    fun applyHeaders(
        conn: java.net.HttpURLConnection,
        stream: Boolean,
        accept: String? = null,
    ) {
        headersFor(stream, accept).forEach { (name, value) ->
            conn.setRequestProperty(name, value)
        }
    }

    // ------------------------------------------------------------------
    // 错误判定
    // ------------------------------------------------------------------

    /** Kilo 的错误信封把分类放在 `error.code` / `error_type` / `error.type` 三处之一。 */
    fun errorType(body: String): String? = runCatching {
        val root = JSONObject(body)
        val error = root.optJSONObject("error")
        error?.optString("code")?.takeIf { it.isNotBlank() }
            ?: root.optString("error_type").takeIf { it.isNotBlank() }
            ?: error?.optString("type")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 429 / 日限额：换模型或走 [FALLBACK_MODEL]，重发同一模型没有意义。 */
    fun rateLimited(body: String): Boolean {
        val type = errorType(body)?.lowercase().orEmpty()
        if (type == "429" || type.contains("rate_limit") || type.contains("ratelimit")) return true
        return body.contains("Rate limit exceeded", ignoreCase = true) ||
            body.contains("Daily limit reached", ignoreCase = true)
    }
}
