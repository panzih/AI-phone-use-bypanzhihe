package com.aiphone.assistant.llm

import com.aiphone.assistant.data.ThinkingMode
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
    /**
     * 思考模式。
     *
     * 官方文档：DeepSeek **默认就开着思考**（强度 high）。见 ThinkingMode ——
     * 这里只是把控制权透传出去，`SERVER_DEFAULT` 表示一个参数都不发。
     */
    val thinking: ThinkingMode = ThinkingMode.SERVER_DEFAULT,
    val timeoutMs: Int = 120_000,
)

/**
 * 一条对话消息。
 *
 * ## 图片为什么必须挂在消息自己身上
 *
 * 原来是"图片单独传、只挂在最后一条 user 消息上"，历史里的图片一律丢掉。
 * 那个设计**会把上下文缓存彻底打废**：
 *
 *   第 1 次请求： [system][user1 + 图]
 *   第 2 次请求： [system][user1 无图][assistant1][user2 + 图]
 *
 * 两次请求的 **user1 不是同一个东西**（一次带图一次不带），token 序列
 * 从 user1 就分叉了 —— 服务端的缓存按**最长公共前缀**匹配，于是能复用的
 * 只剩 system 那一段。也就是说历史越长、步数越多，命中的越少，
 * 每一步都在按全价重算前面所有内容。
 *
 * 正确做法是让消息**逐字不变地**重复出现：图片跟着它所属的那条消息，
 * 之后每次请求都原样再发一遍。这样前缀才是真正只增不改的。
 *
 * 代价是每次请求的请求体里会带上之前用过的图片（一张 60~90KB 的 PNG，
 * base64 之后约 120KB）。但命中缓存的那部分便宜很多，
 * 而且图片是按需才要的（见 Agent 的 need_image），数量很少。
 */
data class ChatTurn(
    val role: String,
    val text: String,
    /** 这条消息附带的截图。之后每次请求都会原样重发 —— 这是缓存命中的前提 */
    val imagePng: ByteArray? = null,
) {
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
        /**
         * 上下文缓存命中/未命中的 token 数。
         *
         * 服务端会按**最长的公共前缀**复用上一次的计算结果，
         * 命中的那部分便宜很多。字段名沿用服务端的
         * prompt_cache_hit_tokens / prompt_cache_miss_tokens；
         * 不支持这个特性的服务不会返回，保持 0 即可。
         */
        val cacheHitTokens: Int = 0,
        val cacheMissTokens: Int = 0,
        /**
         * 上一次请求的消息，有多少条被原样复用了。
         *
         * 分母是**上一次的条数** —— 这个指标要回答的是"上一次请求是不是
         * 被完整复用了"。等于 [prefixTotal] 就是完整复用（正常情况），
         * 小于就说明历史中途被改动过，那之后的缓存全都用不上。
         *
         * [prefixTotal] 为 0 表示这是本次任务的第一次请求，无前缀可谈。
         *
         * 之所以要单独统计：服务端只报 token 数，不会告诉你前缀断在哪一条。
         */
        val prefixReused: Int = 0,
        val prefixTotal: Int = 0,
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
     * 上一次请求的消息指纹。
     *
     * 默认是"一次任务内"的比对。但上下文跨任务续接之后，任务的第一次
     * 请求要和**上一个任务的最后一次请求**比才有意义 —— 所以这个值
     * 可以外部注入（见 [lastFingerprints]）。
     */
    private var lastFingerprints: List<Int> = emptyList()

    /**
     * 上一个请求的指纹链。任务结束时读它、存盘，下一次任务注入回来。
     *
     * 这样"前缀复用"这行自查日志在跨任务时也成立：复用率低就说明
     * 我们**自己**把前缀改了（比如系统提示词里的记忆换了内容），
     * 而不是服务端缓存的问题。
     */
    fun fingerprintChain(): List<Int> = lastFingerprints

    /** 注入上一个任务留下的指纹链。空表示这是一段全新上下文 */
    fun seedFingerprints(chain: List<Int>) {
        lastFingerprints = chain
    }

    /**
     * 发一次请求。
     *
     * 阻塞式，调用方必须放到 IO 线程（Agent 里用 withContext 包了）。
     *
     * ## history 就是实际发出去的内容
     *
     * 这一点是刻意设计的：调用方把本轮 user 消息（连同它的截图）**追加进
     * history** 之后直接传进来。`history` 和真正发出去的 messages 是同一份
     * 东西，不存在"发一套、记另一套"的可能。
     *
     * 为什么重要：服务端的上下文缓存是**按前缀匹配**的。只要有一轮
     * 记录和实际发送的内容不一致，前缀就从那里断开，**之后所有轮次
     * 全部缓存未命中**。
     *
     * 这里踩过两次同一个坑，值得记下来：
     *   1. 发出去的消息带着控件树，历史里记的却是一句"已执行"
     *   2. 图片只挂在最后一条 user 上 —— 同一条消息在两次请求里
     *      一次带图一次不带，前缀从第一条 user 就分叉了
     *
     * 两次的教训是同一个：**发出去的东西必须逐字不变地留在历史里。**
     */
    fun chat(
        system: String,
        history: List<ChatTurn>,
        /**
         * 请求体写完之后回调一次 —— 也就是"传完了，开始等模型"。
         *
         * 上传和等待是两件体感完全不同的事：上传快则几百毫秒、
         * 慢则好几秒（截图 base64 之后一两兆），等待则是另一段。
         * 分开报给用户，他才知道卡在哪一段。
         *
         * 回调在 IO 线程上执行。
         */
        onUploaded: (() -> Unit)? = null,
    ): LlmResult {
        if (cfg.apiKey.isBlank()) {
            return LlmResult.Fail("还没填 API Key。到「设置 → 模型」里填一个。")
        }

        // 和上一次请求比对前缀。这一步很便宜（只比指纹），
        // 但它是"缓存为什么没命中"这个问题唯一能自己回答的部分
        val fingerprints = fingerprint(system, history)
        val previous = lastFingerprints
        val reused = commonPrefixLength(previous, fingerprints)
        lastFingerprints = fingerprints

        val body = buildBody(system, history)

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

            val payload = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            // 传完了，接下来是等服务端算 —— 切换阶段
            onUploaded?.invoke()

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""

            if (code !in 200..299) {
                return LlmResult.Fail(explainHttpError(code, text))
            }

            // 分母用**上一次**的消息条数：这个指标要回答的是
            // "上一次请求是不是被完整复用了"，而不是"新请求多长"
            parseResponse(text, reused, previous.size)
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
                    val image = turn.imagePng
                    // 图片跟着它所属的那条消息，每次请求都原样重发。
                    // 这是前缀能被缓存命中的前提（见 ChatTurn 的说明）
                    if (image != null && image.isNotEmpty()) {
                        val parts = JSONArray()
                        parts.put(
                            JSONObject().apply {
                                put("type", "text")
                                put("text", turn.text)
                            }
                        )
                        parts.put(
                            JSONObject().apply {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    JSONObject().apply {
                                        put("url", "data:image/png;base64,${b64(image)}")
                                        put("detail", cfg.detail)
                                    },
                                )
                            }
                        )
                        put("content", parts)
                    } else {
                        put("content", turn.text)
                    }
                }
            )
        }

        return JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            // 思考模式下服务端会忽略 temperature（官方：不报错，也不生效），
            // 那就干脆不传 —— 免得用户以为调了它有用
            if (!cfg.thinking.thinkingOn) {
                put("temperature", cfg.temperature)
            }
            cfg.thinking.toggle?.let { toggle ->
                put("thinking", JSONObject().put("type", toggle))
            }
            cfg.thinking.effort?.let { put("reasoning_effort", it) }
            put("stream", false)
        }
    }

    /**
     * 每条消息的指纹：角色 + 文本 + **有没有图（以及哪张图）**。
     *
     * 用 identityHashCode 而不是对整个 ByteArray 求哈希：图片是几百 KB，
     * 每一步都全量哈希没意义 —— 同一个 ByteArray 实例被反复重发，
     * 身份不变就足够说明"还是那张图"。
     */
    private fun fingerprint(system: String, history: List<ChatTurn>): List<Int> =
        buildList {
            add("system".hashCode() * 31 + system.hashCode())
            history.forEach { t ->
                add(
                    t.role.hashCode() * 31 +
                        t.text.hashCode() * 7 +
                        (t.imagePng?.let { System.identityHashCode(it) * 13 + it.size } ?: 0)
                )
            }
        }

    /** 两个指纹序列从头开始有多少个相同 */
    private fun commonPrefixLength(a: List<Int>, b: List<Int>): Int {
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return i
    }

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    // ------------------------------------------------------------------
    // 响应解析与报错翻译
    // ------------------------------------------------------------------

    private fun parseResponse(raw: String, prefixReused: Int, prefixTotal: Int): LlmResult {
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
                cacheHitTokens = usage?.optInt("prompt_cache_hit_tokens", 0) ?: 0,
                cacheMissTokens = usage?.optInt("prompt_cache_miss_tokens", 0) ?: 0,
                prefixReused = prefixReused,
                prefixTotal = prefixTotal,
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
    fun describe(): String =
        "模型=${cfg.model} 地址=${endpoint()} 图片=${cfg.detail} 思考=${cfg.thinking.label}"
}
