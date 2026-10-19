package com.aiphone.assistant.agent

/**
 * 「复盘 / 复评」这句话是不是在让我**回看上一趟**，而不是开新任务。
 *
 * 为什么用短语而不是加个按钮：复盘是一次性的、低频的，加按钮会把主界面
 * 又撑一块；而用户想说这两个字的时候，本来就会在输入框里说。
 *
 * 判据刻意收得很紧：**只认"光秃秃的复盘"，后面不能跟任何实际任务**。
 * 「复盘之后帮我打开微信」如果被当成纯复盘，那句"打开微信"就没人执行了 ——
 * 判错的代价不对称，所以宁可漏判（最多当普通任务跑一趟）。
 */
object ReviewShortcut {

    private val VERBS = listOf("复盘", "复评", "回顾")
    private val FILLERS = listOf("一下", "一次", "这趟", "刚才", "吧", "呢")

    fun matches(text: String): Boolean {
        var rest = text.trim().trimEnd('。', '！', '!', '?', '？', '，', ',', ' ')
        if (rest.isEmpty()) return false
        if (rest == "总结这趟" || rest == "总结一下") return true
        // 英文别名：一是有些人习惯打英文，二是自动化测试没法用 `input text` 敲中文
        if (rest.lowercase() in setOf("review", "retro", "retrospective")) return true

        val verb = VERBS.firstOrNull { rest.startsWith(it) } ?: return false
        rest = rest.removePrefix(verb)
        // 把"一下 / 这趟 / 吧"这类语气词剥干净；剥完还剩字 = 后面有真事，不是复盘
        var changed = true
        while (changed) {
            changed = false
            for (f in FILLERS) {
                if (rest.startsWith(f)) {
                    rest = rest.removePrefix(f)
                    changed = true
                }
            }
        }
        return rest.isBlank()
    }
}
