package com.aiphone.assistant.llm

import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 免密免费通道（opencode.ai 公开池）的报文契约。
 *
 * ## 这份契约是怎么来的（别再靠文档猜）
 *
 * 前面两版都是照着上游插件 `dsh-our-free-model` 的**文档**写的，两版都错。
 * 现在这一版是**抓的官方客户端实包**：把 `OpenCode.app` 自带的 CLI 的
 * provider baseURL 指到一个本地代理上，让它自己发一次请求，逐字节抄下来。
 *
 * 对照表（第三列是实测）：
 *
 * | 项 | 上游文档写的 v2 | **实测的官方 2.2.0 客户端** |
 * |---|---|---|
 * | 对话端点 | `/inference/v1/chat/completions` | **`/inference/openai/v1/chat/completions`** |
 * | User-Agent | `opencode/latest/2.0.26/cli` | `opencode/latest/2.2.0/cli`（跟着客户端版本走） |
 * | Authorization | `Bearer public` | **整个头都不发** |
 * | project | 固定字符串 `global` | 40 位 hex（sha1 形状，本机稳定不变） |
 * | 追踪头 | 没有 | **`b3` + `traceparent`** 都带 |
 * | accept | `text/event-stream` | 通配符（不是 SSE） |
 * | 工具闸门 | 必须注入逐字的 `read`/`shell` | **不需要**（客户端发的是自己那套工具） |
 * | body | `stream:true` | `stream:true` + **`stream_options.include_usage`** |
 *
 * ⚠️ **模型目录还在 `/inference/v1/models`** —— `/inference/openai/v1/models`
 * 是 404：官方客户端的模型表来自 models.dev，不走网关目录。
 *
 * ## 还有一条不是报文的问题：出口
 *
 * 免费池"按出口 IP 计量"，上游插件为此专门写了 `src/egress.js`（Clash/mihomo
 * 换节点）。同一条报文，从干净的出口发就是 200，从被限流的出口发就是
 * `429 FreeUsageLimitError`，报文本身没区别。所以**本机/代理环境测出来的 429
 * 不代表代码有问题**，换网络再测。
 */
object FreeLaneWire {

    const val BASE_URL = "https://opencode.ai"

    /** 对话端点。⚠️ 是 `/inference/openai/v1`，少一层 `openai` 就不是同一条路。 */
    const val CHAT_PATH = "/inference/openai/v1/chat/completions"

    /** 模型目录。只有这个路径有列表（openai/v1 那个 404）。 */
    const val MODELS_PATH = "/inference/v1/models"

    /** 客户端指纹：整串匹配，且要跟官方最新客户端一致。 */
    const val CLIENT_UA = "opencode/latest/2.2.0/cli"

    fun chatUrl(): String = BASE_URL + CHAT_PATH

    fun modelsUrl(): String = BASE_URL + MODELS_PATH

    // ------------------------------------------------------------------
    // 身份
    // ------------------------------------------------------------------

    private val rnd = SecureRandom()
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    @Volatile private var lastStamp = 0L
    @Volatile private var seq = 0

    /**
     * session id：`ses_` + 12 位降序时间戳 hex + 14 位 base62。
     *
     * 那 12 位必须是 `~(ts_ms << 12 | counter)` 的低 48 位，counter 落在 1–63
     * （低 12 位 0xfc0–0xfff）。升序写法、随机 hex、摘要都会被拒。
     */
    fun newSessionId(): String {
        val now = System.currentTimeMillis()
        val counter: Int = synchronized(this) {
            if (now != lastStamp) {
                lastStamp = now
                seq = 0
            }
            seq = (seq % 63) + 1
            seq
        }
        val value = ((now shl 12) or counter.toLong()).inv() and 0xFFFFFFFFFFFFL
        val timeHex = value.toString(16).padStart(12, '0')
        return "ses_$timeHex" + (0 until 14).map { BASE62[rnd.nextInt(62)] }.joinToString("")
    }

    /**
     * project id：40 位 hex。官方客户端发的是 sha1 形状的稳定值。
     *
     * 这边用一个固定种子算 sha1 —— 只要不变，每次启动都是同一个 project，
     * 别用随机值（网关按 project 记账，随机等于每轮都换个新桶）。
     */
    val projectId: String by lazy {
        val digest = MessageDigest.getInstance("SHA-1").digest("paperbox:default-project".toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // 请求头
    // ------------------------------------------------------------------

    /**
     * 一次请求的追踪头。官方客户端每次都新生成一对，网关侧用来串请求。
     * 我们的用途只是"看起来一样"，值本身没有语义。
     */
    private fun traceHeaders(): Pair<String, String> {
        // traceId = 16 字节（32 hex），spanId = 8 字节（16 hex），和官方客户端一致
        val trace = randomHex(32)
        val span = randomHex(16)
        return "$trace-$span-1-$span" to "00-$trace-$span-01"
    }

    private fun randomHex(len: Int): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append("0123456789abcdef"[rnd.nextInt(16)]) }
        return sb.toString()
    }

    /**
     * 免费通道的完整请求头。
     *
     * ⚠️ **不发 `Authorization`**：免费池靠客户端身份识别，加了 `Bearer public`
     * 反而会被当成"非官方客户端"的调用方（实测直接 429）。
     * ⚠️ 也不发 `x-opencode-request` —— 那是 v1 专用头。
     */
    fun applyHeaders(
        conn: HttpURLConnection,
        session: String,
        stream: Boolean,
        accept: String? = null,
    ) {
        val (b3, traceparent) = traceHeaders()
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", accept ?: "*/*")
        conn.setRequestProperty("User-Agent", CLIENT_UA)
        conn.setRequestProperty("x-opencode-client", "cli")
        conn.setRequestProperty("x-opencode-project", projectId)
        conn.setRequestProperty("x-opencode-session", session)
        conn.setRequestProperty("x-opencode-session-id", session)
        conn.setRequestProperty("x-session-affinity", session)
        conn.setRequestProperty("x-session-id", session)
        conn.setRequestProperty("b3", b3)
        conn.setRequestProperty("traceparent", traceparent)
    }

    // ------------------------------------------------------------------
    // 错误分类
    // ------------------------------------------------------------------

    /** 网关在 body 里回的 `error.type`；拿不到就 null */
    fun errorType(body: String): String? = runCatching {
        org.json.JSONObject(body).optJSONObject("error")?.optString("type")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 这个出口/会话的免费额度用完了（换网络通常就好） */
    fun freeQuotaExhausted(body: String): Boolean = errorType(body) == "FreeUsageLimitError"

    /** 客户端身份被拒 —— 客户端又改版了，只能跟版本 */
    fun fingerprintRejected(body: String): Boolean = errorType(body) == "FreeTierError"

    /** 这个模型在当前地区不可用 */
    fun regionBlocked(body: String): Boolean = errorType(body) == "RegionError"
}
