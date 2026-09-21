package com.aiphone.assistant.record

/**
 * 发送框里的本地快捷指令。
 *
 * 用户在发任务的输入框里直接说「操作记录」「开始录制」等短语时，
 * 直接打开录制页，不发给云端模型 —— 不花 token，也不会被模型理解偏。
 *
 * 只做短语匹配、不做模型意图识别。为了不误伤正常任务，
 * 要求整句（去掉标点、礼貌用语、「一下」之后）正好是下面这些短语之一。
 */
object RecordingShortcut {

    /** 这句话是不是「打开操作记录」的快捷指令。 */
    fun matches(raw: String): Boolean {
        var core = normalize(raw)
        if (core.isBlank()) return false
        // 礼貌用语可能叠好几层（「请帮我打开…」），一层层剥，剥到短语为止
        var guard = 0
        while (guard++ < 6) {
            val p = PREFIXES.firstOrNull { core.startsWith(it) } ?: break
            core = core.removePrefix(p)
        }
        return core in PHRASES
    }

    /** 去标点和空白、去掉「一下」，只留字和数字。 */
    private fun normalize(raw: String): String =
        raw.trim().lowercase()
            .filter { it.isLetterOrDigit() }
            .replace("一下", "")

    /** 剥一层常见礼貌用语（长的放前面，避免只剥掉一半）。 */
    private val PREFIXES = listOf(
        "请帮我", "帮我", "我想要", "我要", "我想",
        "请", "查看", "看看", "打开", "进入", "进", "去", "到", "看",
    )

    private val PHRASES = setOf(
        "操作记录", "整理操作记录", "我的操作记录",
        "录制", "开始录制", "录制操作", "录制一个操作",
        "录制宏", "录宏", "录一个宏", "新建宏", "新建操作", "学习操作",
    )
}
