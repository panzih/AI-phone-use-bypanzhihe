package com.aiphone.assistant.llm

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 OpenAI 兼容接口拉取可用模型列表。
 *
 * 调的是标准的 GET /v1/models，DeepSeek / OpenAI / 任何兼容服务商都支持。
 * 不需要 LlmClient 那套重发/看门狗——这就是个一次性 GET，失败了让用户重试就行。
 */
object ModelListFetcher {

    private const val TAG = "ModelListFetcher"

    /**
     * 拉模型列表。
     *
     * @throws Exception 网络错误 / 非 2xx / 返回不是合法 JSON 时抛出，
     *         message 是人能看懂的中文原因
     */
    fun fetch(baseUrl: String, apiKey: String): List<String> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("还没填 API Key")
        }

        val url = modelsEndpoint(baseUrl)
        Log.i(TAG, "拉模型列表：$url")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code：${err.take(200)}")
            }

            val root = JSONObject(body)
            val data = root.optJSONArray("data")
                ?: throw IllegalStateException("返回里没有 data 字段")

            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id", "")
                if (id.isNotBlank()) ids.add(id)
            }
            // 排序一下，不然每次顺序都不一样，用户眼花
            ids.sort()
            return ids
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 免费通道：从网关接口**实时读取**可用的免费模型。
     *
     * 和 [fetch]（自定义通道，要 Key）不同：这里不需要任何凭证 —— 端点就是
     * 免费池自己的 `/zen/v1/models`，Headers 照抄主请求那套（Bearer public +
     * CLI 伪装）。实测这个端点不带 session 头也放行，所以这里不带。
     *
     * **为什么不再用内置名单**：内置那份是 2026-10-15 手工同步的，很快就过时 ——
     * 实测网关现在有 85 个条目，免费池里已经新上了 glm-5.3-flash-free、
     * beam-free、nemotron-3.5-super-vl-free 这些内置名单里没有的模型。
     * 与其维护一份必然过时的名单，不如每次打开选择器就问接口要（失败再退回内置）。
     *
     * @throws Exception 网络错误 / 非 2xx / 返回不是合法 JSON 时抛出，
     *         message 是人能看懂的中文原因
     */
    fun fetchFree(): List<String> {
        val conn = (URL(FREE_MODELS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer public")
            setRequestProperty("User-Agent", "opencode/1.18.31 (linux arm64; node20.0.0)")
            setRequestProperty("x-opencode-client", "cli")
        }

        try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code：${err.take(200)}")
            }

            val data = JSONObject(body).optJSONArray("data")
                ?: throw IllegalStateException("返回里没有 data 字段")

            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.getJSONObject(i).optString("id", "")
                if (keepFreeModel(id)) ids.add(id)
            }
            ids.sort()
            if (ids.isEmpty()) throw IllegalStateException("接口里没有可用的免费模型")
            Log.i(TAG, "接口读到免费模型 ${ids.size} 个：${ids.joinToString()}")
            return ids
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 网关目录里 85 个条目既含免费池、也含**付费目录**（claude / gpt / gemini /
     * kimi / grok …）。免费通道只能选免费的那批：
     *
     *   - `-free` 后缀的（"deepseek-v4.1-flash-free"、"glm-5.3-flash-free" …）
     *   - 少数不带后缀但属于免费池的（"big-pickle"）
     *
     * 另外排掉目录里的测试占位条目（"test" —— 真的出现在目录里，
     * 选它必然报错，别让用户看见）。
     */
    internal fun keepFreeModel(id: String): Boolean =
        id != "test" && (id.endsWith("-free") || id == "big-pickle")

    /**
     * 拼 /v1/models 地址。
     *
     * 和 LlmClient.endpoint() 同一套容错逻辑：用户可能填 https://api.deepseek.com、
     * https://api.openai.com/v1、甚至完整的 .../chat/completions。
     */
    private fun modelsEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.deepseek.com/v1/models"
        return when {
            base.endsWith("/chat/completions") ->
                base.removeSuffix("/chat/completions") + "/models"
            base.endsWith("/v1") -> "$base/models"
            else -> "$base/v1/models"
        }
    }

    /** 免费池的模型目录（和 LlmClient 的免费端点同源：/zen/v1） */
    private const val FREE_MODELS_URL = "https://opencode.ai/zen/v1/models"
}
