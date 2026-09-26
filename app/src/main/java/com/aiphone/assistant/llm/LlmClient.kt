package com.aiphone.assistant.llm

import com.aiphone.assistant.data.ThinkingMode
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
     * 官方文档：DeepSeek **默认就开着思考**（强度 high），所以我们一律
     * 显式发一个档位，不再有"什么都不发、让服务端替你决定"的情况。
     * 见 ThinkingMode 的四档说明。
     */
    val thinking: ThinkingMode = ThinkingMode.OFF,
    val timeoutMs: Int = 120_000,
    /**
     * 一次调用最多试几次（首试 + 重发）。
     *
     * 大模型"卡住"最常见的形态是**服务端收下了请求却迟迟不吐字** ——
     * 干等到超时也没什么可等的，重发一次往往就好了。3 次是折中：
     * 单次上限 [timeoutMs]，加上退避，最坏在几分钟量级。
     *
     * 注意重发**不是免费的**：如果上一次其实只是"慢"而不是"死"，
     * 服务端可能已经把两份都算了钱。所以只对**确定失败**的情形重发
     * （超时、连接断、5xx、429），4xx 一律不重发 —— 那是配置问题，
     * 重发一百次结果一样，只会白烧额度。
     */
    val maxAttempts: Int = 3,
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
 * 代价是每次请求的请求体里会带上之前用过的图片。所以图片是**全分辨率 JPEG**
 * （q82，见 `AccessibilityChannel.screenshot()`）：全分辨率是为了让模型给的
 * 坐标和提示词里承诺的屏宽/屏高一致，JPEG 是为了别让每一步都重发一张
 * 1MB 级的 PNG。而且图片是按需才要的（capture / need_image），数量很少。
 */
data class ChatTurn(
    val role: String,
    val text: String,
    /**
     * 这条消息附带的截图字节（正常是 JPEG；副屏那条路编码失败时会退回 PNG）。
     * 之后每次请求都会原样重发 —— 这是缓存命中的前提，所以**不能重新编码**：
     * 必须是同一份 byte[]。MIME 由 [mimeOf] 按字节头判断，不写死。
     */
    val imageBytes: ByteArray? = null,
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
     * 正在飞的那条连接。
     *
     * 只在中断时才用得上：`HttpURLConnection` 的 **读** 有 `readTimeout` 兜着，
     * 但 **写** 没有 —— 官方 API 根本没有写超时。上传一个几百 KB 到几 MB 的
     * base64 请求体时，如果链路半死不活（对端不给 ACK），
     * `outputStream.write()` 会一直阻塞，用户看到的就是"卡死了"。
     *
     * 唯一的解法是从**另一个线程** `disconnect()`：它会关掉底层 socket，
     * 让阻塞中的读或写立刻抛 IOException，线程得以退出。
     */
    private val activeConn = AtomicReference<HttpURLConnection?>(null)

    /** 外部叫停（急停按钮 / 任务被停止）。置上之后不再重发 */
    private val abortFlag = AtomicBoolean(false)

    /**
     * 从外部打断当前这次请求。
     *
     * 由 [com.aiphone.assistant.agent.Agent] 的看门狗调用：用户按了急停，
     * 而请求正卡在几十秒的等待里 —— 这时候什么都不做的话，用户会以为
     * 程序死了。断开连接让它立刻失败、立刻收尾。
     *
     * 从任意线程调用都安全。
     */
    fun abort() {
        abortFlag.set(true)
        runCatching { activeConn.get()?.disconnect() }
    }

    /** 一次尝试的结论。三类：拿到了 / 值得重发 / 重发也是白费 */
    private sealed class Try {
        class Ok(val result: LlmResult.Ok) : Try()

        /** [why] 要短，它会出现在"第 2 次重发（HTTP 503）"这种给用户看的文案里 */
        class Retry(val why: String) : Try()

        /** [message] 是可以直接显示的中文原因；重发没有意义 */
        class Fatal(val message: String) : Try()
    }

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
         * **每次重发都会再回调一次** —— 重发当然也要重新上传。
         *
         * 回调在 IO 线程上执行。
         */
        onUploaded: (() -> Unit)? = null,
        /**
         * 每次准备重发之前回调一次（IO 线程）。
         *
         * 参数：这是第几次**尝试失败**（1 起）、最多允许几次、
         * 上一次为什么没成、这次要等多久再发。
         *
         * 存在的理由和 [onUploaded] 一样：让用户知道"它没死，是在等一会儿重发"，
         * 而不是干瞪着屏幕怀疑卡死了。
         */
        onRetry: ((attempt: Int, maxAttempts: Int, why: String, waitMs: Long) -> Unit)? = null,
        /**
         * 外部中断信号（急停）。返回 true 就不再重发、不再等待。
         *
         * 为什么不是一个普通的 `Boolean`：等待退避的那几秒也要能被叫停，
         * 否则"按了急停还要等 6 秒"就会变成常态。
         */
        shouldAbort: (() -> Boolean)? = null,
    ): LlmResult {
        if (cfg.apiKey.isBlank()) {
            return LlmResult.Fail("还没填 API Key。到「设置 → 模型」里填一个。")
        }

        // 公网明文 HTTP 警告：API Key 会明文经过网络。
        // 本地/局域网地址（localhost、127.0.0.1、10.0.2.2、192.168.x.x、10.x.x.x、172.16-31.x.x）
        // 是本地模型服务的常见场景，不警告。
        warnIfPublicHttp(cfg.baseUrl)

        // 上一次调用的中断标记属于上一次，清掉
        abortFlag.set(false)

        // 和上一次请求比对前缀。这一步很便宜（只比指纹），
        // 但它是"缓存为什么没命中"这个问题唯一能自己回答的部分。
        // ⚠️ 只在这里记一次 —— 重发不算新的"上一次请求"，
        // 记多了一次会让下一次的复用率统计永远对不上。
        val fingerprints = fingerprint(system, history)
        val previous = lastFingerprints
        val reused = commonPrefixLength(previous, fingerprints)
        lastFingerprints = fingerprints

        // 请求体只构造一次，重发时**逐字节复用同一份**。
        // 服务端按最长公共前缀命中缓存，重发同一份内容能直接吃上缓存；
        // 重新构造（哪怕内容完全等价）就没这个保证了。
        val body = buildBody(system, history)

        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + RETRY_TOTAL_BUDGET_MS
        val maxAttempts = cfg.maxAttempts.coerceAtLeast(1)
        var attempts = 0

        while (true) {
            if (isAborted() || shouldAbort?.invoke() == true) {
                return LlmResult.Fail(ABORT_MESSAGE)
            }

            attempts++
            val outcome = try {
                // 分母用**上一次**的消息条数：这个指标要回答的是
                // "上一次请求是不是被完整复用了"，而不是"新请求多长"
                attemptOnce(body, onUploaded, reused, previous.size)
            } catch (t: Throwable) {
                throwableToTry(t)
            }

            when (outcome) {
                is Try.Ok -> return outcome.result

                is Try.Fatal -> return LlmResult.Fail(outcome.message)

                is Try.Retry -> {
                    Log.w(TAG, "第 $attempts 次请求没成：${outcome.why}")
                    if (attempts >= maxAttempts) {
                        return giveUp(attempts, outcome.why, startedAt)
                    }
                    // 剩下的预算不够再跑一趟完整的 timeoutMs，就别起新的了 ——
                    // 否则"重发"会变成没有上限的干等。反过来，
                    // 快速失败（连接被拒、5xx）不占预算，能多试几次。
                    if (System.currentTimeMillis() + cfg.timeoutMs > deadline) {
                        return giveUp(attempts, "${outcome.why}，且总耗时已超预算", startedAt)
                    }
                    val wait = backoffMs(attempts)
                    onRetry?.invoke(attempts, maxAttempts, outcome.why, wait)
                    if (!sleepWithAbort(wait, shouldAbort)) {
                        return LlmResult.Fail(ABORT_MESSAGE)
                    }
                }
            }
        }
    }

    /**
     * 一次尝试：建连 → 上传 → 等响应 → 解析。
     *
     * 全程被一个看门狗线程盯着（见 [Watchdog]），
     * 到点没回来就断开，转成"超时"交给外面的重发循环。
     */
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
            setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            setRequestProperty("Accept", "application/json")
        }
        activeConn.set(conn)
        val watched = Watchdog(conn)
        watched.start()

        return try {
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            // ⚠️ 这里必须再查一次中断。
            //
            // `openConnection()` 只是造了个对象、**还没连**（真正建连发生在
            // 第一次 I/O 时）。如果 abort() 落在"设好 activeConn" 与"真正建连"
            // 之间，那次 disconnect() 落在未连接的 socket 上是**空操作**，
            // 而 abortFlag 又已经置上了 —— 结果就是连接照样建起来、
            // 请求照样发出去、然后卡满整个 readTimeout。用户按了急停却要等两分钟。
            // 这一行把这个窗口堵掉。
            if (isAborted()) {
                runCatching { conn.disconnect() }
                return Try.Fatal(ABORT_MESSAGE)
            }

            // 传完了，接下来是等服务端算 —— 切换阶段
            onUploaded?.invoke()

            val code = conn.responseCode
            val text = if (code in 200..299) {
                readAll(conn.inputStream)
            } else {
                readAll(conn.errorStream)
            }

            if (code !in 200..299) {
                httpFailure(code, text)
            } else {
                parseResponse(text, prefixReused, prefixTotal)
            }
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

    /** 把响应体读干净。必须读到 EOF，否则这条连接不会被复用 */
    private fun readAll(stream: java.io.InputStream?): String =
        stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        } ?: ""

    // ------------------------------------------------------------------
    // 重发策略
    // ------------------------------------------------------------------

    /**
     * 退避时长：1.5s → 3s → 6s，各带 ±20% 抖动。
     *
     * 抖动是为了避免"多个任务同时失败、同时重发"挤在一起 ——
     * 服务端本来就在出问题，整齐划一地猛敲它只会让它更糟。
     */
    private fun backoffMs(attempt: Int): Long {
        val base = RETRY_BASE_DELAY_MS shl (attempt - 1)
        val jitter = (base * 0.2 * (Math.random() * 2 - 1)).toLong()
        return (base + jitter).coerceAtLeast(400L)
    }

    /**
     * 可被叫停的等待。
     *
     * 一整段 `Thread.sleep(6000)` 会让"按了急停还要等六秒"变成常态，
     * 所以切成 100ms 一片，每片都看一眼中断标记。
     *
     * @return true = 等满了；false = 中途被叫停
     */
    private fun sleepWithAbort(totalMs: Long, shouldAbort: (() -> Boolean)?): Boolean {
        var left = totalMs
        while (left > 0) {
            if (isAborted() || shouldAbort?.invoke() == true) return false
            val step = minOf(left, ABORT_POLL_MS)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            left -= step
        }
        return true
    }

    private fun isAborted(): Boolean = abortFlag.get()

    private fun giveUp(attempts: Int, why: String, startedAt: Long): LlmResult.Fail {
        val spent = (System.currentTimeMillis() - startedAt) / 1000
        return LlmResult.Fail(
            "模型连续 $attempts 次没有正常响应（最后一次：$why），共花了 ${spent}s。\n" +
                "网络不稳就重发一次任务；每次都这样，检查手机网络，" +
                "或者看「设置 → 模型」里的地址和服务商状态。",
        )
    }

    /**
     * 看门狗：单次尝试的总时限。
     *
     * `readTimeout` 只管读、`connectTimeout` 只管建连，**写没有超时**。
     * 上传大请求体时卡住是真实存在的一种"卡死"，只能靠外面的线程
     * 定时 `disconnect()` 把它掀掉。
     *
     * 时限故意比 `readTimeout` 宽几秒：正常情况下让 `readTimeout` 先赢，
     * 那样异常类型明确（SocketTimeoutException）、文案也好写；
     * 看门狗只在"卡在读和写之外的地方"时才接管。
     */
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
                try {
                    Thread.sleep(minOf(left, ABORT_POLL_MS))
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "llm-watchdog"
        }

        fun start() {
            thread.start()
        }

        fun stop() {
            alive.set(false)
            thread.interrupt()
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

    /**
     * 公网明文 HTTP 警告。
     *
     * 本地/局域网地址是本地模型服务的常见场景，不警告；
     * 公网 http:// 地址会把 API Key 明文传出去，打一条 warning 提醒。
     * 只在第一次遇到时打（用一个标志位，避免每步都刷屏）。
     */
    private var httpWarned = false

    private fun warnIfPublicHttp(baseUrl: String) {
        if (httpWarned) return
        val url = baseUrl.trim().lowercase()
        if (!url.startsWith("http://")) return
        // 提取 host 部分
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
        val isLocal = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            (host.startsWith("172.") && host.substringAfter("172.").substringBefore(".").toIntOrNull() in 16..31)
        if (!isLocal) {
            Log.w("LlmClient", "⚠️ 正在使用公网明文 HTTP 地址（$baseUrl），API Key 会明文经过网络。建议改用 HTTPS。")
            httpWarned = true
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
                    val image = turn.imageBytes
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
                                        put("url", "data:${mimeOf(image)};base64,${b64(image)}")
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
                        (t.imageBytes?.let { System.identityHashCode(it) * 13 + it.size } ?: 0)
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

    /**
     * 按字节头判断图片 MIME。
     *
     * 为什么不写死 `image/jpeg`：不同来源的编码不一定一样 —— 主通道是 JPEG，
     * 副屏（跑在 Shizuku 用户服务里抓帧）优先 JPEG、编码失败会退回 PNG。
     * MIME 声明错了会被多模态服务直接拒掉，而嗅探两个字节就够准。
     */
    private fun mimeOf(bytes: ByteArray): String =
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            "image/jpeg"
        } else {
            "image/png"
        }

    // ------------------------------------------------------------------
    // 响应解析与报错翻译
    // ------------------------------------------------------------------

    private fun parseResponse(raw: String, prefixReused: Int, prefixTotal: Int): Try {
        val root = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            // 响应截断（连接被中途掐断）也会长得像"不合法 JSON"，
            // 所以归到可重发，而不是直接判死
            return Try.Retry("响应不是合法 JSON（${t.message}）").also {
                Log.w(TAG, "响应不是合法 JSON，原始内容前 300 字：${raw.take(300)}")
            }
        }

        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            Log.w(TAG, "返回里没有 choices：${raw.take(300)}")
            return Try.Retry("模型没返回任何内容")
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: return Try.Retry("返回结构里没有 message 字段").also {
                Log.w(TAG, "返回结构里没有 message：${raw.take(300)}")
            }

        // 有些模型（尤其是带推理的）会把内容放在 reasoning_content，
        // content 为空。两个都看看，别直接判失败。
        val content = message.optString("content", "").ifBlank {
            message.optString("reasoning_content", "")
        }
        if (content.isBlank()) {
            Log.w(TAG, "模型返回了空内容：${raw.take(300)}")
            // 温度默认 1.0，重发一次拿到不同结果的概率不低 —— 值得再试
            return Try.Retry("模型返回了空内容")
        }

        val usage = root.optJSONObject("usage")
        return Try.Ok(
            LlmResult.Ok(
                text = content,
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
                cacheHitTokens = usage?.optInt("prompt_cache_hit_tokens", 0) ?: 0,
                cacheMissTokens = usage?.optInt("prompt_cache_miss_tokens", 0) ?: 0,
                prefixReused = prefixReused,
                prefixTotal = prefixTotal,
            )
        )
    }

    /**
     * HTTP 非 2xx 的处置：**翻译成人话，并判断值不值得重发**。
     *
     * 这一步不能省。原始报错是 `{"error":{"message":"..."}}` 这种，
     * 用户看到只会一头雾水。而这里最常见的两个坑 ——
     * Key 不对、模型不支持图片 —— 恰好都能从状态码和关键字判断出来。
     *
     * 分界线是"重发会不会有不同结果"：
     *   5xx / 429 / 408 —— 服务端自己或链路的事，等一会儿可能就好了
     *   其余 4xx       —— 我们的请求本身有问题，重发一百次也还是一样
     */
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
                detail.contains("image", true) || detail.contains("vision", true) ->
                    "这个模型不接受图片输入。要多模态模型（比如 deepseek-flash）。"
                detail.contains("model", true) -> "模型名不对：${cfg.model}。"
                else -> "请求被拒绝。"
            }
            in 500..599 -> "服务端出错了。"
            else -> "请求失败。"
        }

        val retryable = code == 408 || code == 429 || code in 500..599
        return if (retryable) {
            Try.Retry("HTTP $code（$reason）")
        } else {
            Try.Fatal("$reason\n服务端说明：$detail")
        }
    }

    /**
     * 异常 → 处置。
     *
     * 绝大多数网络异常是**暂时性**的：连接被拒、连接被重置、
     * 域名解析抖一下。重发一次基本都能过去。
     *
     * 唯一例外是 SSL 握手失败 —— 那通常意味着证书/域名配置不对，
     * 重发只会浪费时间，直接判死更快让用户看到真正的问题。
     */
    private fun throwableToTry(t: Throwable): Try = when (t) {
        // ⚠️ SocketTimeoutException 是 InterruptedIOException 的子类，
        // 这两个分支的先后顺序不能调
        is java.net.SocketTimeoutException ->
            Try.Retry("超时（${cfg.timeoutMs / 1000}s 没等到响应）")
        is java.io.InterruptedIOException -> Try.Retry("连接被中断")
        is java.net.ConnectException -> Try.Retry("连不上服务端")
        is java.net.UnknownHostException -> Try.Retry("域名解析失败（${cfg.baseUrl}）")
        is java.net.SocketException -> Try.Retry("连接断了（${t.message}）")
        is java.io.IOException -> Try.Retry("网络出错（${t.message}）")
        is javax.net.ssl.SSLException -> Try.Fatal("HTTPS 握手失败：${t.message}")
        else -> Try.Fatal("请求出错：${t.javaClass.simpleName} ${t.message}")
    }

    /** 给连通性自检用的短描述 */
    fun describe(): String =
        "模型=${cfg.model} 地址=${endpoint()} 图片=${cfg.detail} 思考=${cfg.thinking.label} " +
            "重发=${cfg.maxAttempts}次"

    companion object {
        private const val TAG = "LlmClient"

        /** 建连超时。和读超时分开：连不上是"地址/网络"问题，等久了没意义 */
        const val CONNECT_TIMEOUT_MS = 20_000

        /** 重发退避的基准：1.5s、3s、6s… */
        const val RETRY_BASE_DELAY_MS = 1_500L

        /** 看门狗比 readTimeout 宽出来的余量，见 [Watchdog] 的说明 */
        const val WATCHDOG_GRACE_MS = 5_000L

        /** 等待中断标记的轮询间隔 */
        const val ABORT_POLL_MS = 100L

        /**
         * 一次 [chat] 的总时间预算。
         *
         * 没有它的话，"每次都卡满 120s"会变成 3 趟加起来六分钟 ——
         * 手机任务里这个体感已经接近死机了。有了预算，**快失败能多试、
         * 慢卡死少试**：5xx 那种几百毫秒就返回的，三次都跑得完；
         * 真卡满 120s 的，第二趟之后就不给新的了。
         */
        const val RETRY_TOTAL_BUDGET_MS = 300_000L

        /** 用户按了急停、请求被中断时的返回文案 */
        const val ABORT_MESSAGE = "已停止（请求被中断）"
    }
}
