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
 * ## 五档，每一档都明确对应「接口上到底发什么」
 *
 *   OFF → LOW → HIGH → MAX → 默认
 *
 * | 档位 | 请求体里实际发的内容 |
 * |---|---|
 * | OFF   | `thinking: {"type": "disabled"}` |
 * | LOW   | `thinking: {"type": "enabled"}` + `reasoning_effort: "low"` |
 * | HIGH  | `thinking: {"type": "enabled"}` + `reasoning_effort: "high"` |
 * | MAX   | `thinking: {"type": "enabled"}` + `reasoning_effort: "max"` |
 * | 默认  | 什么都不发（用接口自己的默认） |
 *
 * 参数名与取值来自 DeepSeek 接口文档（guides/thinking_mode）：
 *   - 开关：`{"thinking": {"type": "enabled/disabled"}}`
 *   - 强度：`{"reasoning_effort": "low/high/max"}`
 *   - **思考默认开启，且默认 effort = high** —— 所以「什么都不发」实际就等于 HIGH
 *
 * 以前这里只写"由服务端自己决定（DeepSeek 默认 HIGH，Kimi 默认 balance）"，
 * 那是凭印象写的（Kimi 那句根本没有依据）。这一版把每一档改成**接口事实**：
 * 界面上直接显示这一档会发什么参数，用户可以自己核对。
 *
 * ## 免费通道的两个例外（接口实测，不是猜的）
 *
 *   - 免费网关**不接受** `thinking: {"type": "disabled"}`（space-bunny 直接 400），
 *     所以 OFF 档在免费通道**不发这个字段**（上游本来默认就不推理）；
 *   - 免费通道的 `reasoning_effort` 只是"被接受"，上游不一定真按它推理 ——
 *     免费池里有的模型固定思考、关不掉（网关会回 1210），有的压根不推理。
 *     真实行为只能以接口返回为准，[apiFor] 会把这两点如实地显示出来。
 */
enum class ThinkingMode(
    val id: String,
    val label: String,
    val note: String,
    val toggle: String?,
    val effort: String?,
    /** 这一档实际发出去的接口参数（设置页原样显示，用户可以直接核对） */
    val api: String,
) {
    OFF(
        id = "off",
        label = "OFF",
        note = "不推理，直接给动作。最快最省，适合「点哪个按钮」这类简单判断",
        toggle = "disabled",
        effort = null,
        api = "thinking.type = disabled",
    ),
    LOW(
        id = "low",
        label = "LOW",
        note = "少量推理。速度接近 OFF，复杂界面比 OFF 稳",
        toggle = "enabled",
        effort = "low",
        api = "thinking.type = enabled + reasoning_effort = low",
    ),
    MEDIUM(
        id = "medium",
        label = "MED",
        note = "中等推理。给只认 medium 的服务商用；DeepSeek 不认这一档，所以它不出现在 DeepSeek 的滑块上",
        toggle = "enabled",
        effort = "medium",
        api = "thinking.type = enabled + reasoning_effort = medium",
    ),
    HIGH(
        id = "high",
        label = "HIGH",
        note = "充分推理。慢一些、贵一些，绕圈子的任务更靠谱",
        toggle = "enabled",
        effort = "high",
        api = "thinking.type = enabled + reasoning_effort = high",
    ),
    MAX(
        id = "max",
        label = "MAX",
        note = "推理拉满。最慢最贵，只在你确认任务确实难的时候用",
        toggle = "enabled",
        effort = "max",
        api = "thinking.type = enabled + reasoning_effort = max",
    ),
    /**
     * 自定义强度（用户 2026-10-22 要求）。
     *
     * 为什么需要它：服务商越来越多，各自的 effort 词表也在变
     * （low/medium/high/max、minimal、digital…），写死一张表必然过时。
     * 这一档把「填什么」交给用户自己 —— 填了原样发 `reasoning_effort`，
     * **留空就什么都不发**（等同「默认」，由服务商决定）。
     *
     * 免费通道唯一例外：这个渠道本来就不一定认 effort，填错只会上游报错 ——
     * 所以设置页会提示"免费通道可能被忽略"。
     */
    CUSTOM(
        id = "custom",
        label = "自定义",
        note = "自己填 reasoning_effort（比如 high / medium / minimal）；留空 = 什么都不发，用服务商默认",
        toggle = "enabled",
        effort = null,
        api = "thinking.type = enabled + reasoning_effort = 你填的值（留空则都不发）",
    ),

    DEFAULT(
        id = "default",
        label = "默认",
        note = "什么都不发，由接口自己决定 —— DeepSeek 文档：思考默认开启、默认 effort = high；" +
            "免费网关由上游各自决定（有的固定思考关不掉，有的不推理）",
        toggle = null,
        effort = null,
        api = "（不发任何思考参数）",
    );

    /**
     * 在当前通道下，这一档**实际**会发什么 —— 给设置页原样显示。
     *
     * 免费通道有例外（OFF 不发），不这样分开写的话，界面上的说明会和
     * 真实请求对不上 —— 那正是这次要改掉的毛病。
     */
    fun apiFor(freeLane: Boolean): String = when {
        freeLane && this == OFF -> "不发（免费上游不接受 disabled）"
        else -> api
    }

    /** CUSTOM 档用用户填的值渲染「实际会发什么」（设置页原样显示） */
    fun apiFor(freeLane: Boolean, customEffort: String): String = when {
        this != CUSTOM -> apiFor(freeLane)
        customEffort.isBlank() -> "（留空：不发任何思考参数，用服务商默认）"
        else -> "thinking.type = enabled + reasoning_effort = " + customEffort.trim()
    }

    /**
     * 是不是真的在做思维链。
     *
     * 只用来决定要不要传 temperature：在做思维链就传了也没用。
     * OFF 明确关了；DEFAULT 不发参数，服务端可能开也可能关，保守起见不传。
     */
    val thinkingOn: Boolean
        get() = this == LOW || this == MEDIUM || this == HIGH || this == MAX || this == CUSTOM

    companion object {
        fun fromId(id: String?): ThinkingMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * 这个通道/服务商**实际支持**的档位，按「从弱到强、默认放最右」排好。
         *
         * ## 为什么要按服务商分，而不是全列出来
         *
         * 把六档全摆出来看着整齐，实际是骗人：DeepSeek 只认 low/high/max，
         * 拉到 MEDIUM 发过去是 400；反过来某些 OpenAI 兼容服务只认 low/medium/high，
         * 根本没有 max。滑块上摆一个"发了就报错"的档位，比不给更糟。
         *
         * ## 数据从哪来（老实说：没有远程接口）
         *
         * 2026-10-19 查过一圈：`/zen/v1/models` 只回模型 id；models.dev 的
         * `api.json` 有 5.8MB（手机端不适合拉）；DeepSeek 的 `/models` 也不带能力位。
         * 所以这里按**服务商家族**判定，规则写在下面，改的时候只改这一处：
         *   - 免费网关：上游各异，给最保守的四档（关/低/高/默认）
         *   - DeepSeek 官方：文档写死 low/high/max
         *   - 其他 OpenAI 兼容：低/中/高（medium 是 OpenAI 的官方取值）
         *
         * 哪天接上真正的能力接口，只需要把这里换成"读回来的列表"，
         * 调用方（滑块）不用动。
         */
        fun levelsFor(baseUrl: String, freeLane: Boolean): List<ThinkingMode> = when {
            freeLane -> listOf(OFF, LOW, HIGH, DEFAULT, CUSTOM)
            baseUrl.contains("deepseek", ignoreCase = true) -> listOf(OFF, LOW, HIGH, MAX, DEFAULT, CUSTOM)
            else -> listOf(OFF, LOW, MEDIUM, HIGH, DEFAULT, CUSTOM)
        }

        /**
         * 这一档实际要发给服务商的 effort；null = 不发。
         *
         * CUSTOM 档的值来自用户输入，所以不能写在枚举里 —— 统一走这个入口，
         * 免得 LlmClient 和设置页各拼一套。
         */
        fun effortOf(mode: ThinkingMode, customEffort: String): String? = when (mode) {
            ThinkingMode.CUSTOM -> customEffort.trim().ifBlank { null }
            else -> mode.effort
        }
    }
}

/**
 * 点击前复核的档位（用户 2026-10-23 要求）。
 *
 * 模型给坐标时，那个点是**它估出来的**：控件树编号点击会落到元素中心，
 * 坐标点击却可能落在相邻控件、空白处或图标缝隙里。复核就是把"即将点下去
 * 的那一小块画面"裁出来、画上十字准星，再让模型自己看一眼 —— 在真正
 * 注入之前把"点偏了"拦下来。
 *
 * 三档而不是开关：复核要额外调一次模型。不开是零成本；只复核坐标点击
 * 能覆盖绝大多数"点偏"又不为精确的编号点击重复花钱；全复核最稳，
 * 但每点一次都多一次往返。默认关，用户自己权衡。
 */
enum class TapReviewMode(
    val id: String,
    val label: String,
    val note: String,
) {
    OFF(
        id = "off",
        label = "关闭",
        note = "不额外请求，模型点哪儿就是哪儿。最省最快",
    ),

    COORDINATES(
        id = "coords",
        label = "只复核坐标点击",
        note = "模型报 x/y（没有元素编号）时，点之前把落点附近截图发回给它再确认一次；" +
            "按编号点的位置本来就是控件中心，不重复花这笔钱。推荐",
    ),

    ALL(
        id = "all",
        label = "复核所有点击",
        note = "每次点击/长按/双击前都多问一次模型。最稳，但每点一次多一次请求，" +
            "免费模型上会明显变慢",
    );

    companion object {
        fun fromId(id: String?): TapReviewMode =
            entries.firstOrNull { it.id == id } ?: OFF
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
     *
     * **默认关闭**：新安装走自定义 Key。免费通道是显式选项，首次开启
     * 必须看过 [freeLaneNoticeAccepted] 对应的风险说明。
     */
    val useFreeModel: Boolean = false,

    /**
     * 是否已经确认过免费通道的风险说明。
     *
     * 只在**第一次**打开免费通道时弹一次；确认后不再打扰。老版本用户
     * 如果已经开着免费通道、但没有这个标记，启动时会补一次告知。
     */
    val freeLaneNoticeAccepted: Boolean = false,

    /** 思考模式。默认「默认」：让服务端自己挑 */
    val thinking: ThinkingMode = ThinkingMode.DEFAULT,

    /**
     * CUSTOM 档用的 reasoning_effort 原文（用户自己填）。
     *
     * 留空 = 什么都不发（等价于「默认」）。**原样发**，不做白名单校验：
     * 服务商各有自己的词表（low/medium/high/max/minimal…），堵死任何一个
     * 都会让"新服务商能用的档位"在这一版用不了。
     */
    val customEffort: String = "",

    /**
     * 悬浮条要不要做背景模糊（毛玻璃）。
     *
     * 默认**关**，原因写在这里免得以后有人"顺手打开"：
     *   - 实现用的是 Android 12+ 的跨窗口模糊（`setBlurBehindRadius` +
     *     `FLAG_BLUR_BEHIND`），**系统没开这个特性时完全没效果**；
     *   - 模拟器（AVD）上实测：整块屏幕都被糊掉了，不只是条子背后那一条
     *     （模拟器的模糊区域不裁剪）—— 真机应当只糊窗口背后，但没在真机确认前
     *     不适合默认打开；
     *   - 模糊是主线程实时合成，低端机会掉帧。
     * 所以做成开关：想试的用户自己打开。
     */
    val overlayBlur: Boolean = false,

    /**
     * 点击前复核。见 [TapReviewMode]。
     *
     * 默认关：复核是一次**额外的模型请求**（还带一张局部截图），会拖慢每一步。
     * 用户明确要求时才开。
     */
    val tapReview: TapReviewMode = TapReviewMode.OFF,

    /**
     * 允许 AI 执行高风险操作（支付、转账、删除、发送、授权、验证码等）。
     *
     * **默认关**。关着时执行层直接拦截，不靠提示词自觉 —— 屏幕/网页内容
     * 是不可信输入，模型可能被诱导去点支付按钮。打开后不再逐次让用户确认，
     * 但每个高风险动作仍要过一次**独立的模型二次确认**，结果写进运行日志。
     */
    val allowHighRiskActions: Boolean = false,

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
     * 端侧**提示**挡路的无副作用弹窗（0.9.21 起不再自己点）。
     *
     * 开着的时候，每步读界面之前端侧会先用本地规则看一眼：如果页面是个
     * 广告/更新/引导类弹窗，且有「稍后 / 跳过 / 取消」这类**不产生任何进展**
     * 的关闭按钮，就在这一步的消息里告诉模型「安全关闭按钮是哪个编号」——
     * 关不关**由云端模型决定**（它可以直接点，也可以下发 `dismiss_dialog`）。
     *
     * 为什么不自己点：词表再保守也是猜的，端侧一旦动手，云端连发生过什么都不知道
     * （T42「云端最终决定权」，2026-10-21）。上限 `LOCAL_DISMISS_LIMIT` 仍是一次
     * 任务内的总提醒次数，防止"关掉又弹出来"的页面把提示刷成噪声。
     *
     * 安全闸是硬的，不受这个开关影响：授权页、支付页、带副作用按钮的页面
     * 端侧连建议都不给（见 LocalRuleEngine）。
     */
    val localDialogDismiss: Boolean = true,

    /**
     * 启动时自动检查更新。
     *
     * **默认关**：检查更新会把 IP（服务器必然能看到）、HTTP User-Agent、
     * 设备标识（Android ID）、包名和版本号发给更新站。用户没主动要求联网时
     * 不替他做这个决定；「关于」页仍有手动检查。
     */
    val autoCheckUpdate: Boolean = false,

    /**
     * 用户点过「跳过这个版本」的 versionCode。
     *
     * 只跳过**这一版**：下一版仍会提示。这是「跳过」不是「关掉更新」，
     * 后者由 [autoCheckUpdate] 负责。
     */
    val skippedUpdateCode: Int = 0,
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
