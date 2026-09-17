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

/**
 * 上下文什么时候重开。
 *
 * ## 三档，从最左拉到最右
 *
 *   每次都重置  →  每发一条新任务就开全新的上下文。最干净，但每步都要重新
 *                  读界面、重新理解任务，也最贵
 *   超过 24 小时 →  24 小时没动静才清。日常最常用的档
 *   不限        →  永远不清空
 *
 * ## 它和记忆的关系（这层关系是这个功能的核心）
 *
 * **只有新开上下文时，才会把记忆塞进系统提示词。**
 *
 * 因为服务端的缓存按前缀匹配：往正在进行的对话中间插记忆，等于把前缀
 * 整段作废，下一次请求全部按未命中计价 —— 那比省下的记忆 token 贵得多。
 *
 * 所以"每次都重置"看起来最费，但记忆每次都能带上；
 * "不限"最省往返，但一条长对话里模型看不到记忆，得靠它自己调技能。
 */
enum class ContextPolicy(
    val id: String,
    val label: String,
    val note: String,
) {
    RESET_EACH_TIME(
        id = "reset",
        label = "每次重置",
        note = "每发一条新任务就开一个全新的上下文。每次都带着最新记忆、不会越跑越糊涂，代价是每步都要重新理解界面",
    ),

    H24(
        id = "h24",
        label = "超过 24 小时",
        note = "24 小时没发消息就清空上下文。日常用这一档：短期内的连续操作能接上，隔天又是干净的",
    ),

    UNLIMITED(
        id = "never",
        label = "不限",
        note = "永远不清空，上下文一直累积。最省事也最省往返，但一条对话里模型看不到记忆（要靠它自己调技能），而且迟早会顶到模型窗口上限",
    );

    /** 闲置多久算过期（分钟）。0 表示不按时间判断 */
    val idleMinutes: Int
        get() = when (this) {
            RESET_EACH_TIME -> 0
            H24 -> 24 * 60
            UNLIMITED -> 0
        }

    companion object {
        fun fromId(id: String?): ContextPolicy =
            entries.firstOrNull { it.id == id } ?: H24
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

    /**
     * 在副屏上操作。
     *
     * 开了之后：任务开始时用 Shizuku 建一块虚拟屏，AI 的截图和触控
     * 全部落到那块屏上，手机主屏留给你自己用。任务结束自动撤屏。
     *
     * **需要 Shizuku**（副屏的触控只能走 shell 的 input -d，
     * 无障碍的手势注入没有"指定屏幕"的参数）。
     *
     * 默认关：它比主屏模式弱 —— 副屏**读不到控件树**，模型只能看截图猜坐标。
     */
    val useVirtualDisplay: Boolean = false,

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

    /** 上下文什么时候重开。见 [ContextPolicy] */
    val contextPolicy: ContextPolicy = ContextPolicy.H24,

    /**
     * 记忆开关。
     *
     * 开了就**每次任务结束后让 AI 归纳一条洞察，追加到记忆文件末尾**，
     * 关掉就完全不写。只有一个开关 —— 用户不需要理解"归纳"和"不丢"
     * 的区别，那是实现细节。
     */
    val memoryEnabled: Boolean = false,

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

}

/**
 * 步数文案。**上限 0 = 不限**，这个约定散落在提示词、界面、悬浮窗三处，
 * 所以文案也统一在这里生成 —— 否则迟早会出现"第 3 / 0 步"这种东西。
 */
fun stepsLabel(step: Int, maxSteps: Int): String =
    if (maxSteps <= 0) "第 $step 步（不限）" else "第 $step / $maxSteps 步"
