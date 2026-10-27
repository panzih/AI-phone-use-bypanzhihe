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
     * 免费模式：走 opencode.ai 免密聚合网关。
     * 开启后忽略 baseUrl/apiKey，自动用 Bearer public + 伪造客户端指纹。
     */
    val useFreeModel: Boolean = false,
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
     * DEFAULT 档什么都不发，让服务端自己决定。
     */
    val thinking: ThinkingMode = ThinkingMode.DEFAULT,

    /**
     * CUSTOM 档要发的 reasoning_effort 原文（留空 = 什么都不发）。
     *
     * 单独一个字段而不是塞进 [thinking]：用户可能来回切档位，
     * 切回 CUSTOM 时上一次填的值还在，不用重打一遍。
     */
    val customEffort: String = "",
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
) {
    companion object {
        /**
         * 这套模型配置能不能发请求。
         *
         * 免费通道走网关的 `Bearer public`，**不需要用户填 Key**；自定义通道必须有。
         * 入口（MainActivity.submit）和 [LlmClient.chat] 必须共用这一份判据 ——
         * 2026-10-25 的线上问题就是入口只认 Key，把免费模式的新用户直接拦在门外：
         * 点发送 → runTask 提前 return → 协程 finally 立刻 stopService，
         * 悬浮窗服务还没 startForeground 就被停掉，系统抛
         * ForegroundServiceDidNotStartInTimeException 把整个 App 打崩。
         */
        fun missingApiKey(useFreeModel: Boolean, apiKey: String): Boolean =
            !useFreeModel && apiKey.isBlank()
    }
}

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
 * 从 user1 就分叉了 —— 服务端的缓存要求**完整匹配**一个前缀单元，
 * 于是它之后的内容全都用不上（只剩 system，因为它会被单独持久化成一个单元）。
 * 也就是说历史越长、步数越多，命中的越少，每一步都在按全价重算前面所有内容。
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
    /**
     * 图片的**内容标识** —— 由字节算出来，同一份图永远是同一个串。
     *
     * 为什么不能用 [System.identityHashCode] 认图：那个身份码只在**同一次任务内**
     * 成立。上下文落盘时图片跟着写进文件，下一次任务读回来是一个**新的数组**，
     * 身份码必然不同 —— 于是"前缀复用"会把历史判成"从这条起被改过"，
     * 整段缓存的命中就此让出去（实测：24 条里只复用得了 5 条）。
     *
     * 用内容算就不会有这个问题：字节一样 → 标识一样 → 指纹一样。
     * 懒算是因为一张全分辨率截图几百 KB，没必要每次请求都重算。
     */
    val imageId: String? by lazy { imageBytes?.let { imageIdOf(it) } }

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"

        private const val HEX = "0123456789abcdef"

        /**
         * 图片标识 = SHA-256 前 8 字节（16 位十六进制）+ 原始长度。
         *
         * 带上长度，是为了落盘后的文件名也能一眼看出有没有被截断；
         * 真正防撞的是哈希。
         */
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

/** 一次请求的结果 */
sealed class LlmResult {
    data class Ok(
        val text: String,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        /**
         * 上下文缓存命中/未命中的 token 数。
         *
         * 服务端把请求切成一个个**独立的"缓存前缀单元"**（在请求边界、公共前缀、
         * 固定 token 间隔处生成），只有**完整匹配**上某个单元才算命中 ——
         * Sliding Window Attention 之后就是这样，不再是"最长公共前缀"那么宽松。
         * 命中的那部分便宜 **50 倍**（$0.003 vs $0.15 / 1M，off-peak）。
         * 字段名沿用服务端的 prompt_cache_hit_tokens / prompt_cache_miss_tokens；
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

    /**
     * 免费通道的 session id：整个 App 进程复用一个。
     *
     * 免费池的额度是**按 session 记账**的，每个请求换一个新 id 会把额度打散成
     * 一堆"新会话"，反而更快撞 429。id 的编码规则（反相时间戳 + counter 1–63）
     * 在 [FreeLaneWire.newSessionId]。
     */
    private val freeSessionId = FreeLaneWire.newSessionId()

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
    /**
     * 上一次请求的「首字节」耗时（毫秒）。0 表示没测到。
     *
     * 为什么不是"首 token"：这个客户端是**一次性读完整个响应体**再解析的，
     * 流式那套只用于免费通道的 SSE 累积，没有逐 token 的回调点。
     * 所以这里量的是"请求写完 → 服务端开始回响应头"的时间 ——
     * 对非流式请求来说，它就是模型开始出结果前的等待，和 TTFT 是一个量级，
     * 界面上标"TTFT"但**别拿它当流式首 token**用。
     */
    @Volatile
    var lastTtftMs: Long = 0L
        private set

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
        if (LlmConfig.missingApiKey(cfg.useFreeModel, cfg.apiKey)) {
            return LlmResult.Fail("还没填 API Key。到「设置 → 模型」里填一个，或切到「免费」模式。")
        }

        // 公网明文 HTTP 警告：API Key 会明文经过网络。
        // 哪些算"本地不警告"由 EndpointSafety 统一判定（设置页共用同一份规则）。
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
        // 服务端要求完整匹配缓存前缀单元，重发同一份内容才保得住已有单元；
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
            if (cfg.useFreeModel) {
                // 免费通道：整套 v2 指纹（UA / 四个 session 头 / project=global）见
                // FreeLaneWire.applyHeaders。⚠️ 绝不要在这里补 x-opencode-request ——
                // 那是 v1 专用头，v2 收到就回 403。
                FreeLaneWire.applyHeaders(this, freeSessionId, stream = true)
            } else {
                // 自定义通道：普通 OpenAI 兼容请求，Accept 用 application/json
                setRequestProperty("Accept", "application/json")
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

            // 首字节：从"请求写完"到"服务端回了响应头"。这就是用户体感上的
            // "它想了多久才开始出东西"，比整次请求的耗时更能说明问题
            val waitStart = System.currentTimeMillis()
            val code = conn.responseCode
            lastTtftMs = System.currentTimeMillis() - waitStart
            val text = if (code in 200..299) {
                val raw = readAll(conn.inputStream)
                // 免费通道是 SSE 流，累积成普通 JSON 再交给解析器
                if (cfg.useFreeModel) accumulateSse(raw) else raw
            } else {
                readAll(conn.errorStream)
            }

            if (code !in 200..299) {
                // Retry-After 只在 429 有意义（免费池用它报"到额度重置还有多久"）
                val retryAfter = runCatching { conn.getHeaderField("Retry-After")?.trim()?.toLong() }.getOrNull()
                httpFailure(code, text, retryAfter)
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

    /**
     * 把免费通道的 SSE 事件流累积成一个普通的非流式 JSON 响应。
     *
     * opencode 免费池只回答流式请求（stream:true 才不 403），但 App 其余部分
     * 都按"一个 JSON 响应"来解析。所以这里把所有 `data: {...}` 事件里的
     * `choices[0].delta.content` 拼起来，再包成 `{"choices":[{"message":{"content":...}}]}`
     * 交给原来的 parseResponse —— 下游完全不用改。
     * `data: [DONE]` 表示结束；遇到解析不了的行直接跳过（SSE 里可能混心跳/注释）。
     *
     * ## 思维链（reasoning_content）也要读
     *
     * 思考模式打开时，模型先把思维链放在 `delta.reasoning_content`，正式回答
     * 放在 `delta.content`（DeepSeek 文档：两者同级）。之前这里**只拼 content**，
     * 思维链直接被丢掉 —— 平时的代价只是"看不见模型在想什么"，但有一类模型
     * 会**把全部输出都放进 reasoning_content、content 留空**（免费池里有
     * "固定思考、关不掉"的模型，网关会回 1210），这时整个回复会被当成空响应
     * 触发三连重发，最后报"模型返回了空内容"——从错误里根本看不出是这回事。
     *
     * 现在：content 为空时用思维链兜底（和 [parseResponse] 的兜底策略一致），
     * 两者都有时以 content 为准，并把思维链长度记进日志（这是"模型到底想没想"
     * 的唯一可观测点）。
     *
     * `internal` 是给单测留的口子（同 [buildBody]）。
     */
    internal fun accumulateSse(raw: String): String {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") return@forEach
            val json = try {
                JSONObject(payload)
            } catch (_: Throwable) {
                return@forEach
            }
            val choices = json.optJSONArray("choices") ?: return@forEach
            if (choices.length() == 0) return@forEach
            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return@forEach
            delta.optString("content")?.takeIf { it.isNotEmpty() }?.let { content.append(it) }
            delta.optString("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { reasoning.append(it) }
        }
        val answer = content.toString().ifBlank { reasoning.toString() }
        if (content.isBlank() && reasoning.isNotBlank()) {
            Log.i(TAG, "免费通道：模型只回了思维链（${reasoning.length} 字），用它兜底当回答")
        } else if (reasoning.isNotBlank()) {
            Log.i(TAG, "免费通道：模型输出含思维链 ${reasoning.length} 字（未进入动作解析）")
        }
        return JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", answer),
                    ),
                ),
            )
            .toString()
    }

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
     * 语音能力探针（T40）：发一段 1 秒静音 WAV，看这个通道收不收 `input_audio`。
     *
     * 不重试、不流式 —— 这一趟只要一个状态码。任何异常都折成 `code = 0`，
     * 调用方拿 [AudioProbe.describe] 就能说清是"不支持"还是"连不上"。
     */
    fun probeAudio(): AudioProbe.Outcome {
        val wav = AudioProbe.silentWav()
        val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val body = AudioProbe.buildBody(cfg.model, b64).toByteArray(Charsets.UTF_8)
        return try {
            val conn = (java.net.URL(endpoint()).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (cfg.useFreeModel) {
                    // 探针也走同一套 v2 指纹：少一个 session 头就是 403，
                    // 那样探针会错报"不支持语音"（其实是没进去门）
                    FreeLaneWire.applyHeaders(this, freeSessionId, stream = false, accept = "application/json")
                } else {
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                }
            }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            conn.disconnect()
            AudioProbe.Outcome(code in 200..299, code, text)
        } catch (t: Throwable) {
            AudioProbe.Outcome(false, 0, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 拼接口地址。
     *
     * 用户填什么的都有：`https://api.deepseek.com`、
     * `https://api.openai.com/v1`、甚至完整的 `.../chat/completions`。
     * 三种都要能用，所以按后缀判断，不硬编码。
     */
    private fun endpoint(): String {
        // 免费通道走 /inference/v1（2026-10 上游改版；旧的 /zen/v1 对话端点
        // 对免费 model 一律 403 FreeTierError）。端点与请求头的全部常量在 FreeLaneWire。
        if (cfg.useFreeModel) return FreeLaneWire.chatUrl()
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
     *
     * 判据在 [EndpointSafety] —— 设置页用同一份规则做常驻警告，
     * 这里只是补一条日志（日志用户不看，界面才看得见）。
     */
    private var httpWarned = false

    private fun warnIfPublicHttp(baseUrl: String) {
        if (httpWarned) return
        if (EndpointSafety.cleartextWarning(baseUrl) != null) {
            Log.w("LlmClient", "⚠️ 正在使用公网明文 HTTP 地址（$baseUrl），API Key 会明文经过网络。建议改用 HTTPS。")
            httpWarned = true
        }
    }

    /**
     * 组装请求体。
     *
     * `internal` 是给单测留的口子：免费通道契约的字段策略都在这里，
     * 上游对多余字段是严格校验的（见 thinking 的处理），必须有测试
     * 钉住"发什么、绝对不发什么"（`FreeLaneBodyTest`）。
     */
    internal fun buildBody(
        system: String,
        history: List<ChatTurn>,
    ): JSONObject {
        val messages = JSONArray()

        // 免费通道要往系统提示里补一句"read/shell 是占位、别调"。
        // 那两个工具是网关门禁逼着声明的，但它们是**真工具**（定义逐字来自官方 CLI），
        // 不打招呼模型真的会拿 read 去读文件。自定义通道不掺这句。
        val systemContent = if (cfg.useFreeModel) {
            "$system\n\n${FreeLaneWire.PLACEHOLDER_NOTE}"
        } else {
            system
        }

        messages.put(
            JSONObject().apply {
                put("role", "system")
                put("content", systemContent)
            }
        )

        history.forEach { turn ->
            messages.put(
                JSONObject().apply {
                    put("role", turn.role)
                    // 免费通道里不是每个模型都看图：nemotron-lightning / jev / beam /
                    // flint 收图片会直接报错。这里按模型能力把图**摘掉**，只发文字 ——
                    // 与其让整轮 400，不如让它在纯控件树模式下继续跑。
                    val image = if (cfg.useFreeModel && !FreeModelCatalog.supportsImages(cfg.model)) {
                        null
                    } else {
                        turn.imageBytes
                    }
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
                                        // 免费通道不带 detail 字段。
                                        // 2026-10-16 实测：space-bunny-free 的上游对
                                        // detail:"original" 直接 400 invalid_request
                                        // （"auto" / 不带字段都 200；把 image_url 摊平成
                                        // 纯字符串也 400，形态别动）。
                                        // 省略 = 由上游取默认值，是免费池的最小公倍数；
                                        // "original" 保真度的损失可以接受 —— 免费通道
                                        // 本来也没有别的保真度选项可用。
                                        if (!cfg.useFreeModel) {
                                            put("detail", cfg.detail)
                                        }
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
            // thinking 字段：免费通道不发"明确关闭"形态。
            //
            // 2026-10-16 线上事故（0.9.11 用户实测）：OFF 档发
            // `thinking:{"type":"disabled"}`，space-bunny-free 的上游对
            // 非标准字段做严格校验，直接回 400 invalid_request。
            // 同日 8 个免费模型 × 2 种形态的实测矩阵：只有 space-bunny 拒
            // disabled，而 enabled + reasoning_effort 被所有还能跑通的模型
            // 接受。"disabled" 对免费上游也不承载语义 —— 它们默认就不推理。
            // 所以 OFF 档在免费通道干脆不带字段；自定义通道（DeepSeek /
            // Kimi 等）保持原样，那边的 disabled 是有意义的。
            // 自定义档留空 = "用服务商默认"，那时连通开关都不发（发了 enabled
            // 却不说强度，某些上游会把思考强行打开，反而不是用户要的"别管"）
            // thinking 字段：免费通道**整个不发**。
            //
            // 2026-10-16 线上事故（0.9.11 用户实测）：OFF 档发
            // `thinking:{"type":"disabled"}`，space-bunny-free 的上游对
            // 非标准字段做严格校验，直接回 400 invalid_request。
            // 现在的免费池只认 `reasoning_effort` 一个表达（上游插件的
            // effortPatch 也只发这一个字段），多发一个 thinking 对象纯属风险。
            //
            // 自定义通道（DeepSeek / Kimi 等）保持原样，那边的 disabled 是有意义的。
            // 自定义档留空 = "用服务商默认"，那时连通开关都不发（发了 enabled
            // 却不说强度，某些上游会把思考强行打开，反而不是用户要的"别管"）
            val customBlank = cfg.thinking == ThinkingMode.CUSTOM && cfg.customEffort.isBlank()
            if (!cfg.useFreeModel) {
                cfg.thinking.toggle?.let { toggle ->
                    if (!customBlank) put("thinking", JSONObject().put("type", toggle))
                }
            }
            // effort 走 ThinkingMode.effortOf：CUSTOM 档的值来自用户输入，
            // 留空就**一个字段都不发**（等价「默认」，绝不发空串 —— 那多半 400）
            //
            // 免费通道再过一道 FreeModelCatalog：每个模型只认自己声明的档位
            // （nemotron-super 不认 max、jev 完全没有推理），发错了是
            // 400 invalid_value —— 网关会校验这个字段，不是忽略。
            val effort = ThinkingMode.effortOf(cfg.thinking, cfg.customEffort)
            if (cfg.useFreeModel) {
                FreeModelCatalog.resolveEffort(cfg.model, effort)?.let { put("reasoning_effort", it) }
            } else {
                effort?.let { put("reasoning_effort", it) }
            }
            // 免费通道（opencode.ai 免密池）的 v2 报文契约，全部细节见 FreeLaneWire：
            //   1. 端点 /inference/v1/chat/completions（/zen/v1 对免费 model 一律 403）
            //   2. body.stream = true（非流式直接 403）
            //   3. tools 必须**逐字**声明官方 CLI 的 read + shell 两个工具 ——
            //      v2 比对的是描述与参数 schema 本身，空桩已经失效
            //   4. **不发 tool_choice**：v2 见到 "none" 直接 403
            //   5. session id 的编码必须合法（FreeLaneWire.newSessionId）
            // 断言在 FreeLaneBodyTest 里钉着，改这条链路先跑它。
            if (cfg.useFreeModel) {
                put("tools", FreeLaneWire.tools())
            }
            // 免费通道必须流式（OFM aggregate.js/adapter.js 全部强制 stream:true）；
            // 自定义通道保持非流式，响应是单个 JSON。
            put("stream", cfg.useFreeModel)
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
                // 和 buildBody 保持同一套"图到底发不发"的判定，否则指纹会声称
                // "这条带了图"、实际却没发，缓存前缀对不上还查不出来
                val image = if (cfg.useFreeModel && !FreeModelCatalog.supportsImages(cfg.model)) {
                    null
                } else {
                    t.imageBytes
                }
                add(
                    t.role.hashCode() * 31 +
                        t.text.hashCode() * 7 +
                        // 用**内容标识**而不是身份码：图从磁盘读回来是新数组，
                        // 身份码会变，只有内容标识跨任务稳定（见 ChatTurn.imageId）。
                        // 图不在这条上必须是 0 —— 指纹要如实反映"实际发出去的东西"，
                        // 否则应用会报"前缀完整"，而服务端那边其实早就分叉了。
                        if (image == null) 0
                        else (t.imageId?.hashCode() ?: 0) * 13 + image.size
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
    private fun httpFailure(code: Int, body: String, retryAfterSec: Long? = null): Try {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull().orEmpty().ifBlank { body.take(200) }
        Log.w(TAG, "HTTP $code：$detail")

        // ---- 免费通道的三种专用失败 ----
        // 必须分开说，因为三种的"用户能做什么"完全不同：
        //   额度用完 → 等，或者换自己的 Key
        //   指纹被拒 → 什么都做不了，等 App 更新
        //   地区锁   → 换个模型
        // 混成一句"请求太频繁或额度用完了"，用户只会去反复点发送。
        if (cfg.useFreeModel) {
            when {
                FreeLaneWire.freeQuotaExhausted(body) -> return Try.Fatal(
                    buildString {
                        append("免费通道今天的额度用完了")
                        retryAfterSec?.let { append("，约 ${formatWait(it)} 后恢复") }
                        append("。\n")
                        append("这份额度按**出口 IP** 记账，不是按这台手机 —— ")
                        append("同一个网络下所有设备、所有免费模型共用一份。\n")
                        append("想马上继续：去「设置 → 模型」切到自定义通道，填自己的 API Key。")
                    },
                )

                FreeLaneWire.fingerprintRejected(body) -> return Try.Fatal(
                    "免费通道的客户端身份被网关拒了 —— 上游又改版了，这不是你哪里设置错了，要等 App 跟着更新。\n" +
                        "服务端说明：$detail",
                )

                FreeLaneWire.regionBlocked(body) -> return Try.Fatal(
                    "这个免费模型在当前地区不可用，换一个模型试试。\n服务端说明：$detail",
                )
            }
        }

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

    /** 把 Retry-After 的秒数说成人话（免费池一次就是十几小时，说"58536 秒"没人看得懂） */
    private fun formatWait(sec: Long): String = when {
        sec >= 3600 -> "%.1f 小时".format(sec / 3600.0)
        sec >= 60 -> "${sec / 60} 分钟"
        else -> "${sec} 秒"
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
        // SSLException 也是 IOException 的子类，必须放在通用 IO 分支之前。
        // 证书或协议配置错误时重发同一请求只会重复失败。
        is javax.net.ssl.SSLException -> Try.Fatal("HTTPS 握手失败：${t.message}")
        is java.io.InterruptedIOException -> Try.Retry("连接被中断")
        is java.net.ConnectException -> Try.Retry("连不上服务端")
        is java.net.UnknownHostException -> Try.Retry("域名解析失败（${cfg.baseUrl}）")
        is java.net.SocketException -> Try.Retry("连接断了（${t.message}）")
        is java.io.IOException -> Try.Retry("网络出错（${t.message}）")
        else -> Try.Fatal("请求出错：${t.javaClass.simpleName} ${t.message}")
    }

    /** 给连通性自检用的短描述 */
    fun describe(): String =
        "模型=${cfg.model} 地址=${endpoint()} 图片=${cfg.detail} 思考=" +
            (if (cfg.thinking == ThinkingMode.CUSTOM) {
                "自定义(" + cfg.customEffort.trim().ifBlank { "不发" } + ")"
            } else {
                cfg.thinking.label
            }) + " " +
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
