package com.aiphone.assistant.browser

import com.aiphone.assistant.llm.EndpointSafety

/**
 * 内置浏览器的地址白名单。
 *
 * ## 为什么只放行 http/https
 *
 * `browser_open` 的 URL 来自模型，而屏幕/网页内容是不可信输入 —— 模型可能
 * 被页面里的文字诱导，给出一个看起来像网址、实际是别的东西的字符串：
 *
 *   file:///sdcard/...        读本地文件
 *   content://...             读其他应用的 ContentProvider
 *   intent://...              拉起任意组件 / 发广播
 *   javascript:...            在当前页执行脚本
 *   data:text/html,...        内联一个钓鱼页
 *
 * 这些都不该由模型随手写的一串字符决定。这里统一做**白名单**：只认
 * `http` / `https`，其余一律拒绝，并把原因回灌给模型。
 *
 * `browser_open`（解析层）、[BrowserDriver.open]（驱动层）和
 * [BrowserActivity]（WebView 导航层）共用这一份判据，避免只堵住一个入口。
 */
object BrowserUrl {

    sealed class Result {
        data class Ok(val url: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /** 协议头：`scheme:` 后面可能是 `//host`，也可能是 `host:port` 这种误判 */
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)

    private const val MAX_LENGTH = 4096

    /**
     * 把模型给的字符串归一成可加载的 http/https URL。
     *
     * 空串表示"打开空白页"（[BrowserDriver.open] 会加载 about:blank）。
     * 模型常省略协议头（`bilibili.com`）或写成 `example.com:8080`，
     * 这些补成 https；本机/局域网主机补成 http，因为本地模型/路由管理页
     * 基本不带 TLS。
     */
    fun normalize(raw: String): Result {
        val input = raw.trim()
        if (input.isEmpty()) return Result.Ok("")
        if (input.length > MAX_LENGTH) {
            return Result.Rejected("网址太长（超过 $MAX_LENGTH 字符）")
        }
        // 换行/制表符会被某些解析器当成截断点，也能用来伪造请求行
        if (input.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
            return Result.Rejected("网址里不能有空白或控制字符")
        }
        // 浏览器把反斜杠当斜杠，`https://a.com\@b.com` 这类写法容易造成误判
        if (input.contains('\\')) return Result.Rejected("网址里不能有反斜杠")

        val m = SCHEME.find(input)
        val candidate = if (m == null) {
            if (input.startsWith("//")) {
                // 协议相对写法：//example.com
                "https:$input"
            } else {
                val host = input.substringBefore('/').substringBefore(':')
                val scheme = if (isLocal(host)) "http" else "https"
                "$scheme://$input"
            }
        } else {
            val scheme = m.groupValues[1].lowercase()
            val rest = m.groupValues[2]
            when {
                scheme == "http" || scheme == "https" -> {
                    if (rest.startsWith("//")) "$scheme:$rest"
                    else "$scheme://" + rest.trimStart('/')
                }

                // 明确的 scheme URL：除了 http/https 一律拒绝
                rest.startsWith("//") ->
                    return Result.Rejected("只允许 http/https，$scheme:// 已被拒绝")

                // `example.com:8080` / `localhost:3000` 被正则误认成 scheme。
                // 端口是数字、或者"scheme"里带点，就按 host:port 处理。
                rest.firstOrNull()?.isDigit() == true || scheme.contains('.') -> {
                    val host = input.substringBefore('/').substringBefore(':')
                    val fallback = if (isLocal(host)) "http" else "https"
                    "$fallback://$input"
                }

                // `javascript:` / `file:` / `data:` / `intent:` / 自定义 scheme
                else -> return Result.Rejected("只允许 http/https，$scheme: 已被拒绝")
            }
        }

        val afterScheme = candidate.substringAfter("://", "")
        val authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isBlank()) return Result.Rejected("网址缺少主机名")
        if (authority.contains('@')) return Result.Rejected("网址不能带用户名或密码")

        val host = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        if (host.isBlank() || host == "." || host == "..") {
            return Result.Rejected("网址缺少有效的主机名")
        }
        return Result.Ok(candidate)
    }

    /**
     * WebView 导航层用：这个地址能不能放行。
     *
     * `about:blank` 是空白页自己加载的，单独放行；其余必须是 http/https。
     */
    fun isAllowedPage(url: String): Boolean {
        val u = url.trim()
        if (u == "about:blank") return true
        return (normalize(u) as? Result.Ok)?.url?.isNotEmpty() == true
    }

    private fun isLocal(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        return EndpointSafety.isLocalHost(h)
    }
}
