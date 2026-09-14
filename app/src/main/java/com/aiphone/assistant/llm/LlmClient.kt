package com.aiphone.assistant.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型配置。
 *
 * 刻意做成独立的小数据类，而不是直接吃 AppSettings —— 这样
 * LlmClient 不依赖界面层和 SharedPreferences，单独测试也方便。
 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /**
     * 图片精度。
     *
     *   original 保留原图 —— **手机 UI 文字多，必须用这个**
     *   low      服务端压到 512×512，界面上的字会糊掉
     */
    val detail: String = "original",
    /**
     * 温度。
     *
     * 这里默认 1.0 而不是写代码时习惯的 0.1。
     * 官方的视觉场景评测用的就是 1.0，调低反而会让模型在
     * "看不清楚该点哪"时变得犹豫、反复输出同一个无效动作。
     */
    val temperature: Double = 1.0,
    val timeoutMs: Int = 120_000,
)

/** 一条对话消息（纯文本；图片单独传） */
data class ChatTurn(val role: String, val text: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/** 一次请求的结果 */
sealed class LlmResult {
    data class Ok(
        val text: String,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    ) : LlmResult()

    /** message 是可以直接显示给用户的中文原因 */
    data class Fail(val message: String) : LlmResult()
}

/**
 * OpenAI 兼容的对话客户端。
 *
 * ## 为什么不用 OkHttp / Retrofit
 *
 * 只有两个请求（一次请求体、一个响应），用 `HttpURLConnection` 就够，
 * 而它是 JDK 自带的。这个项目从桌面版开始就坚持零第三方依赖 ——
 * 少一个依赖就少一份体积、一份版本冲突、一份构建风险。
 *
 * ## 图片放在哪条消息里
 *
 * 服务端要求**图片只能出现在 user 消息里**，放 system 或 assistant
 * 会直接返回 400。这一点很容易踩，所以本类在构造消息时强制保证，
 * 调用方不用操心。
 */
class LlmClient(private val cfg: LlmConfig) {

    /**
     * 发一次请求。
     *
     * 阻塞式，调用方必须放到 IO 线程（Agent 里用 withContext 包了）。
     *
     * @param imagePng 本轮截图。**只有这一张**，历史轮次的图不带 ——
     *                 这是刻意的成本控制，见 Agent 里的说明。
     */
    fun chat(
        system: String,
        history: List<ChatTurn>,
        userText: String,
        imagePng: ByteArray?,
    ): LlmResult {
        if (cfg.apiKey.isBlank()) {
            return LlmResult.Fail("还没填 API Key。到「设置 → 模型」里填一个。")
        }

        val body = buildBody(system, history, userText, imagePng)

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

            if (code !in 200..299) {
                return LlmResult.Fail(explainHttpError(code, text))
            }

            parseResponse(text)
        } catch (t: Throwable) {
            LlmResult.Fail(explainThrowable(t))
        }
    }

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

    /**
     * 拼接口地址。
     *
     * 用户填什么的都有：`https://api.deepseek.com`、
     * `https://api.openai.com/v1`、甚至完整的 `.../chat/completions`。
     * 三种都要能用，所以按后缀判断，不硬编码。
     */
    private fun endpoint(): String {
        val base = cfg.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.deepseek.com/v1/chat/completions"
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun buildBody(
        system: String,
        history: List<ChatTurn>,
        userText: String,
        imagePng: ByteArray?,
    ): JSONObject {
        val messages = JSONArray()

        messages.put(
            JSONObject().apply {
                put("role", "system")
                put("content", system)
            }
        )

        history.forEach { turn ->
            messages.put(
                JSONObject().apply {
                    put("role", turn.role)
                    // 历史一律是纯文本 —— 图片只有当前这一轮有
                    put("content", turn.text)
                }
            )
        }

        // 当前轮：文本 + 图片
        val userMessage = JSONObject().apply {
            put("role", "user")
            if (imagePng == null || imagePng.isEmpty()) {
                put("content", userText)
            } else {
                val parts = JSONArray()
                parts.put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", userText)
                    }
                )
                parts.put(
                    JSONObject().apply {
                        put("type", "image_url")
                        put(
                            "image_url",
                            JSONObject().apply {
                                put("url", "data:image/png;base64,${b64(imagePng)}")
                                put("detail", cfg.detail)
                            },
                        )
                    }
                )
                put("content", parts)
            }
        }
        messages.put(userMessage)

        return JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            put("temperature", cfg.temperature)
            put("stream", false)
        }
    }

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    // ------------------------------------------------------------------
    // 响应解析与报错翻译
    // ------------------------------------------------------------------

    private fun parseResponse(raw: String): LlmResult {
        return try {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return LlmResult.Fail("模型没有返回任何内容。原始响应：${raw.take(300)}")
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: return LlmResult.Fail("返回结构里没有 message 字段：${raw.take(300)}")

            // 有些模型（尤其是带推理的）会把内容放在 reasoning_content，
            // content 为空。两个都看看，别直接判失败。
            val content = message.optString("content", "").ifBlank {
                message.optString("reasoning_content", "")
            }
            if (content.isBlank()) {
                return LlmResult.Fail("模型返回了空内容：${raw.take(300)}")
            }

            val usage = root.optJSONObject("usage")
            LlmResult.Ok(
                text = content,
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
            )
        } catch (t: Throwable) {
            LlmResult.Fail("响应不是合法 JSON：${t.message}\n原始内容：${raw.take(300)}")
        }
    }

    /**
     * 把 HTTP 错误翻译成看得懂的中文。
     *
     * 这一步不能省。原始报错是 `{"error":{"message":"..."}}` 这种，
     * 用户看到只会一头雾水。而这里最常见的两个坑 ——
     * Key 不对、模型不支持图片 —— 恰好都能从状态码和关键字判断出来。
     */
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
                detail.contains("model", true) ->
                    "模型名不对：${cfg.model}。"
                else -> "请求被拒绝。"
            }
            500, 502, 503, 504 -> "服务端出错了（$code），稍后重试。"
            else -> "请求失败，HTTP $code。"
        }
        return "$base\n服务端说明：$detail"
    }

    private fun explainThrowable(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException ->
            "请求超时。可能是网络慢，或者接口地址填错了。"
        is java.net.UnknownHostException ->
            "连不上 ${cfg.baseUrl}。检查手机网络，或者接口地址有没有写错。"
        is javax.net.ssl.SSLException ->
            "HTTPS 握手失败：${t.message}"
        else -> "请求出错：${t.javaClass.simpleName} ${t.message}"
    }

    /** 给连通性自检用的短描述 */
    fun describe(): String = "模型=${cfg.model} 地址=${endpoint()} 精度=${cfg.detail}"
}
