package com.aiphone.assistant.llm

import java.net.HttpURLConnection
import java.security.SecureRandom

/**
 * 免密免费通道（opencode.ai 公开池）的报文契约。
 *
 * ## 这份契约的唯一依据
 *
 * **不是文档，是插件源码。** 上游 `dsh-our-free-model` 的 README / `docs/`
 * 里的对照表和它自己的 `src/upstream.js` 有几处不一致（文档停在 10-16，
 * 代码往前走到了 10-29），照文档写两次都错。这里逐项抄 `src/upstream.js`：
 *
 * | 项 | 抄自哪里 | 值 |
 * |---|---|---|
 * | 端点 | `endpointFor()` | `/inference/v1/chat/completions` |
 * | 鉴权 | `gatewayHeaders()` | **`authorization: Bearer public`**（不是"不发"） |
 * | UA | `CLIENT_UA` | `opencode/latest/2.0.26/cli` |
 * | project | `gatewayHeaders()` | 固定字符串 `global` |
 * | 会话头 | `gatewayHeaders()` | 四个同名同值 |
 * | 工具闸门 | `FINGERPRINT_TOOL_DEFINITIONS` | `read` + `shell`，描述与 schema **逐字** |
 * | `tool_choice` | `applyFingerprint()` | **绝不下发**（`none` 即 403） |
 * | env 块 | `ensureFreeLaneEnvironment()` | system 末尾追加 `<env>`，**缺了就是 429** |
 * | 追踪头 | `gatewayHeaders()` 里没有 | 不发 `b3` / `traceparent` |
 *
 * ## `<env>` 块是硬要求，不是装饰
 *
 * `ensureFreeLaneEnvironment()` 的注释原话：
 * *"Reversible live controls (2026-10-28) returned 429 without it and PONG with it."*
 * —— 同样的报文，去掉 `<env>` 就是 `429 FreeUsageLimitError`，加上就 200。
 * 这是本项目最容易漏、也最难从错误信息里看出来的一条。
 *
 * ## 怎么区分"报文错"和"额度用完"（实测有效）
 *
 * - `403 FreeTierError` → 客户端身份不对，报文还得改
 * - `429 FreeUsageLimitError` → **报文已经过了闸门**，是这个池子的额度用完了
 * - `403 RegionError` → 报文过了，是模型在本地域不可用（如 `beam-free`）
 *
 * 这条判据很重要：429 不等于代码错。同机官方 CLI 在额度耗尽时也是 429。
 */
object FreeLaneWire {

    const val BASE_URL = "https://opencode.ai"

    /** 对话端点（`endpointFor()`：muse-spark 走 /responses、union-alpha 走 /messages，其余都是这条）。 */
    const val CHAT_PATH = "/inference/v1/chat/completions"

    /** 模型目录。官方客户端不用它（它读 models.dev），但我们要用它列免费模型。 */
    const val MODELS_PATH = "/inference/v1/models"

    /** `CLIENT_UA` 原样。⚠️ 整串匹配，前后缀任何字符都会让它不再算 v2 客户端。 */
    const val CLIENT_UA = "opencode/latest/2.0.26/cli"

    /** 公开池的唯一凭据（`gatewayHeaders()` 写死的）。 */
    const val PUBLIC_CREDENTIAL = "Bearer public"

    /** 网关用来分桶的 project（`gatewayHeaders()` 写死的字符串）。 */
    const val PROJECT = "global"

    fun chatUrl(): String = BASE_URL + CHAT_PATH

    fun modelsUrl(): String = BASE_URL + MODELS_PATH

    // ------------------------------------------------------------------
    // 会话身份
    // ------------------------------------------------------------------

    private val rnd = SecureRandom()
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    @Volatile private var lastStamp = 0L
    @Volatile private var seq = 0

    /**
     * session id：`ses_` + 12 位降序时间戳 hex + 14 位 base62。
     *
     * 12 位必须是 `~(ts_ms * 0x1000 + seq)` 的低 48 位（`mintSessionId()` 原样）。
     * 低 12 位落 0xfc0–0xfff —— 解码出来是 `seq`，上游要求它很小（实测 0–16 通过、
     * ≥100 拒绝），所以每毫秒归零重计。
     *
     * 摘要派生、随机 hex、升序时间戳都会被拒。
     */
    fun newSessionId(): String {
        val now = System.currentTimeMillis()
        val counter: Int = synchronized(this) {
            if (now != lastStamp) {
                lastStamp = now
                seq = 0
            }
            // 逐行审查 #9：计数必须回绕收敛在 1–63。同一毫秒铸超过 63 个时
            // （理论场景：批量探针/测试），无界递增会让低 12 位落出
            // 0xfc0–0xfff 契约区，上游直接拒绝。
            seq = (seq % 63) + 1
            seq
        }
        val value = ((now shl 12) or counter.toLong()).inv() and 0xFFFFFFFFFFFFL
        return "ses_" + value.toString(16).padStart(12, '0') +
            (0 until 14).map { BASE62[rnd.nextInt(62)] }.joinToString("")
    }

    /**
     * session 的落盘目录，由 [com.aiphone.assistant.PaperboxApp] 在启动时装上。
     * 单测里不装 —— 那时退回"进程内一个"，不影响断言。
     */
    @Volatile private var storeDir: java.io.File? = null

    /** 安装持久化目录（应用 filesDir）。只应在 Application.onCreate 调一次。 */
    fun installStore(dir: java.io.File?) {
        storeDir = dir
    }

    @Volatile private var persisted: String? = null

    /**
     * 这个安装的 session id：**铸造一次，之后一直用它**。
     *
     * 上游插件的原话是 "mints once with `mintSessionId()` and persists it"，
     * 并且 "No error or retry rotates the session"。每请求换新 id 会被当成
     * 刷额度：实测同一个 id 连发两次能过，而每次都换 id 的那组全是 429。
     *
     * 也不能每次冷启动都换 —— 落盘到 `filesDir/free-lane-session`。
     */
    fun session(): String {
        persisted?.let { return it }
        synchronized(this) {
            persisted?.let { return it }
            val file = storeDir?.let { java.io.File(it, "free-lane-session") }
            val fromDisk = runCatching {
                file?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { SESSION_RE.matches(it) }
            }.getOrNull()
            if (fromDisk != null) {
                persisted = fromDisk
                return fromDisk
            }
            val minted = newSessionId()
            persisted = minted
            runCatching {
                file?.parentFile?.mkdirs()
                file?.writeText(minted)
            }
            return minted
        }
    }

    /** 与上游 `SESSION_RE` 同一条：`ses_` + 12 位小写 hex + 14 位 base62。 */
    private val SESSION_RE = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")

    // ------------------------------------------------------------------
    // 请求头（gatewayHeaders() 逐项）
    // ------------------------------------------------------------------

    /**
     * 一次请求的头，**纯数据**（`gatewayHeaders()` 逐项）。
     *
     * 为什么不直接写在 [applyHeaders] 里：`HttpURLConnection` 会把
     * `Authorization` 从 `requestProperties` 里藏起来，断言看不见它。
     * 拆出一个 map 之后，测试断言的就是**真正要发出去的东西**。
     *
     * ⚠️ 三条容易搞错的：
     * - **`Authorization: Bearer public` 必须发**。不发不是"更干净"，是过不了鉴权；
     * - **不发 `x-opencode-request`** —— v1 专用头，v2 收到就 403；
     * - **不发 `b3` / `traceparent`** —— 官方客户端会带，但这个插件不带，不需要。
     */
    fun headersFor(
        session: String,
        stream: Boolean,
        accept: String? = null,
    ): LinkedHashMap<String, String> = linkedMapOf(
        "Content-Type" to "application/json",
        "Authorization" to PUBLIC_CREDENTIAL,
        "User-Agent" to CLIENT_UA,
        "x-opencode-client" to "cli",
        "x-opencode-session" to session,
        "x-opencode-session-id" to session,
        "x-session-affinity" to session,
        "x-session-id" to session,
        "x-opencode-project" to PROJECT,
        "Accept" to (accept ?: if (stream) "text/event-stream" else "*/*"),
    )

    /** 把 [headersFor] 挂到连接上。 */
    fun applyHeaders(
        conn: HttpURLConnection,
        session: String,
        stream: Boolean,
        accept: String? = null,
    ) {
        headersFor(session, stream, accept).forEach { (name, value) ->
            conn.setRequestProperty(name, value)
        }
    }

    // ------------------------------------------------------------------
    // 工具闸门（FINGERPRINT_TOOL_DEFINITIONS 逐字）
    // ------------------------------------------------------------------

    /** 闸门要求的两个工具名（`FINGERPRINT_TOOLS`）。 */
    val FINGERPRINT_TOOLS = listOf("read", "shell")

    private const val READ_DESCRIPTION =
        "Read the contents of a file or directory. Supports text files, images, and PDFs. " +
            "Images and PDFs are presented directly to the model. Each text line is prefixed by " +
            "its 1-based line number as <line>: <content>. The prefix is for reference and is " +
            "not part of the file content. Directory entries are returned one per line. Use " +
            "offset and limit to read large files or directories in sections. Prefer one larger " +
            "read over many small slices, and use grep to find specific content in large files."

    private const val SHELL_DESCRIPTION =
        "Execute a shell command and return its output. Commands run on Linux using bash. " +
            "Quote file paths containing spaces or special characters. Prefer dedicated tools " +
            "over shell commands when possible. When output is large, the full result is saved " +
            "to a file and a truncated preview is returned. Rely on automatic truncation unless " +
            "filtering the output is more useful. Commands accept an optional timeout, background " +
            "commands have no timeout by default. Background commands return immediately, and " +
            "you will be notified when they complete."

    /**
     * 注入给网关看的两个工具定义。
     *
     * ⚠️ **一个字都不要改**。上游比对的是 `description` 全文与 `parameters` 的 JSON：
     * 改措辞、精简 schema、`properties:{}`、改名，都是 403 FreeTierError。
     * 这两段是从 `src/upstream.js` 里原样复制的。
     */
    fun toolsJson(): org.json.JSONArray {
        val tools = org.json.JSONArray()

        tools.put(
            org.json.JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    org.json.JSONObject().apply {
                        put("name", "read")
                        put("description", READ_DESCRIPTION)
                        put(
                            "parameters",
                            org.json.JSONObject().apply {
                                put("type", "object")
                                put(
                                    "properties",
                                    org.json.JSONObject().apply {
                                        put(
                                            "path",
                                            org.json.JSONObject().apply {
                                                put("type", "string")
                                                put("description", "File or directory to read")
                                            },
                                        )
                                        put(
                                            "offset",
                                            org.json.JSONObject().apply {
                                                put("type", "integer")
                                                put("minimum", 0)
                                                put(
                                                    "description",
                                                    "The line or directory entry to start reading from (1-based)",
                                                )
                                            },
                                        )
                                        put(
                                            "limit",
                                            org.json.JSONObject().apply {
                                                put("type", "integer")
                                                put("minimum", 0)
                                                put(
                                                    "description",
                                                    "The maximum number of lines or directory entries to read " +
                                                        "(defaults to and capped at 2000)",
                                                )
                                            },
                                        )
                                    },
                                )
                                put("required", org.json.JSONArray().put("path"))
                                put("additionalProperties", false)
                            },
                        )
                    },
                )
            },
        )

        tools.put(
            org.json.JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    org.json.JSONObject().apply {
                        put("name", "shell")
                        put("description", SHELL_DESCRIPTION)
                        put(
                            "parameters",
                            org.json.JSONObject().apply {
                                put("type", "object")
                                put(
                                    "properties",
                                    org.json.JSONObject().apply {
                                        put(
                                            "command",
                                            org.json.JSONObject().apply {
                                                put("type", "string")
                                                put("description", "Shell command string to execute")
                                            },
                                        )
                                        put(
                                            "workdir",
                                            org.json.JSONObject().apply {
                                                put("type", "string")
                                                put(
                                                    "description",
                                                    "Working directory to execute the command in. Defaults to " +
                                                        "the current working directory. When possible, avoid " +
                                                        "changing directories in the command and set the working " +
                                                        "directory here instead.",
                                                )
                                            },
                                        )
                                        put(
                                            "timeout",
                                            org.json.JSONObject().apply {
                                                put("type", "integer")
                                                put("minimum", 0)
                                                put(
                                                    "description",
                                                    "Timeout in milliseconds. Set to 0 to disable the timeout. " +
                                                        "Defaults to 120000 for foreground commands. Background " +
                                                        "commands have no timeout by default.",
                                                )
                                            },
                                        )
                                        put(
                                            "background",
                                            org.json.JSONObject().apply {
                                                put("type", "boolean")
                                                put(
                                                    "description",
                                                    "Run the command in the background and return immediately " +
                                                        "(useful for dev servers and long-running builds). You do " +
                                                        "not need to use '&' at the end of the command when using " +
                                                        "this parameter. You will be notified when it completes. " +
                                                        "DO NOT poll for completion.",
                                                )
                                            },
                                        )
                                    },
                                )
                                put("required", org.json.JSONArray().put("command"))
                                put("additionalProperties", false)
                            },
                        )
                    },
                )
            },
        )

        return tools
    }

    /** `placeholderNote(['read','shell'])` 原样：这两个是真工具，不打招呼模型真会去调。 */
    const val PLACEHOLDER_NOTE =
        "Do not call the `read` or `shell` tools; they are placeholders that do nothing. " +
            "Use the other tools you have been given instead."

    /**
     * `<env>` 块（`ensureFreeLaneEnvironment()` 原样）。
     *
     * 上游实测：同一份报文，**没这块 429、有这块 200**。值必须是真实的
     * （工作目录 + 平台），不抄 OpenCode 的人设。
     */
    fun environmentBlock(
        workingDirectory: String,
        platform: String = "android",
    ): String {
        val line = { v: String -> v.replace(Regex("[\\r\\n<>]"), " ") }
        return "Here is some useful information about the environment you are running in:\n" +
            "<env>\n" +
            "  Working directory: ${line(workingDirectory)}\n" +
            "  Platform: ${line(platform)}\n" +
            "</env>"
    }

    // ------------------------------------------------------------------
    // 错误分类
    // ------------------------------------------------------------------

    /** 网关在 body 里回的 `error.type`；拿不到就 null */
    fun errorType(body: String): String? = runCatching {
        org.json.JSONObject(body).optJSONObject("error")?.optString("type")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 这个池子/会话的免费额度用完了（报文是对的，等重置或换网络） */
    fun freeQuotaExhausted(body: String): Boolean = errorType(body) == "FreeUsageLimitError"

    /** 客户端身份被拒 —— 上游又改版了，只能跟版本 */
    fun fingerprintRejected(body: String): Boolean = errorType(body) == "FreeTierError"

    /** 这个模型在当前地区不可用（如 beam-free 对国内出口） */
    fun regionBlocked(body: String): Boolean = errorType(body) == "RegionError"
}
