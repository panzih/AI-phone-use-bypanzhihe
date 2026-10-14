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
}
