package com.aiphone.assistant.llm

/**
 * 接口地址的安全判定。
 *
 * ## 为什么需要它
 *
 * `network_security_config` 全局放开了明文 HTTP —— 这是知情权衡：本地模型
 * 服务（Ollama / vLLM / LM Studio）基本跑在 `http://192.168.x.x:11434`
 * 这种地址上，而 Android 的 domain-config 又不支持 IP 段白名单，只能
 * 全局允许或全局禁止。代价写在那份配置的注释里：用户把地址填成
 * **公网** `http://` 时，API Key 会明文经过网络。
 *
 * 原来这件事只有 LlmClient 里一条 `Log.w` —— **日志用户不会看**。
 * 把判据抽到这里，设置页就能在输入框下面钉一条常驻警告；
 * 两处共用同一份规则，不会再出现"日志警告了、界面没提示"。
 *
 * ## 什么算"本地"（不警告）
 *
 * 判据是白名单而不是黑名单：只对**明确是内网**的主机放行，剩下的都警告。
 * 宁可在"奇怪的本地地址"上多提醒一次，也不能让公网地址静默漏过。
 */
object EndpointSafety {

    /**
     * 明文 HTTP 且主机不是本机/局域网 → 返回给用户看的警告文案；否则 null。
     *
     * 只关心 scheme 和 host，不看路径（`http://x/v1` 和 `http://x` 等价）。
     */
    fun cleartextWarning(baseUrl: String): String? {
        val url = baseUrl.trim().lowercase()
        if (!url.startsWith("http://")) return null
        val host = extractHost(url.removePrefix("http://"))
        if (host.isBlank()) return null
        if (isLocalHost(host)) return null
        return "这个地址是公网明文 HTTP：API Key 和对话内容会以明文经过网络，" +
            "同网络下的人可能看到。能换 https:// 就换；换不了就只在自己放心的网络里用。"
    }

    /** 从 `host[:port][/path]` 里取主机名，兼容 IPv6 字面量 `[::1]:8080` */
    internal fun extractHost(authority: String): String {
        val hostAndPort = authority.substringBefore('/')
        return if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']').removePrefix("[")
        } else {
            hostAndPort.substringBefore(':')
        }
    }

    /**
     * 本机 / 内网 / 明显非公网的写法。
     *
     * 覆盖：localhost、IPv6 环回与私有段、IPv4 环回/私有段/链路本地、
     * 单标签主机名（`myserver` 这种只能出现在内网 DNS 里）、
     * `.local` / `.lan` / `.home` 这类内网后缀。
     */
    internal fun isLocalHost(host: String): Boolean {
        if (host == "localhost") return true
        if (host == "::1") return true
        // IPv6 唯一本地地址 fc00::/7 与链路本地 fe80::/10
        if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")) {
            if (host.contains(':')) return true
        }
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")) return true
        // 不带点的单标签主机名：公网 DNS 解析不出这种名字（RFC 882 时代就废弃了），
        // 它只可能是本机 hosts 或内网 DNS —— 而内网就是我们要放行的那类
        if (!host.contains('.')) return true

        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 ||                     // 10.0.0.0/8（含模拟器宿主机 10.0.2.2）
                a == 127 ||                       // 环回
                (a == 192 && b == 168) ||         // 192.168.0.0/16
                (a == 172 && b in 16..31) ||      // 172.16.0.0/12
                (a == 169 && b == 254)            // 链路本地
        }
        return false
    }
}
