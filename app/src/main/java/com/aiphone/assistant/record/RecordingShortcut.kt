package com.aiphone.assistant.record

/**
 * 发送框里的本地快捷指令。
 *
 * 用户在发任务的输入框里直接说「操作记录」「开始录制」等短语时，
 * 直接打开录制页，不发给云端模型 —— 不花 token，也不会被模型理解偏。
 *
 * ## 匹配到什么程度
 *
 * 只做短语匹配、不做模型意图识别。判定是"**摘掉礼貌用语和水词之后，
 * 整句正好等于下面某个短语**"：
 *
 *   「操作记录」                            → 命中
 *   「帮我整理一下刚才的操作记录」            → 命中（摘掉"帮我/一下/刚才的"）
 *   「整理操作记录里提到的联系人然后发给我妈」 → 不命中（摘完还剩一堆字）
 *
 * 之所以坚持"整句相等"而不是"包含关键词"：一旦放宽成后者，上面第三种
 * 真任务会被吞掉 —— 用户看到的是"发送框坏了"，比让他多点两下难受得多。
 */
object RecordingShortcut {

    /** 这句话是不是「打开操作记录」的快捷指令。 */
    fun matches(raw: String): Boolean {
        var core = normalize(raw)
        if (core.isBlank()) return false
        // 「剥礼貌用语 → 摘水词」走两轮：两者谁在前都可能
        // （「帮我整理一下刚才的操作记录」 vs 「整理一下我的操作记录」）
        repeat(2) {
            core = stripPrefixes(core)
            core = stripFillers(core)
        }
        return core in PHRASES
    }

    /** 去标点和空白、去掉「一下」，只留字和数字。 */
    private fun normalize(raw: String): String =
        raw.trim().lowercase()
            .filter { it.isLetterOrDigit() }
            .replace("一下", "")

    /** 剥掉开头的礼貌用语，可能叠好几层（「请帮我打开…」）。 */
    private fun stripPrefixes(raw: String): String {
        var core = raw
        var guard = 0
        while (guard++ < 6) {
            val p = PREFIXES.firstOrNull { core.startsWith(it) } ?: break
            core = core.removePrefix(p)
        }
        return core
    }

    /**
     * 摘掉不影响意图的填充词。
     *
     * 只摘这张**已知无害**的白名单，摘完仍然要求整句等于短语 ——
     * 不做"包含关键词就算"的模糊匹配（理由见类注释）。
     * 白名单里绝不能出现"操作""记录""录制"这类词：它们本身是短语的组成部分。
     */
    private fun stripFillers(raw: String): String {
        var out = raw
        for (f in FILLERS) out = out.replace(f, "")
        return out
    }

    /** 剥一层常见礼貌用语（长的放前面，避免只剥掉一半）。 */
    private val PREFIXES = listOf(
        "请帮我", "帮我", "我想要", "我要", "我想", "要",
        "请", "查看", "看看", "看下", "看", "打开", "进入", "进", "去", "到",
    )

    /** 与意图无关的填充词（长的放前面）。 */
    private val FILLERS = listOf(
        "刚才的", "刚刚的", "之前的", "以前的", "我自己的", "自己的",
        "刚才", "刚刚", "我的", "那个", "这", "我", "的", "一下",
    )

    private val PHRASES = setOf(
        "操作记录", "整理操作记录", "我的操作记录",
        "录制", "开始录制", "录制操作", "录制一个操作",
        "录制宏", "录宏", "录一个宏", "新建宏", "新建操作", "学习操作",
    )
}
