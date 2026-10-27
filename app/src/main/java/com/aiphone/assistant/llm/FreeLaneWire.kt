package com.aiphone.assistant.llm

import java.net.HttpURLConnection
import java.security.SecureRandom

/**
 * 免密免费通道（opencode.ai 公开池）的报文契约。
 *
 * ## 为什么单独成文件
 *
 * 这套东西是**别人的协议**，上游随时会改，改一次线上就全挂。把它从
 * [LlmClient] 里拆出来，是为了让"改协议"只动一处，而且改的时候能一眼
 * 看到完整的对照表。
 *
 * ## 2026-10 上游改成 v2，旧报文全线作废
 *
 * | 项 | v1（旧，已失效） | v2（现在） |
 * |---|---|---|
 * | 端点 | `/zen/v1/chat/completions` | **`/inference/v1/chat/completions`** |
 * | User-Agent | `opencode/1.18.31 (linux arm64; …)` | **`opencode/latest/2.0.26/cli`**（整串匹配，major 必须 ≥ 2） |
 * | 请求头 | 带 `x-opencode-request` | **不带**（v2 收到即 403）；`x-opencode-session` 与 `x-opencode-session-id` 必须**同时**有，另加 `x-session-affinity` / `x-session-id` 同值镜像 |
 * | project | `prj_<sha256>` | **`global`**（固定字符串） |
 * | 工具闸门 | `bash` + `read` 空桩 | **`read` + `shell`，描述与参数 schema 必须与官方 CLI 逐字一致** |
 * | `tool_choice` | 无工具时补 `none` | **`none` 即 403，绝不下发** |
 * | session id | 反相时间戳即可 | 同左，但编码校验更严：12 位 hex 必须是降序 `~(ts<<12\|c)`，counter 1–63（低 12 位落在 `0xfc0–0xfff`） |
 *
 * 每一条都对应一次 403 FreeTierError —— 少一项、多一项都过不去。
 * 依据：上游 `dsh-our-free-model` v2.7.2 的 `src/upstream.js`
 * （其中记录了对活网关的实测），以及它引用的 9router / opencode2api。
 *
 * ## 还有一条不是 403 的失败：429 额度
 *
 * 免费池按**出口 IP** 记每日额度，用完回 `429 FreeUsageLimitError`，
 * 响应头 `Retry-After` 是到重置的秒数（实测约 16 小时，即 UTC 0 点）。
 * 这跟本文件无关，但必须在 UI 上说清楚，否则用户只会以为软件坏了。
 */
object FreeLaneWire {

    const val BASE_URL = "https://opencode.ai"

    /** 免费通道的对话端点。⚠️ 不是 /zen/v1 —— 那条路对免费 model 一律 403。 */
    const val CHAT_PATH = "/inference/v1/chat/completions"

    /** 模型目录。两个路径目前都能回 200，这里跟对话端点保持一致，少一个变量。 */
    const val MODELS_PATH = "/inference/v1/models"

    /**
     * 客户端指纹。**必须原样单独发出**：v2 是整串匹配，加任何前后缀
     * （包括自家产品名）都会让它不再是"一个 v2 客户端"。
     */
    const val CLIENT_UA = "opencode/latest/2.0.26/cli"

    /**
     * 注入了 `read`/`shell` 之后必须告诉模型"别调它们"。
     *
     * 这两个定义是**真工具**（描述与 schema 都来自官方 CLI），模型会真的去调 ——
     * 实测会拿 `read` 去读 /etc。措辞是上游挑过的：写成"不可用"会让模型连
     * 调用方自己的工具也一起拒绝，"占位、请用别的工具"才既压住注入项又不误伤。
     */
    const val PLACEHOLDER_NOTE: String =
        "Do not call the `read` or `shell` tools; they are placeholders that do nothing. " +
            "Use the other tools you have been given instead."

    fun chatUrl(): String = BASE_URL + CHAT_PATH

    fun modelsUrl(): String = BASE_URL + MODELS_PATH

    // ------------------------------------------------------------------
    // 工具闸门
    // ------------------------------------------------------------------

    /**
     * v2 门禁要求的两个工具定义，**逐字**取自 OpenCode v2.0.26 CLI。
     *
     * ⚠️ 不要"顺手美化"：上游比对的是 description 字符串和 parameters 结构，
     * 改一个词、把 `properties` 写成 `{}`、换个工具名，都会换回 403
     * （v1 时代"桩工具 + 占位 property"的写法已经失效）。
     */
    private val TOOLS_JSON: String = """
    [
      {
        "type": "function",
        "function": {
          "name": "read",
          "description": "Read the contents of a file or directory. Supports text files, images, and PDFs. Images and PDFs are presented directly to the model. Each text line is prefixed by its 1-based line number as <line>: <content>. The prefix is for reference and is not part of the file content. Directory entries are returned one per line. Use offset and limit to read large files or directories in sections. Prefer one larger read over many small slices, and use grep to find specific content in large files.",
          "parameters": {
            "type": "object",
            "properties": {
              "path": { "type": "string", "description": "File or directory to read" },
              "offset": { "type": "integer", "minimum": 0, "description": "The line or directory entry to start reading from (1-based)" },
              "limit": { "type": "integer", "minimum": 0, "description": "The maximum number of lines or directory entries to read (defaults to and capped at 2000)" }
            },
            "required": ["path"],
            "additionalProperties": false
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "shell",
          "description": "Execute a shell command and return its output. Commands run on Linux using bash. Quote file paths containing spaces or special characters. Prefer dedicated tools over shell commands when possible. When output is large, the full result is saved to a file and a truncated preview is returned. Rely on automatic truncation unless filtering the output is more useful. Commands accept an optional timeout, background commands have no timeout by default. Background commands return immediately, and you will be notified when they complete.",
          "parameters": {
            "type": "object",
            "properties": {
              "command": { "type": "string", "description": "Shell command string to execute" },
              "workdir": { "type": "string", "description": "Working directory to execute the command in. Defaults to the current working directory. When possible, avoid changing directories in the command and set the working directory here instead." },
              "timeout": { "type": "integer", "minimum": 0, "description": "Timeout in milliseconds. Set to 0 to disable the timeout. Defaults to 120000 for foreground commands. Background commands have no timeout by default." },
              "background": { "type": "boolean", "description": "Run the command in the background and return immediately (useful for dev servers and long-running builds). You do not need to use '&' at the end of the command when using this parameter. You will be notified when it completes. DO NOT poll for completion." }
            },
            "required": ["command"],
            "additionalProperties": false
          }
        }
      }
    ]
    """.trimIndent()

    /** 每次都要一个**新数组**：JSONArray 会被塞进请求体，不能共享实例 */
    fun tools(): org.json.JSONArray = org.json.JSONArray(TOOLS_JSON)

    // ------------------------------------------------------------------
    // 身份
    // ------------------------------------------------------------------

    private val rnd = SecureRandom()
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    @Volatile private var lastStamp = 0L
    @Volatile private var seq = 0

    /**
     * 铸一个网关认的 session id。
     *
     * 形状：`ses_` + 12 位 hex + 14 位 base62。那 12 位 hex 是
     * `~(ts_ms << 12 | counter)` 的低 48 位 —— 官方客户端为了让 id
     * 按字符串排序时"最新的在前"，把时间戳按位取反了。
     *
     * ⚠️ 网关校验的是**编码**，不只是形状：counter 必须落在 1–63，
     * 也就是低 12 位落在 `0xfc0–0xfff`。升序写法（`ts << 12`）、
     * sha256 摘要、随机 hex 都会被 403 拒掉。
     *
     * 同一毫秒内连铸多个时 counter 递增；counter 超过 63 会掉出窗口，
     * 所以这里对 63 取模（正常调用根本到不了）。
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
     * v2 的完整请求头。
     *
     * 四舍五入就是"冒充官方 CLI"：少任何一个 session 头、多一个
     * `x-opencode-request`，都是 403。
     */
    fun applyHeaders(
        conn: HttpURLConnection,
        session: String,
        stream: Boolean,
        accept: String? = null,
    ) {
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", accept ?: if (stream) "text/event-stream" else "*/*")
        conn.setRequestProperty("Authorization", "Bearer public")
        conn.setRequestProperty("User-Agent", CLIENT_UA)
        conn.setRequestProperty("x-opencode-client", "cli")
        conn.setRequestProperty("x-opencode-session", session)
        conn.setRequestProperty("x-opencode-session-id", session)
        conn.setRequestProperty("x-session-affinity", session)
        conn.setRequestProperty("x-session-id", session)
        conn.setRequestProperty("x-opencode-project", "global")
    }

    // ------------------------------------------------------------------
    // 错误分类
    // ------------------------------------------------------------------

    /** 网关在 body 里回的 `error.type`；拿不到就 null */
    fun errorType(body: String): String? = runCatching {
        org.json.JSONObject(body).optJSONObject("error")?.optString("type")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 今日免费额度用完（按出口 IP 记）。[retryAfterSec] 是到重置的秒数 */
    fun freeQuotaExhausted(body: String): Boolean = errorType(body) == "FreeUsageLimitError"

    /** 客户端指纹被拒 —— 上游又改版了，只能改代码，改设置没用 */
    fun fingerprintRejected(body: String): Boolean = errorType(body) == "FreeTierError"

    /** 这个模型在当前地区不可用 */
    fun regionBlocked(body: String): Boolean = errorType(body) == "RegionError"
}
