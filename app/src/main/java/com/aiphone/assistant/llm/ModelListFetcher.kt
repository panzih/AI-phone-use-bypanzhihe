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
    fun fetch(
        baseUrl: String,
        apiKey: String,
        allowPublicCleartext: Boolean = false,
    ): List<String> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("还没填 API Key")
        }
        // 和 LlmClient.chat 同一条规则：公网明文默认不把 Key 发出去。
        val publicHost = EndpointSafety.publicCleartextHost(baseUrl)
        if (publicHost != null && !allowPublicCleartext) {
            throw IllegalStateException(
                "公网明文 HTTP 地址（$publicHost）已被默认拦截：API Key 会以明文经过网络。" +
                    "请改用 https://，或到「设置 → 模型」打开「允许公网明文 HTTP」。"
            )
        }

        val url = modelsEndpoint(baseUrl)
        // 地址不合法时**先说人话**：以前这里直接把 new URL(...) 的
        // MalformedURLException 抛到界面上，用户看到的是
        // 「获取失败: no protocol: http://10.0.2.2;8899/v1/models」——
        // 一句英文 Java 异常，根本看不出是地址里混进了中文标点/漏了冒号。
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalStateException(
                "接口地址格式不对：要以 http:// 或 https:// 开头，现在填的是「$baseUrl」。" +
                    "检查一下是不是把冒号打成了分号、或者混进了空格/中文标点。" +
                    "常见写法：https://api.deepseek.com，或 http://192.168.1.10:11434/v1",
            )
        }
        Log.i(TAG, "拉模型列表：$url")

        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
        } catch (t: Throwable) {
            throw IllegalStateException(describeNetworkError(t, url), t)
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
        } catch (t: IllegalStateException) {
            throw t
        } catch (t: Throwable) {
            // 连不上 / 解析超时 / 读到一半断了：同样是"人话"，不是栈信息
            throw IllegalStateException(describeNetworkError(t, url), t)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 把网络异常翻译成用户能照做的中文（0.16.0）。
     *
     * 判据只按异常类型分：这几种的"下一步动作"完全不同 ——
     * 地址写错要改地址，域名解析失败要查网络/拼写，连不上要查端口，
     * 超时是对方慢。混成一句"获取失败"用户没法处理。
     */
    private fun describeNetworkError(t: Throwable, url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        return when (t) {
            is java.net.MalformedURLException ->
                "接口地址格式不对（$url），检查冒号、斜杠和空格"
            is java.net.UnknownHostException ->
                "域名解析失败：$host。检查地址拼写，或看看手机网络是否正常"
            is java.net.ConnectException ->
                "连不上 $host：端口没开、或被防火墙拦住了"
            is java.net.SocketTimeoutException ->
                "连接 $url 超时：服务端没响应（本地模型是不是没启动？）"
            is javax.net.ssl.SSLException ->
                "HTTPS 握手失败：$host 的证书有问题，或地址该用 http://"
            is java.io.IOException ->
                "网络出错（${t.message ?: t.javaClass.simpleName}）：$url"
            else ->
                "拉模型列表失败：${t.message ?: t.javaClass.simpleName}"
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
        val conn = (URL(FreeLaneWire.modelsUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            // 目录端点不需要会话头（实测不带也放行），但 UA / 客户端标记 / project
            // 跟主请求保持一致，免得将来闸门收紧时这里成为唯一漏项。
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer public")
            setRequestProperty("User-Agent", FreeLaneWire.CLIENT_UA)
            setRequestProperty("x-opencode-client", "cli")
            setRequestProperty("x-opencode-project", FreeLaneWire.projectIdFor(FreeLaneWire.session()))
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
     * Kilo 免密免费池：`GET /models` 里 `isFree: true` 的行。
     *
     * 不需要任何凭据；付费 id 在免密请求下会 401，所以只把免费池广播给用户。
     */
    fun fetchKiloFree(): List<String> {
        val conn = (URL(KiloWire.modelsUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            KiloWire.headersFor(stream = false, accept = "application/json").forEach { (name, value) ->
                setRequestProperty(name, value)
            }
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
                val row = data.optJSONObject(i) ?: continue
                if (!row.optBoolean("isFree", false)) continue
                val id = row.optString("id", "")
                if (id.isNotBlank()) ids.add(id)
            }
            ids.sort()
            if (ids.isEmpty()) throw IllegalStateException("接口里没有可用的 Kilo 免费模型")
            Log.i(TAG, "Kilo 免费模型 ${ids.size} 个：${ids.joinToString()}")
            return ids
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 网关目录里 85 个条目既含免费池、也含**付费目录**（claude / gpt / gemini /
     * kimi / grok …）。免费通道只能选免费的那批：     *   - `-free` 后缀的（"deepseek-v4.1-flash-free"、"glm-5.3-flash-free" …）
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
}
