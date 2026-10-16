package com.aiphone.assistant.data

/**
 * 操作方式。
 *
 * 两条路都是真的能跑的，区别只在**动作和截图从哪儿走**：
 *
 *   无障碍      `dispatchGesture` + `takeScreenshot`。一次授权长期有效，
 *               重启手机也不失效 —— 默认这条。
 *   ADB/Shizuku 借 Shizuku 的 shell 身份发 `input` / `screencap`。**能点
 *               无障碍点不动的地方**（安全窗口、某些系统弹窗）、**能截安全窗口**；
 *               代价是无 root 时每次重启手机都要重新启动一次 Shizuku。
 *               控件树仍然借无障碍（开着更准；不开就退化成"看截图用坐标"）。
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
        available = true,
        note = "借 Shizuku 的 shell 身份点击/滑动/输入，能点无障碍点不动的地方、" +
            "能截安全窗口。控件树仍走无障碍（开着更准，不开就只看截图用坐标）。" +
            "⚠️ 没 root 时每次重启手机都要重新启动一次 Shizuku。",
    );

    companion object {
        fun fromId(id: String?): OperationMode =
            entries.firstOrNull { it.id == id } ?: ACCESSIBILITY
    }
}

/**
 * 思考模式（模型的思维链）。
 *
 * ## 五档，从最左拉到最右
 *
 *   OFF → LOW → HIGH → MAX → 默认
 *
 * 前四档是显式的强度，最右一档「默认」**什么参数都不发**，
 * 让服务端自己挑（DeepSeek 默认 HIGH，Kimi 默认 balance）。
 *
 * ## 为什么要有这个
 *
 * DeepSeek 默认就是 HIGH —— 不发参数 = 每步都在最贵的档上。
 * 所以把控制权交给用户：不想推理就拉到 OFF，想用服务端默认就拉到最右。
 *
 * ## 参数怎么发
 *
 *   OFF   → thinking.type=disabled
 *   LOW   → thinking.type=enabled, reasoning_effort=low
 *   HIGH  → thinking.type=enabled, reasoning_effort=high
 *   MAX   → thinking.type=enabled, reasoning_effort=max
 *   默认  → 一个参数都不发
 */
enum class ThinkingMode(
    val id: String,
    val label: String,
    val note: String,
    val toggle: String?,
    val effort: String?,
) {
    OFF(
        id = "off",
        label = "OFF",
        note = "不推理，直接给动作。最快最省，适合「点哪个按钮」这类简单判断",
        toggle = "disabled",
        effort = null,
    ),
    LOW(
        id = "low",
        label = "LOW",
        note = "少量推理。速度接近 OFF，复杂界面比 OFF 稳",
        toggle = "enabled",
        effort = "low",
    ),
    HIGH(
        id = "high",
        label = "HIGH",
        note = "充分推理。慢一些、贵一些，绕圈子的任务更靠谱",
        toggle = "enabled",
        effort = "high",
    ),
    MAX(
        id = "max",
        label = "MAX",
        note = "推理拉满。最慢最贵，只在你确认任务确实难的时候用",
        toggle = "enabled",
        effort = "max",
    ),
    DEFAULT(
        id = "default",
        label = "默认",
        note = "什么都不发，由服务端自己决定（DeepSeek 默认 HIGH，Kimi 默认 balance）",
        toggle = null,
        effort = null,
    );

    /**
     * 是不是真的在做思维链。
     *
     * 只用来决定要不要传 temperature：在做思维链就传了也没用。
     * OFF 明确关了；DEFAULT 不发参数，服务端可能开也可能关，保守起见不传。
     */
    val thinkingOn: Boolean get() = this == LOW || this == HIGH || this == MAX

    companion object {
        fun fromId(id: String?): ThinkingMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
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
 * ## 它和记忆、历史的关系（这层关系是这个功能的核心）
 *
 * 一段上下文 = 一份固定的系统提示词（含当时注入的记忆）+ 一路追加的模型历史。
 * **模型历史会跟着上下文一起续接** —— 所以"不限"档下，AI 真的记得上一轮
 * 说过什么。
 *
 * 三点必须一起成立，缺一条前缀缓存就断：
 *   1. 系统提示词里的记忆只在**新开上下文**时取一次，之后不再重读文件
 *      （记忆文件长了也不换 —— 想用新记忆就清空上下文重开）
 *   2. 上一段的历史原样放回请求的最前面
 *   3. 每次都发"上次的 + 追加"
 *
 * 服务端只命中**完整匹配的缓存前缀单元**，所以第 3 条让重复部分可以复用：
 * 命中的输入按 $0.003/百万 计价，未命中是 $0.15/百万（差 50 倍）。
 * 这也是为什么"把历史带上"比"丢掉历史省 token"更省钱的真正原因。
 */
enum class ContextPolicy(
    val id: String,
    val label: String,
    val note: String,
) {
    RESET_EACH_TIME(
        id = "reset",
        label = "每次重置",
        note = "每发一条新任务就开一个全新的上下文，模型不记得上一条任务。每次都带上最新记忆、不会越跑越糊涂，代价是每步都要重新理解界面",
    ),

    H24(
        id = "h24",
        label = "超过 24 小时",
        note = "24 小时没发消息就清空上下文。日常用这一档：一天之内的连续操作能接上（模型记得你说过什么），隔天又是干净的",
    ),

    UNLIMITED(
        id = "never",
        label = "不限",
        note = "永远不清空，模型记得这次会话里的每一句话。最省往返（命中缓存的部分便宜 50 倍），但上下文会一直涨，迟早顶到模型窗口上限",
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

    /**
     * 免费模式：开启后忽略上面三项，走 opencode.ai 的免密聚合网关。
     *
     * 不需要填 API Key，模型列表由网关决定（DeepSeek V4 Flash、Kimi 等）。
     * 这是社区白嫖通道，有速率限制，不要拿来跑长任务。
     */
    val useFreeModel: Boolean = false,

    /** 思考模式。默认「默认」：让服务端自己挑 */
    val thinking: ThinkingMode = ThinkingMode.DEFAULT,

    // ---------- 操作授权 ----------
    val mode: OperationMode = OperationMode.ACCESSIBILITY,

    // ---------- 更多设置 ----------

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

    /**
     * 端侧自动关掉挡路的无副作用弹窗。
     *
     * 开着的时候，每步读界面之前端侧会先用本地规则看一眼：如果页面是个
     * 广告/更新/引导类弹窗，且有「稍后 / 跳过 / 取消」这类**不产生任何进展**
     * 的关闭按钮，就当场点掉 —— 模型看到的是清理干净的页面，
     * 省掉整整一轮「模型看到弹窗 → 下发关闭 → 再看一遍」。
     *
     * 安全闸是硬的，不受这个开关影响：授权页、支付页、带副作用按钮的页面
     * 端侧一律不碰（见 LocalRuleEngine）。关掉它就退回原来的行为：
     * 必须模型显式下发 dismiss_dialog 才动。
     */
    val localDialogDismiss: Boolean = true,
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
