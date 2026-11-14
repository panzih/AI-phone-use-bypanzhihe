package com.aiphone.assistant.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 免密免费通道（opencode.ai 公开池）的报文契约。
 *
 * ## 依据
 *
 * **不是文档，是插件源码。** 上游 `dsh-our-free-model`（v2.8.3）的匿名车道自
 * 2026-10-31 起整体移植了参考网关 opencode2api v1.4.6/1.4.7 的整形层，本文件
 * 逐项对齐 `src/upstream.js` 与 `src/o2a/` 下的整形模块：
 *
 * | 项 | 值 |
 * |---|---|
 * | 对话端点 | `/inference/openai/v1/chat/completions`（旧扁平 `/inference/v1/…` 仍可用） |
 * | 模型目录 | `/inference/v1/models` |
 * | 鉴权 | `authorization: Bearer public` |
 * | UA | `opencode/latest/2.2.3/cli`（整串匹配） |
 * | 会话头 | `x-opencode-session` / `x-opencode-session-id` / `x-session-affinity` / `x-session-id` 四头同值 |
 * | project | 40 位 hex，由 session 派生（**不是** `global` —— 共享桶会预烧额度） |
 * | 追踪头 | `traceparent` + `b3`，每请求新生成 |
 * | 系统提示 | 官方 CLI 全量提示：base prompt + `<env>` + skills（`assets/opencode_prompt_v2.txt`） |
 * | 工具集 | 官方 13 个工具全量（`assets/opencode_tools.json`），chat 线带 `strict:false` |
 * | Chat 体 | 流式补 `stream_options.include_usage`；无上限时补 `max_completion_tokens:32000` |
 * | `tool_choice` | **绝不下发**（`none` 即 403） |
 *
 * ## 三条最容易漏的 429 来源（上游单变量实测）
 *
 * 1. **缺官方系统提示** —— 工具与请求头全对、IP 干净，缺官方 CLI 提示仍 429；
 * 2. **project 用共享的 `global`** —— 一个桶就是一份被预烧的额度；
 * 3. **会话时间戳不可信**（纯哈希落到 1970）→ 429；真近日期 → 200。
 *
 * 所以这里的会话铸造走「慢钟」：今日 UTC 零点减一个 ≤30 天的哈希偏移。
 *
 * ## 怎么区分「报文错」和「额度用完」
 *
 * - `403 FreeTierError` → 客户端身份不对，报文还得改
 * - `429 FreeUsageLimitError` → 报文已经过了闸门（也可能是传输层指纹，见下）
 * - `403 RegionError` → 报文过了，是模型在本地域不可用
 *
 * ⚠️ 上游对 TLS/HTTP 客户端指纹敏感：同出口同刻、同样的字节，参考网关的 Go
 * 客户端 200，而 curl/python 转发 429。插件因此用 Bun 发推理请求（Node 的
 * transport 是回退项）。Android 走系统 Conscrypt（BoringSSL），与桌面 Node 不同，
 * 本应用据此直连。
 */
object FreeLaneWire {

    private const val TAG = "FreeLaneWire"

    const val BASE_URL = "https://opencode.ai"

    /** 对话端点（2.1 客户端的 provider-family 路径）。 */
    const val CHAT_PATH = "/inference/openai/v1/chat/completions"

    /** 模型目录。 */
    const val MODELS_PATH = "/inference/v1/models"

    /** `CLIENT_UA` 原样。⚠️ 整串匹配，前后缀任何字符都会让它不再算 v2 客户端。 */
    const val CLIENT_UA = "opencode/latest/2.2.3/cli"

    /** 公开池的唯一凭据。 */
    const val PUBLIC_CREDENTIAL = "Bearer public"

    /** 官方 CLI 系统提示的识别串：已携带就不再重复注入。 */
    const val OFFICIAL_PROMPT_MARKER = "You are opencode, an interactive CLI tool"

    /** 2.x 客户端给 chat 体补的默认输出上限。 */
    const val V2_COMPLETION_LIMIT = 32000

    /** 官方 13 工具的名字与顺序（捕获自 2.x CLI）。 */
    val OFFICIAL_TOOL_ORDER = listOf(
        "edit", "execute", "glob", "grep", "question", "read", "shell",
        "skill", "subagent", "webfetch", "websearch", "write", "patch",
    )

    fun chatUrl(): String = BASE_URL + CHAT_PATH

    fun modelsUrl(): String = BASE_URL + MODELS_PATH

    // ------------------------------------------------------------------
    // 资产（官方提示与工具定义）
    // ------------------------------------------------------------------

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var promptText: String? = null

    @Volatile
    private var toolsText: String? = null

    /** 装上应用 Context，供读取 `assets/opencode_prompt_v2.txt` 与工具定义。 */
    fun installAssets(context: Context?) {
        appContext = context?.applicationContext
        promptText = null
        toolsText = null
    }

    private fun readAsset(name: String): String = runCatching {
        appContext?.assets?.open(name)?.bufferedReader()?.use { it.readText() } ?: ""
    }.getOrElse { e ->
        Log.w(TAG, "读取资产 $name 失败", e)
        ""
    }

    private fun officialPromptText(): String {
        promptText?.let { return it }
        return synchronized(this) {
            promptText ?: readAsset("opencode_prompt_v2.txt").also { promptText = it }
        }
    }

    private fun officialToolsText(): String {
        toolsText?.let { return it }
        return synchronized(this) {
            toolsText ?: readAsset("opencode_tools.json").also { toolsText = it }
        }
    }

    /** CLI 内置 skills 列表（`official-prompt.js` 原样）。 */
    const val OFFICIAL_SKILLS = """Skills provide specialized instructions and workflows for specific tasks.
Use the skill tool to load a skill when a task matches its description.
<available_skills>
  <skill>
    <name>customize-opencode</name>
    <description>Use ONLY when the user is editing or creating opencode's own configuration: opencode.json, opencode.jsonc, files under .opencode/, or files under ~/.config/opencode/. Also use when creating or fixing opencode agents, subagents, skills, plugins, MCP servers, or permission rules. Do not use for the user's own application code, or for any project that is not configuring opencode itself.</description>
    <location>&lt;built-in&gt;</location>
  </skill>
</available_skills>"""

    /**
     * `<env>` 块（`officialEnvironment` 原样）。
     *
     * 上游实测：缺这块不是「少点上下文」，是直接 429。
     */
    fun officialEnvironment(
        model: String,
        workingDirectory: String = "/sdcard",
        platform: String = "android",
    ): String {
        val apiModel = model.replace(Regex("^opencode/"), "").trim().ifEmpty { "unknown" }
        val format = java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = format.format(java.util.Date())
        return listOf(
            "You are powered by the model named $apiModel. The exact model ID is opencode/$apiModel",
            "Here is some useful information about the environment you are running in:",
            "<env>",
            "  Working directory: $workingDirectory",
            "  Workspace root folder: $workingDirectory",
            "  Is directory a git repo: yes",
            "  Platform: $platform",
            "  Today's date: $date",
            "</env>",
        ).joinToString("\n")
    }

    /** 官方 CLI 系统提示全量（`officialSystemPrompt`）。资产缺失时退回只带 env 的最小块。 */
    fun officialSystemPrompt(model: String, workingDirectory: String = "/sdcard"): String {
        val base = officialPromptText()
        val env = officialEnvironment(model, workingDirectory)
        if (base.isBlank()) return env
        return "$base$env\n$OFFICIAL_SKILLS"
    }

    /** 是否已带官方提示（`carriesOpenCodePrompt`）。 */
    fun carriesOfficialPrompt(system: String): Boolean = system.contains(OFFICIAL_PROMPT_MARKER)

    /**
     * 官方 13 工具集，chat 形状。
     *
     * ⚠️ 定义来自 `assets/opencode_tools.json`（捕获自官方 CLI），一个字都不要改。
     * 资产缺失时退回 read/shell 两个最小定义 —— 只保证不再 403 的兜底。
     */
    fun toolsJson(): JSONArray {
        val text = officialToolsText()
        if (text.isNotBlank()) {
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed != null) {
                val array = JSONArray()
                for (name in OFFICIAL_TOOL_ORDER) {
                    val def = parsed.optJSONObject(name) ?: continue
                    val parameters = def.optJSONObject("parameters") ?: JSONObject()
                    array.put(
                        JSONObject().apply {
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", name)
                                    put("description", def.optString("description", "Agent tool $name"))
                                    put("parameters", parameters)
                                    put("strict", false)
                                },
                            )
                        },
                    )
                }
                if (array.length() > 0) return array
            }
        }
        return fallbackTools()
    }

    /** 资产读不到时的兜底：官方 read + shell（逐字定义）。 */
    private fun fallbackTools(): JSONArray {
        val tools = JSONArray()
        tools.put(
            JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    JSONObject().apply {
                        put("name", "read")
                        put(
                            "description",
                            "Read the contents of a file or directory. Supports text files, images, and PDFs. " +
                                "Images and PDFs are presented directly to the model. Each text line is prefixed by " +
                                "its 1-based line number as <line>: <content>. The prefix is for reference and is " +
                                "not part of the file content. Directory entries are returned one per line. Use " +
                                "offset and limit to read large files or directories in sections. Prefer one larger " +
                                "read over many small slices, and use grep to find specific content in large files.",
                        )
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("path", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "File or directory to read")
                                    })
                                })
                                put("required", JSONArray().put("path"))
                                put("additionalProperties", false)
                            },
                        )
                        put("strict", false)
                    },
                )
            },
        )
        tools.put(
            JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    JSONObject().apply {
                        put("name", "shell")
                        put(
                            "description",
                            "Execute a shell command and return its output. Commands run on Linux using bash. " +
                                "Quote file paths containing spaces or special characters. Prefer dedicated tools " +
                                "over shell commands when possible. When output is large, the full result is saved " +
                                "to a file and a truncated preview is returned. Rely on automatic truncation unless " +
                                "filtering the output is more useful. Commands accept an optional timeout, background " +
                                "commands have no timeout by default. Background commands return immediately, and " +
                                "you will be notified when they complete.",
                        )
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("command", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "Shell command string to execute")
                                    })
                                })
                                put("required", JSONArray().put("command"))
                                put("additionalProperties", false)
                            },
                        )
                        put("strict", false)
                    },
                )
            },
        )
        return tools
    }

    /**
     * 占位说明（`placeholderNote(names)`）。
     *
     * 官方 13 工具是真定义，模型会真的去调；本应用不实现它们，必须明确告诉模型
     * 「这些什么都不会做，用你的 JSON 动作」。
     */
    val PLACEHOLDER_NOTE: String
        get() = "The tools " + OFFICIAL_TOOL_ORDER.joinToString(", ") { "`$it`" } +
            " are part of the host CLI's own toolset and are placeholders in this session; " +
            "they do nothing here. Use the other tools you have been given instead."

    // ------------------------------------------------------------------
    // 会话身份
    // ------------------------------------------------------------------

    private val rnd = SecureRandom()
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val SESSION_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val PHASE_MOD = 68_719_476_736L // 2^36

    /** 会话 id 形状：`ses_` + 12 位 hex + 14 位 base62。 */
    private val SESSION_RE = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")

    /** 12 位 hex 必须是降序编码：9 个自由 hex + `f[c-f]` + 1 个 hex。 */
    private val DESCENDING_TIME_RE = Regex("^[0-9a-f]{9}f[c-f][0-9a-f]$")

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { rnd.nextBytes(it) }

    /**
     * 慢钟时间部分：今日 UTC 零点 − 一个 ≤30 天的哈希偏移，`<<12 | 1` 后取反。
     *
     * 哈希只决定偏移量，不提供日期 —— 纯哈希供日期会落到 1970，上游直接 429。
     */
    internal fun descendingTimePart(now: Long, hash: ByteArray): String {
        val raw = ((hash[0].toLong() and 0xff) shl 24) or
            ((hash[1].toLong() and 0xff) shl 16) or
            ((hash[2].toLong() and 0xff) shl 8) or
            (hash[3].toLong() and 0xff)
        val offset = (raw and 0xFFFFFFFFL) % SESSION_WINDOW_MS
        val midnight = now / 86_400_000L * 86_400_000L
        val base = midnight - offset
        val inverted = ((base shl 12) or 1L).inv() and 0xFFFFFFFFFFFFL
        return inverted.toString(16).padStart(12, '0')
    }

    private fun base62Fixed(bytes: ByteArray, width: Int): String {
        var n = BigInteger.ZERO
        for (b in bytes) {
            n = n.shiftLeft(8).or(BigInteger.valueOf((b.toInt() and 0xff).toLong()))
        }
        val out = CharArray(width)
        val base = BigInteger.valueOf(62)
        for (i in width - 1 downTo 0) {
            val qr = n.divideAndRemainder(base)
            out[i] = BASE62[qr[1].toInt()]
            n = qr[0]
        }
        return String(out)
    }

    /** 这个 session 是否是官方客户端铸造、且时间相位仍在可接受窗口。 */
    internal fun isOfficialSession(id: String?): Boolean {
        if (id == null || !SESSION_RE.matches(id)) return false
        if (!DESCENDING_TIME_RE.matches(id.substring(4, 16))) return false
        val phase = sessionPhaseMs(id) ?: return false
        val nowPhase = System.currentTimeMillis() % PHASE_MOD
        var age = (nowPhase - phase) % PHASE_MOD
        if (age < 0) age += PHASE_MOD
        return age < 60L * 24 * 60 * 60 * 1000
    }

    private fun sessionPhaseMs(id: String): Long? {
        val inverted = runCatching { id.substring(4, 16).toLong(16) }.getOrNull() ?: return null
        val current = inverted.inv() and 0xFFFFFFFFFFFFL
        return current ushr 12
    }

    /** 铸造一个形状与时间都合法的 session id。 */
    fun newSessionId(now: Long = System.currentTimeMillis()): String {
        val hash = sha256(randomBytes(16))
        val timePart = descendingTimePart(now, hash)
        val randomPart = base62Fixed(hash.copyOfRange(6, 16), 14)
        return "ses_$timePart$randomPart"
    }

    /**
     * session 的落盘目录，由 [com.aiphone.assistant.PaperboxApp] 在启动时装上。
     * 单测里不装 —— 那时退回"进程内一个"，不影响断言。
     */
    @Volatile
    private var storeDir: java.io.File? = null

    /** 安装持久化目录（应用 filesDir）。只应在 Application.onCreate 调一次。 */
    fun installStore(dir: java.io.File?) {
        storeDir = dir
    }

    @Volatile
    private var persisted: String? = null

    /**
     * 这个安装的 session id：**铸造一次，之后一直用它**。
     *
     * 上游按 session 记账；每请求换新 id 会被当成刷额度。落盘的 id 若时间相位
     * 已离开可接受窗口（老版本铸的、或跨了 ≥60 天），则重铸而不是继续复用。
     */
    fun session(): String {
        persisted?.let { if (isOfficialSession(it)) return it }
        synchronized(this) {
            persisted?.let { if (isOfficialSession(it)) return it }
            val file = storeDir?.let { java.io.File(it, "free-lane-session") }
            val fromDisk = runCatching {
                file?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { isOfficialSession(it) }
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

    // ------------------------------------------------------------------
    // 请求头（o2a/headers.js 逐项）
    // ------------------------------------------------------------------

    /** 40 位 hex 的 project id，由 session 派生（`stableProjectID` / `projectID`）。 */
    fun projectIdFor(session: String): String {
        val digest = sha256("opencode-project\u0000$session".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(40)
        for (i in 0 until 20) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    /**
     * 一次请求的头，**纯数据**。
     *
     * 拆成 map 是为了让单测断言真正要发出去的东西（`HttpURLConnection` 会把
     * `Authorization` 从 `requestProperties` 里藏起来）。
     *
     * ⚠️ `x-opencode-request` 是 v1 专用头，**不能发**；`x-opencode-project`
     * 必须是 40 位 hex（不是 `global`）。
     */
    fun headersFor(
        session: String,
        stream: Boolean,
        accept: String? = "*/*",
    ): LinkedHashMap<String, String> {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Authorization" to PUBLIC_CREDENTIAL,
            "User-Agent" to CLIENT_UA,
            "x-opencode-client" to "cli",
            "x-session-affinity" to session,
            "x-session-id" to session,
            "x-opencode-session" to session,
            "x-opencode-session-id" to session,
            "x-opencode-project" to projectIdFor(session),
        )
        headers.putAll(traceHeaders())
        if (accept != null) headers["Accept"] = accept
        return headers
    }

    /** 每请求新的 W3C Trace Context + b3 单头。 */
    internal fun traceHeaders(): Map<String, String> {
        val traceId = randomBytes(16).joinToString("") { "%02x".format(it) }
        val spanId = randomBytes(8).joinToString("") { "%02x".format(it) }
        val parentId = randomBytes(8).joinToString("") { "%02x".format(it) }
        return linkedMapOf(
            "traceparent" to "00-$traceId-$spanId-01",
            "b3" to "$traceId-$spanId-1-$parentId",
        )
    }

    /** 把 [headersFor] 挂到连接上。 */
    fun applyHeaders(
        conn: java.net.HttpURLConnection,
        session: String,
        stream: Boolean,
        accept: String? = "*/*",
    ) {
        headersFor(session, stream, accept).forEach { (name, value) ->
            conn.setRequestProperty(name, value)
        }
    }

    // ------------------------------------------------------------------
    // 错误分类
    // ------------------------------------------------------------------

    /** 网关在 body 里回的 `error.type`；拿不到就 null */
    fun errorType(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("type")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 这个池子/会话的免费额度用完了 */
    fun freeQuotaExhausted(body: String): Boolean = errorType(body) == "FreeUsageLimitError"

    /** 客户端身份被拒 —— 上游又改版了，只能跟版本 */
    fun fingerprintRejected(body: String): Boolean = errorType(body) == "FreeTierError"

    /** 这个模型在当前地区不可用 */
    fun regionBlocked(body: String): Boolean = errorType(body) == "RegionError"
}
