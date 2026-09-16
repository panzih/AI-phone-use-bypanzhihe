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

/**
 * 思考模式（模型的思维链）。
 *
 * ## 为什么要有这个开关
 *
 * DeepSeek 官方文档写得很清楚：**思考模式默认就是开着的，强度默认 high**。
 * 也就是说如果不发任何参数，每一步都在做高强度的思维链推理 ——
 * 对"点哪个按钮"这种任务既慢又贵，但确实更准。
 *
 * 所以这里不是"打开思考"，而是**把控制权交给用户**。
 *
 * ## 参数是怎么发的
 *
 * 官方给的 OpenAI 格式是：
 *   - 开关：`{"thinking": {"type": "enabled" | "disabled"}}`
 *   - 强度：`{"reasoning_effort": "low" | "high" | "max"}`
 *
 * [SERVER_DEFAULT] 一个参数都不发，完全跟着服务端的默认走。
 * 另外：**思考模式下服务端会忽略 temperature**（官方原话：不报错但也不生效），
 * 所以开着思考时我们干脆不传它。
 *
 * 注意这套是 DeepSeek 的参数名。别的服务商可能不认 —— 不认就改回
 * [SERVER_DEFAULT]，那就一个额外参数都不发了。
 */
enum class ThinkingMode(
    val id: String,
    val label: String,
    val note: String,
    /** 发给服务端的 thinking.type；null 表示不发这个字段 */
    val toggle: String?,
    /** 发给服务端的 reasoning_effort；null 表示不发 */
    val effort: String?,
) {
    SERVER_DEFAULT(
        id = "default",
        label = "跟随服务端默认",
        note = "一个参数都不发。DeepSeek 的默认是「开着 + 强度 high」，也就是最贵但最准的一档",
        toggle = null,
        effort = null,
    ),
    OFF(
        id = "off",
        label = "关闭",
        note = "不推理，直接给动作。最快最省，适合「点哪个按钮」这类简单判断",
        toggle = "disabled",
        effort = null,
    ),
    LOW(
        id = "low",
        label = "低",
        note = "少量推理。速度接近关闭，遇到复杂界面比关闭更稳",
        toggle = "enabled",
        effort = "low",
    ),
    HIGH(
        id = "high",
        label = "高",
        note = "充分推理。慢一些、贵一些，界面复杂或者要绕圈子时更靠谱",
        toggle = "enabled",
        effort = "high",
    ),
    MAX(
        id = "max",
        label = "最高",
        note = "推理拉满。最慢最贵，只在你确认任务确实难的时候用",
        toggle = "enabled",
        effort = "max",
    );

    /**
     * 是不是真的在做思维链。
     *
     * [SERVER_DEFAULT] 也算"可能在做" —— 因为 DeepSeek 的默认是开着的，
     * 所以我们不能替它断言"没思考"。这个属性只用来决定**要不要传
     * temperature**：只要可能在做思维链，传了也没用。
     */
    val thinkingOn: Boolean get() = this != OFF

    companion object {
        fun fromId(id: String?): ThinkingMode =
            entries.firstOrNull { it.id == id } ?: SERVER_DEFAULT
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

    /** 思考模式。默认跟服务端走，不改用户原来看到的行为 */
    val thinking: ThinkingMode = ThinkingMode.SERVER_DEFAULT,

    // ---------- 操作授权 ----------
    val mode: OperationMode = OperationMode.ACCESSIBILITY,

    // ---------- 开发者设置 ----------
    /**
     * 一次任务最多走多少步。
     *
     * 这不是"防死循环"（那个由 Agent 里的控件树指纹和重复动作检测负责），
     * 是**给成本兜底** —— 每步都要调一次模型，步数直接等于花多少钱。
     * 30 步对绝大多数任务够了；卡住的时候加步数不如把任务拆小。
     *
     * **0 表示不限。** 设置页的滑块拉到最右就是这个值。不限不等于失控：
     * 模型自己判断做完/做不下去会主动收尾（finished / failed），
     * 而且上下文接近模型窗口上限时 Agent 会强制停下。
     */
    val maxSteps: Int = 30,

    /**
     * 多久没发消息就清空上下文。0 = 不清空（**默认**）。
     *
     * 默认改成不清空，因为服务端的上下文缓存**按前缀匹配**：
     * 上下文留着不用重算，命中缓存的那部分便宜很多；
     * 一清掉，下次请求就是全新前缀，全部按未命中计价。
     *
     * 所以"清空"现在只是给用户的一个手动开关，
     * 不该是默认行为。
     */
    val autoClearMinutes: Int = 0,

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

/**
 * 步数文案。**上限 0 = 不限**，这个约定散落在提示词、界面、悬浮窗三处，
 * 所以文案也统一在这里生成 —— 否则迟早会出现"第 3 / 0 步"这种东西。
 */
fun stepsLabel(step: Int, maxSteps: Int): String =
    if (maxSteps <= 0) "第 $step 步（不限）" else "第 $step / $maxSteps 步"
