package com.aiphone.assistant.data

/**
 * 操作方式。
 *
 * 目前只做无障碍 —— 它重启后自动恢复、不需要装任何额外东西。
 * ADB/Shizuku 那条路留着占位：能力更强（能截安全窗口），
 * 但无 root 时每次重启都要重新用无线调试授权一次，体验代价太大。
 */
enum class OperationMode(
    val id: String,
    val label: String,
    /** 当前版本是否真的能用。不能用的一律标出来，不假装支持 */
    val available: Boolean,
    val note: String,
) {
    ACCESSIBILITY(
        id = "accessibility",
        label = "无障碍",
        available = true,
        note = "系统级服务，一次授权长期有效，重启手机也不失效。不需要电脑，不需要装别的东西。",
    ),

    ADB(
        id = "adb",
        label = "ADB / Shizuku",
        available = false,
        note = "能截到银行/支付类页面（无障碍截不到），但无 root 时每次重启都要重新授权一次。当前版本还没接入。",
    );

    companion object {
        fun fromId(id: String?): OperationMode =
            entries.firstOrNull { it.id == id } ?: ACCESSIBILITY
    }
}

/** 自动清空上下文的档位。0 表示从不自动清空。 */
enum class AutoClear(val minutes: Int, val label: String) {
    NEVER(0, "不自动清空"),
    M30(30, "超过 30 分钟"),
    H1(60, "超过 1 小时"),
    H3(180, "超过 3 小时"),
    H12(720, "超过 12 小时"),
    D1(1440, "超过 1 天");

    companion object {
        fun fromMinutes(m: Int): AutoClear =
            entries.firstOrNull { it.minutes == m } ?: H1
    }
}

/**
 * 全部用户设置。
 *
 * 集中成一个 data class 而不是散在各处的 mutable 变量，原因是：
 *   - 存/取只有一处实现，不会出现"新加了一项忘了存"
 *   - 日志里可以直接打印整份配置，排查问题时不用问用户"你设置的是什么"
 */
data class AppSettings(
    // ---------- 模型 ----------
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val modelName: String = "deepseek-flash",
    /** original 保留原图；low 压到 512×512。手机 UI 文字多，默认必须 original */
    val detail: String = "original",

    // ---------- 操作授权 ----------
    val mode: OperationMode = OperationMode.ACCESSIBILITY,

    // ---------- 开发者设置 ----------
    /**
     * 一次任务最多走多少步。
     *
     * 这不是"防死循环"（那个由 Agent 里的画面哈希和重复动作检测负责），
     * 是**给成本兜底** —— 每步都要调一次模型，步数直接等于花多少钱。
     * 30 步对绝大多数任务够了；卡住的时候加步数不如把任务拆小。
     */
    val maxSteps: Int = 30,

    /** 多久没发消息就清空上下文。0 = 不清空 */
    val autoClearMinutes: Int = 60,

    /** 开启记忆：让 AI 有机会把上下文沉淀成洞察 */
    val memoryEnabled: Boolean = false,

    /** 保存记忆：清空上下文时把内容转成 md 存下来，而不是直接丢 */
    val keepMemory: Boolean = true,

    /** 每次任务写 run.log */
    val saveLogs: Boolean = true,

    /** 每步截图落盘 */
    val saveScreenshots: Boolean = true,
) {
    /** 界面上显示用的打码 Key，中间用星号 */
    val maskedApiKey: String
        get() = when {
            apiKey.isBlank() -> "未填写"
            apiKey.length <= 8 -> "****"
            else -> apiKey.take(4) + "****" + apiKey.takeLast(4)
        }

    val autoClear: AutoClear get() = AutoClear.fromMinutes(autoClearMinutes)
}
