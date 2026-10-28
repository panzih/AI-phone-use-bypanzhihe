package com.aiphone.assistant.agent

import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind

/**
 * Agent 的常量与纯函数。
 *
 * 从 [Agent] 的 companion 中拆出：门面类只保留稳定 API，
 * 运行期（[AgentSession] 与各阶段实现）统一从这里取配置。
 */
internal object AgentLimits {
    val TAP_HOME = TouchAction(kind = TouchKind.KEY_HOME)

    /**
     * 按钮文字里这些词表示“点了就有副作用”（发消息/付款/下单/授权…）。
     *
     * 动作后若界面没变化，命中这些词的按钮**只上报、不自动重试** ——
     * 指纹误判一次就可能重复发送/付款，是这套机制唯一会造成不可逆后果
     * 的地方，所以词表宁宽勿漏（误判顶多不重试、让人确认）。
     */
    val SIDE_EFFECT_WORDS = listOf(
        // 中文
        "发送", "发信", "发出", "提交", "确认", "确定", "删除", "支付", "付款",
        "付账", "下单", "订购", "订单", "购买", "买入", "抢购", "转账", "汇款",
        "寄出", "发布", "发表", "送出", "授权", "授予", "允许", "同意", "安装",
        "解绑", "注销", "退出登录",
        // 英文
        "send", "pay", "submit", "confirm", "delete", "remove", "order", "buy",
        "purchase", "post", "publish", "authorize", "allow", "grant", "checkout", "ok",
    )

    /**
     * 判断动作是不是有副作用（重试会造成重复发送/支付等）。
     * 返回 true 的动作在“指纹没变”时只上报、不重试；拿不准时按有副作用处理。
     * internal：本地单元测试直接构造 UiNode 验证，无需起 Agent 实例。
     */
    internal fun hasSideEffect(action: TouchAction, beforeNodes: List<UiNode>): Boolean {
        when (action.kind) {
            // 系统导航键无副作用
            TouchKind.KEY_BACK, TouchKind.KEY_HOME, TouchKind.KEY_RECENTS -> return false
            TouchKind.TAP -> {
                // 编号点击时模型只给 index、x/y 默认 0：按编号定位真正的目标，
                // 不能拿 (0,0) 做命中测试（会误判到左上角标题/根容器，甚至对
                // 发送、支付按钮错误放行重试）；只有坐标点击才用范围找。
                val hit = if (action.targetIndex > 0) {
                    beforeNodes.filter { it.index == action.targetIndex }
                } else {
                    beforeNodes.filter { it.bounds.contains(action.x, action.y) }
                }
                if (hit.isEmpty()) return true                 // 找不到命中目标，保守
                if (hit.any { labelHasSideEffect(it) }) return true
                // 点中的控件全无文字/描述（纯图标，无法判断意图）→ 保守当副作用
                if (hit.none { (it.text + it.contentDesc).isNotBlank() }) return true
                return false
            }
            else -> return true
        }
    }

    /** 控件文字/描述里是否含“发送/支付/确认/删除”等会造成副作用的词 */
    internal fun labelHasSideEffect(node: UiNode): Boolean {
        val raw = node.text.ifBlank { node.contentDesc }
        if (raw.isBlank()) return false
        val label = raw.lowercase()
        // 中文词按子串（中文不靠空格分词）；英文词按整词 —— 否则 "ok" 会
        // 误伤 book/look，让英文界面几乎不重试（功能静默失效）。
        val englishTokens = label.split(Regex("[^a-z]")).toHashSet()
        return SIDE_EFFECT_WORDS.any { word ->
            if (word.all { it in 'a'..'z' }) word in englishTokens
            else label.contains(word)
        }
    }

    /**
     * 藏完悬浮窗后等一小会儿再截图/注入。
     *
     * 隐藏是异步的（窗口变更要经过 WindowManager 和 SurfaceFlinger
     * 才真正生效），立刻截图有可能拍到还没消失的那一帧。
     * 100ms 对 60Hz 来说是 6 帧，足够。
     */
    const val OVERLAY_SETTLE_MS = 100L

    /**
     * 0.9.18 死锁护栏的判据：**目标还没记上时，切换请求必须给 open_app 让路**。
     *
     * `watcher.targetPkg` 唯一的来源是"应用真的到了前台"，而把应用拉到前台的
     * 正是 open_app。切换请求若把 open_app 掐死，目标永远出不来，切换请求
     * 就永远被"先让 AI 打开要操作的应用"拒掉 —— 用户连点多少次都没用
     * （0.9.17 蓝叠实证：4 次点击，前 3 次全撞进这个环，任务卡死）。
     */
    internal fun switchMustYieldToOpenApp(actionKind: TouchKind, targetPkg: String?): Boolean =
        actionKind == TouchKind.OPEN_APP && targetPkg.isNullOrBlank()

    /**
     * 按 HOME 让开后等界面切走：**上限**，不是固定等待。
     *
     * 实际等待由 [FOREGROUND_YIELD_POLL_MS] 轮询前台包名决定 ——
     * 前台不是纸盒了就立刻走，这个值只是"真卡住"时的兜底。
     */
    const val FOREGROUND_YIELD_MS = 600L

    /** 让开时轮询前台包名的间隔 */
    const val FOREGROUND_YIELD_POLL_MS = 120L

    /** 等待时检查急停的间隔 */
    const val STOP_POLL_MS = 100L

    /**
     * 一批最多执行几个动作。
     *
     * 解析层（[ActionParser.MAX_ACTIONS] = 12）留着更宽的上限只是为了兼容老模型，
     * 真正执行时裁到 6：连点 12 个动作的脚本，从第 2、3 个开始就基本在猜。
     */
    const val MAX_BATCH_ACTIONS = 6

    /**
     * 暂停时多久看一次"用户点继续了没有"。
     *
     * 50ms：暂停期间**不发请求、不读树**，轮询本身几乎没有成本；
     * 而"点了继续，两百毫秒后还没动"是很明显的迟滞感。
     */
    const val PAUSE_POLL_MS = 50L

    // 自动切副屏的判据常量已随状态机迁到 AutoSwitchWatcher（0.9.11）：
    // DESKTOP_DEBOUNCE_MS / TARGET_FRESH_MS / SELF_HOME_SUPPRESS_MS / AUTO_FAIL_COOLDOWN_MS

    /**
     * 普通动作后等界面稳定的参数：
     * - 最小起步：**只在没有观测到界面变化时**才生效的保守兜底。
     *   动作刚发出、界面还没开始动的时候，立刻采样会误判"已稳定"；
     *   但只要亲眼看到它变过又停住，就不必等这个值了（见 [WaitPolicy]）。
     * - 硬上限：时钟/进度条这类每秒变字的页面可能永远不稳定，到点就放行。
     * - 轮询间隔：每次取控件树是一次 binder 调用。原来是 200ms ——
     *   "界面 500ms 就停住了"也要等到 600~800ms 才返回，所以收到 100ms。
     * - 静止判据：观测到最后一次变化之后，还要连续静止这么久才算稳。
     *   100ms 的采样间隔下，这个值等于"至少两次采样一致且跨度足够"。
     */
    const val STABLE_MIN_MS = 400L
    const val STABLE_HARD_MS = 1200L
    const val STABLE_POLL_MS = 100L
    const val STABLE_SETTLE_MS = 200L

    /**
     * 打开应用（冷启动）的稳定参数：最小起步更长 —— 启动页常常整段
     * 不在控件树里，指纹会误判"已稳定"；硬上限也相应放宽。
     *
     * [OPEN_ARRIVED_SETTLE_MS] 是本地判据的静止要求：目标包名到了前台
     * 之后，界面还要静止这么久才算真的到了（启动页通常也会静止一瞬间）。
     * 有它才敢提前放行 —— 典型的"秒开"应用能从 1500ms 降到 500ms 上下。
     */
    const val OPEN_STABLE_MIN_MS = 1500L
    const val OPEN_STABLE_HARD_MS = 2500L
    const val OPEN_STABLE_POLL_MS = 150L
    const val OPEN_ARRIVED_SETTLE_MS = 300L

    /**
     * 判定"可能卡住了"的门槛（连续几步界面没变化 / 连续几次同一批动作）。
     *
     * 到这条线**只是提示云端**，不是止损 —— 最终决定权在云端（见
     * [STUCK_ADVICE_LIMIT] 与 RunGuards.stuckAdvice）。
     */
    const val STUCK_LIMIT = 5

    /**
     * 端侧"可能卡住了"最多提醒几次，之后才强制停。
     *
     * 用户 2026-10-21 定的权责：云端拥有最终决定权，端侧只提醒。
     * 但步数上限是"不限"，如果永远只提醒不停，一个原地打转的任务
     * 会一直烧 token —— 所以留这条硬兜底：提醒 3 次仍毫无进展就停。
     */
    const val STUCK_ADVICE_LIMIT = 3

    /** 打开内置浏览器后等页面加载的时间（下一步要读 DOM，不能读空白页） */
    const val BROWSER_LOAD_SETTLE_MS = 1200L

    /**
     * `browser_html` 默认最多给模型看多少字符。
     *
     * 8000 字符大约是 2K token 量级：够看清结构，又不至于把上下文顶爆。
     * 模型可以自己给 `max_chars` 调（最多 [BROWSER_HTML_MAX_CHARS]）。
     */
    const val BROWSER_HTML_DEFAULT_CHARS = 8_000

    /** `browser_html` 的硬上限：再长就该用 selector 缩小范围了 */
    const val BROWSER_HTML_MAX_CHARS = 24_000

    /** `browser_eval` 结果回灌给模型的字符上限 */
    const val BROWSER_EVAL_MAX_CHARS = 4_000

    /** 一次任务最多开几次内置浏览器（模型来回开没有意义，纯烧步数） */
    const val MAX_BROWSER_OPENS = 3

    /**
     * 连续几步"连根节点都读不到"就止损。
     *
     * 这是**环境**问题（无障碍服务被系统停用、当前窗口不交给无障碍），
     * 不是模型不行。以前这种情况会和"界面没变化"混在一起，5 步之后
     * 报一句"卡住了：界面连续 6 步没有任何变化"，把用户引到完全错的
     * 方向（他去看那个界面，界面明明是好的）。
     *
     * 6 步：足够跨过一次启动/切页的瞬时读不到，又不至于让用户白等太久。
     */
    const val UNREADABLE_TREE_LIMIT = 6

    /**
     * 控件树的一次读结果写进日志的一行。
     *
     * 0.8.5 的日志里这一行是 `控件树：1 个元素` —— 那个 "1" 其实是
     * 空列表的占位文本行数，于是"读不到"和"只有一个控件"在日志里
     * 长得一模一样，整整一批失败都没法复盘。现在把**根节点读到没有 /
     * 真的有几个元素 / 有没有被截断**分开写。
     */
    fun buildTreeLogLine(
        r: com.aiphone.assistant.channel.UiTreeRead,
        vdMode: Boolean = false,
    ): String = buildString {
        append("控件树：")
        append(r.nodes.size)
        append(" 个元素")
        when {
            // 副屏模式排在前面：那条通道**本来就没有控件树**，
            // 沿用"无障碍没生效"的措辞会让人（和模型）去查错方向
            vdMode ->
                append("（副屏模式：这条通道没有控件树，靠截图 + 坐标）")
            !r.rootAvailable ->
                append("（❌ 读不到根节点：无障碍服务没真正生效，或当前窗口不给无障碍）")
            r.nodes.isEmpty() ->
                append("（根节点读到了，但这一屏没有可交互元素：自绘/游戏/视频，或前台是纸盒自己）")
            else -> {
                append("（遍历 ${r.rawNodeCount} 个原始节点）")
                if (r.truncated) {
                    append(" ⚠️ 已达上限 ${com.aiphone.assistant.channel.AccessibilityChannel.TREE_LIMIT}")
                    append("，界面还有元素没发给模型")
                }
            }
        }
    }

    // 回迁与接管的常量已随迁移逻辑迁到 ChannelMigration（0.9.11）：
    // RETURN_REASON_MANUAL / AUTO_RETURN_COOLDOWN_MS / TAKEOVER_STRIKES / TAKEOVER_COOLDOWN_MS

    /** 纸盒回前台后等这么久再自动回迁，避免 ON_RESUME 抖动误触发（批 3） */
    const val AUTO_RETURN_DEBOUNCE_MS = 600L

    /** 连续几次解析不出动作就停 */
    const val MAX_PARSE_FAILS = 5

    /**
     * 一次任务内端侧**主动**关弹窗的次数上限。
     *
     * 正常任务遇不到几个挡路弹窗；这个上限是给"关掉又弹出来"的循环兜底的 ——
     * 到点就把判断交回模型，别把整趟任务耗在关弹窗上。
     */
    const val LOCAL_DISMISS_LIMIT = 8

    /**
     * 同一轮里最多调几次技能。
     *
     * 技能结果会留在上下文里，模型可能陷入"再查一次确认"。
     * 给三次机会足够（查文档 + 调一次 + 补查），之后必须做决定。
     */
    const val MAX_SKILL_CALLS = 3

    /**
     * 同一轮里最多给几次截图。
     *
     * 截图很贵，而且模型可能陷入"我要看图 → 还是不确定 → 再要图"。
     * 给两次机会，之后就必须按现有信息做决定。
     */
    const val MAX_IMAGE_REQUESTS = 2

    /**
     * 一次任务里 need_image + capture 总共最多给多少张图。
     *
     * 和 [MAX_IMAGE_REQUESTS]（同一轮内的限制）是两条维度：那个每轮重置、
     * 这个跨整段任务累计。副屏模式每步自动带的刚需图不计入这里。
     */
    const val MAX_TOTAL_IMAGES = 20

    /**
     * 一批动作里最多截几张图。
     *
     * 同一批动作之间的间隔只有几百毫秒，画面几乎没变；模型要是连写几个
     * `capture`，多出来的那些纯粹是白花钱。第二张起只回一行说明。
     */
    const val MAX_CAPTURE_PER_BATCH = 1

    /**
     * 超限后模型还坚持要图/要技能，最多重试几次。
     *
     * 超限后如果模型给了动作，就执行动作（别丢有效动作），
     * 但如果它连续好几次都只给"要图/要技能 + 随便一个动作"，
     * 说明它根本没打算靠现有信息做决策，继续下去就是烧钱。
     * 给 3 次机会，之后直接停。
     */
    const val OVER_LIMIT_MAX_RETRY = 3

    /**
     * 上下文预算。
     *
     * 上下文**不做裁剪**（缓存按前缀匹配，一裁前缀就断，反而更贵），
     * 所以这里给个上限兜底，避免请求被服务端直接拒掉。
     *
     * 这两个数是保守值，按所用模型的窗口大小调整。
     */
    const val CONTEXT_WARN_TOKENS = 100_000
    const val CONTEXT_STOP_TOKENS = 120_000
}
