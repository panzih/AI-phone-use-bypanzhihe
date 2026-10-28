package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.agent.Agent.EventKind
import com.aiphone.assistant.agent.Agent.Listener
import com.aiphone.assistant.agent.AgentLimits.BROWSER_EVAL_MAX_CHARS
import com.aiphone.assistant.agent.AgentLimits.BROWSER_HTML_DEFAULT_CHARS
import com.aiphone.assistant.agent.AgentLimits.BROWSER_HTML_MAX_CHARS
import com.aiphone.assistant.agent.AgentLimits.BROWSER_LOAD_SETTLE_MS
import com.aiphone.assistant.agent.AgentLimits.CONTEXT_STOP_TOKENS
import com.aiphone.assistant.agent.AgentLimits.CONTEXT_WARN_TOKENS
import com.aiphone.assistant.agent.AgentLimits.FOREGROUND_YIELD_MS
import com.aiphone.assistant.agent.AgentLimits.FOREGROUND_YIELD_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.LOCAL_DISMISS_LIMIT
import com.aiphone.assistant.agent.AgentLimits.MAX_BATCH_ACTIONS
import com.aiphone.assistant.agent.AgentLimits.MAX_BROWSER_OPENS
import com.aiphone.assistant.agent.AgentLimits.MAX_CAPTURE_PER_BATCH
import com.aiphone.assistant.agent.AgentLimits.MAX_IMAGE_REQUESTS
import com.aiphone.assistant.agent.AgentLimits.MAX_PARSE_FAILS
import com.aiphone.assistant.agent.AgentLimits.MAX_SKILL_CALLS
import com.aiphone.assistant.agent.AgentLimits.MAX_TOTAL_IMAGES
import com.aiphone.assistant.agent.AgentLimits.OPEN_ARRIVED_SETTLE_MS
import com.aiphone.assistant.agent.AgentLimits.OPEN_STABLE_HARD_MS
import com.aiphone.assistant.agent.AgentLimits.OPEN_STABLE_MIN_MS
import com.aiphone.assistant.agent.AgentLimits.OPEN_STABLE_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.OVERLAY_SETTLE_MS
import com.aiphone.assistant.agent.AgentLimits.OVER_LIMIT_MAX_RETRY
import com.aiphone.assistant.agent.AgentLimits.PAUSE_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.STABLE_HARD_MS
import com.aiphone.assistant.agent.AgentLimits.STABLE_MIN_MS
import com.aiphone.assistant.agent.AgentLimits.STABLE_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.STABLE_SETTLE_MS
import com.aiphone.assistant.agent.AgentLimits.STOP_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.STUCK_ADVICE_LIMIT
import com.aiphone.assistant.agent.AgentLimits.STUCK_LIMIT
import com.aiphone.assistant.agent.AgentLimits.TAP_HOME
import com.aiphone.assistant.agent.AgentLimits.UNREADABLE_TREE_LIMIT
import com.aiphone.assistant.agent.AgentLimits.buildTreeLogLine
import com.aiphone.assistant.agent.AgentLimits.hasSideEffect
import com.aiphone.assistant.agent.AgentLimits.switchMustYieldToOpenApp
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmConfig
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.RunLogger
import com.aiphone.assistant.overlay.AgentPhase
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.skill.SkillRegistry
import com.aiphone.assistant.shell.ShizukuBridge
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * AI 决策循环。
 *
 * ```
 *   控件树 ──→ 模型 ──→ 一批动作 ──→ 逐个执行（中间补默认间隔）──┐
 *     ↑                                                        │
 *     └────────────────────────────────────────────────────────┘
 *              ↑ 只有模型主动要求时才加一张截图
 * ```
 *
 * ## 与上一版的三处不同
 *
 * **1. 默认不再每步发截图。**
 * 一张全分辨率截图 base64 之后是大头，是整条链路里最贵的东西。
 * 控件树已经能给出精确的元素编号，截图只在"这一屏说不清是什么"时
 * 才有价值 —— 所以改成由模型自己要：`need_image`（立即要，重问同一步）
 * 或把 `capture` 放进动作序列（图随下一步发，不多一次往返）。
 *
 * **2. 一轮执行一批动作。**
 * 模型一次给出若干个动作，中间自动等界面稳定（页面指纹判据）；
 * 模型显式写了 sleep 就完全不补，用它的值。往返次数因此大幅下降。
 *
 * **3. 模型可以主动调技能。**
 * 控件树只能看到屏幕上有什么，看不到"手机里装了什么应用"这类信息。
 * 模型用 `use_skill` 要，系统取回来塞进上下文，这一轮不算一步 ——
 * 和 `need_image` 是同一种机制。第一个技能是 list_apps（应用列表 + 包名）。
 *
 * **4. 防死循环改用控件树指纹。**
 * 原来比的是截图 MD5，现在默认没有截图可比了 —— 控件树文本没变
 * 同样说明"上一个动作没生效"，而且比截图更准（没有动画、时钟干扰）。
 *
 * ## 协作对象（2026-10-27 拆解 Agent 上帝类）
 *
 * 这个类曾经是两千九百行的 `Agent`，一个类同时承担主决策循环、问模型、
 * 执行动作、通道迁移、自动切副屏推断、端侧清弹窗。现在职责边界是：
 *
 *   - **本类**：任务级状态与主循环编排（准备 → 观察 → 问模型 → 执行 → 收尾）；
 *   - [AgentStepObserver]：读控件树、算指纹、生成通道/可读性/卡死提示；
 *   - [AgentModelExchange]：问模型、截图/技能请求、重试与急停；
 *   - [AgentActionExecutor]：一批动作的执行、等待稳定、动作后验证；
 *   - [AgentRuntime]：暂停/继续、让开前台、等待、弹窗、收尾与指标；
 *   - [AgentLimits]：常量与纯函数（副作用判断等）；
 *   - [ChannelMigration]：主屏↔副屏迁移（建/销毁副屏、整栈搬运、用户接管检测）；
 *   - [AutoSwitchWatcher]：「回桌面 → 自动切副屏」的推断状态机；
 *   - [ActivityProbe]：dumpsys 活动任务栈查询（解析是纯函数，单测钉住）；
 *   - [LocalRuleEngine]：端侧弹窗规则（本来就是独立的）。
 *
 * 循环里那些"界面变了没有"的判据收在 [LoopCriteria] 里 ——
 * 换通道时要清零，而迁移发生在循环之外，做成局部变量就够不到了。
 *
 * ## 关于"自己拍自己"
 *
 * 无障碍读的是当前活动窗口，纸盒自己在前台时模型读到的是纸盒界面、会对着
 * 它操作。但又不能任务一启动就盲目回桌面（第一批动作若是 open_app，多按一次
 * HOME 只是白弹一下桌面）。所以改成 lazy：模型要截图、或执行非 open_app
 * 动作前才按需让开；open_app 执行后若没切走再补一次。控件树解析还会按包名
 * 过滤掉我们自己的节点，双保险。
 */
internal class AgentSession(
    internal val controller: ChannelController,
    internal val llm: LlmClient,
    internal val settings: AppSettings,
    internal val maxSteps: Int,
    /** 本应用的包名，用来识别"自己在前台" */
    internal val selfPackage: String,
    /** 技能注册表 —— 模型用 use_skill 主动要"屏幕上没有的信息" */
    internal val skills: SkillRegistry,
    /**
     * **技能目录快照**（进系统提示词的**那一份**）。
     *
     * 必须用快照而不是 [skills] 的实时目录：系统提示词是前缀的第 0 个 token，
     * 用户学会/删掉一个录制技能都会让实时目录变化 —— 那会让整段上下文的
     * 缓存静默失效（只烧钱、不出错，日志里只能看到命中率掉下来）。
     * 快照由调用方在**新开上下文时**固定，之后整段沿用；
     * 技能**执行**仍然走实时注册表（新技能立刻能用，不影响前缀）。
     *
     * null = 调用方没给，退化成实时目录（仅用于测试和极端兜底）。
     */
    internal val skillCatalogSnapshot: String? = null,
    /**
     * 用户的记忆。**只在上下文是新开的时候非空**。
     *
     * 由调用方决定传不传：往一段正在进行的对话里插记忆会把缓存前缀
     * 打断，代价比省下的 token 大得多（见 ContextPolicy）。
     */
    internal val memorySnapshot: String? = null,
    /**
     * 上一段上下文里模型自己的历史。**空表示这是一段全新的上下文**。
     *
     * 有它模型才真的"记得上一轮说了什么"。因为每次都把同一份前缀原样
     * 重发，重复的部分会命中服务端的硬盘缓存 —— 官方定价里命中的输入
     * 只有未命中的 1/50，所以带上历史不但不贵，反而是最省的走法。
     */
    internal val seedHistory: List<ChatTurn> = emptyList(),
    internal val logger: RunLogger?,
    internal val listener: Listener,
) {



    /** 用户中途叫停 */
    @Volatile
    var stopRequested: Boolean = false

    /**
     * 已经跟用户说过"暂停了"没有。只影响提示重复，不影响状态 ——
     * 每轮都刷一条"已暂停"会把日志和对话刷满。
     */
    internal var pauseAnnounced = false

    /**
     * 当前在第几步 —— 只给"等待期间的暂停"用。
     *
     * [awaitStable] / [awaitWithStop] 在轮询里也要能停住（不然一个 30 秒的
     * 长等待按了暂停还得等完），而它们拿不到循环里的 `step` 变量。
     */
    internal var currentStep = 0

    // ---- 任务级可变状态（拆出主循环后由各阶段共享） ----

    /** 连续几步"根节点都读不到"（无障碍没真正生效）。 */
    internal var unreadableTreeSteps = 0

    /** 模型连续几次没给出可用动作。 */
    internal var parseFails = 0

    /** 拒绝"没看一眼就说完成"只做一次，避免模型死磕时来回拉锯。 */
    internal var refusedBlindFinish = false

    /** 收尾闸拦过几次（跨步累计）。 */
    internal var finishRefusals = 0

    /** 这次任务开过几次内置浏览器。 */
    internal var totalBrowserOpens = 0

    /** 端侧"可能卡住了"提醒过几次（跨步累计，有进展就清零）。 */
    internal var stuckAdviceCount = 0

    /** 上一轮的输入 token 数（缓存自检用）。 */
    internal var lastPromptTokens = 0

    /** 上一批动作的执行结果，会拼进下一条 user 消息。 */
    internal var lastResult: String? = null

    /** 这一步存下来的截图文件（need_image 或 capture 都会存）。 */
    internal var stepShot: java.io.File? = null

    /** capture 动作截到的图，跨步携带，随下一步的 user 消息一起发。 */
    internal var pendingActionShot: ByteArray? = null

    /** 任务级图片计数：need_image 和 capture 共同消耗。 */
    internal var totalImages = 0

    /**
     * 这次任务结束时，模型那一侧攒下的完整历史。
     *
     * 调用方在 [run] 返回后读它、存进 [com.aiphone.assistant.data.ContextStore]，
     * 下一次任务再原样喂回来 —— 这样"上下文"才真的是同一段上下文。
     *
     * 名字不叫 history，是因为 [run] 里有一个同名的局部变量（那才是
     * 真正发给模型的那一份），这里只是它结束时的快照。
     */
    internal val allHistory = mutableListOf<ChatTurn>()

    /**
     * 只读快照。带图的消息**原样带走**。
     *
     * 图片由 ContextStore 落盘、下一次任务再读回来，所以历史里每一条都还能
     * 逐字复原 —— 前缀才是真正只增不改的。原来在这里把图剥掉，等于让下一次
     * 任务的前缀从第一条带图的消息就断掉（实测 24 条只复用 5 条）。
     */
    val finalHistory: List<ChatTurn>
        get() = synchronized(allHistory) { allHistory.toList() }

    /** 累计 token，用来算这次花了多少 */
    internal var promptTokens = 0
    internal var completionTokens = 0

    /**
     * 上下文缓存的命中情况。
     *
     * 本轮对话是**严格追加**的，所以从第 2 步开始，
     * 系统提示词和前面所有轮次都应该命中缓存。
     * 如果稳定是 0，说明前缀被打断了 —— 先查是不是有人往 history 里
     * 塞了和实际发送内容不一致的东西。
     */
    internal var cacheHitTokens = 0
    internal var cacheMissTokens = 0

    /**
     * 这趟用的系统提示词（复评时复用）。
     *
     * 复用它是为了**命中缓存**：同一段系统提示词 + 同一段历史 + 最后追加一条
     * "请复盘"，前面全部命中；换一套提示词的话，几万 token 全按未命中计价。
     */
    internal var systemPrompt: String = ""

    /**
     * 点击前复核专用的模型客户端。
     *
     * **必须和主客户端分开**：主客户端内部记着"上一次请求的消息指纹"，
     * 用来算前缀复用率。复核是一次独立的短请求（只有一条 user 消息），
     * 如果共用同一个实例，下一次主请求会把复核请求当成"上一次" ——
     * 前缀复用率永远显示 0，缓存断了的告警也会乱响。
     *
     * 它只发一张局部图和一小段提示词，不碰主对话历史；超时/重试也收紧
     * （复核失败就按原点执行，不能因为它把整个任务拖住）。
     */
    internal val reviewLlm: LlmClient by lazy {
        LlmClient(
            LlmConfig(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.modelName,
                useFreeModel = settings.useFreeModel,
                thinking = settings.thinking,
                customEffort = settings.customEffort,
                timeoutMs = 60_000,
                maxAttempts = 1,
            )
        )
    }

    /**
     * 当前模型不接受图片时，本次任务后续不再复核。
     *
     * 免费池里有些模型只收文本；每个点击都撞一次 400 既慢又刷日志。
     * 第一次识别到"不接受图片"就关掉，任务照常跑。
     */
    internal var tapReviewVisionUnsupported = false

    /** 这次任务是否已经把纸盒让到后台（lazy，全程只让一次） */
    internal var foregroundYielded = false

    /**
     * 这次任务里端侧**主动**关掉弹窗的次数。
     *
     * 只在 [dismissBlockingDialogLocally] 里自增，用来止损：正常任务遇不到
     * 几个弹窗；真碰上"关掉又弹出来"的循环时，靠 [LOCAL_DISMISS_LIMIT] 收手，
     * 把判断交回模型（那种情况多半该换个做法，而不是继续关）。
     */
    internal var localDismissCount = 0

    /**
     * 「界面变了没有 / 这批动作重复了几次」的整套判据。
     *
     * 为什么是对象而不是 [run] 的局部变量：通道迁移（[ChannelMigration]）
     * 发生在循环之外，换屏后**必须**把它们清零（见 [onChannelSwitched]）。
     * 做成局部变量时外面够不到，只能靠一个"信标字段"让循环自己消费 ——
     * 收进对象之后，清理只有一处实现，也不会再有人忘了消费。
     */
    internal class LoopCriteria {
        var lastTreeHash = 0
        var sameTree = 0
        var lastSig = ""
        var repeatAction = 0

        /**
         * 全部清零。两个调用点，语义都是"旧判据作废"：
         *   - 新任务开头（上一任务的界面/动作和这一任务无关，不清零会误报
         *     "界面没变化 / 又给了一遍同样的动作"）；
         *   - 换通道后（换屏后第一次比对必然"不同"，留着会误报）。
         */
        fun reset() {
            lastTreeHash = 0
            sameTree = 0
            lastSig = ""
            repeatAction = 0
        }
    }

    internal val criteria = LoopCriteria()

    /**
     * 刚换过通道（换到哪），等下一轮开头给模型留一行说明。
     *
     * 编号体系是**逐屏**的：换了屏，上一步的 [3][7] 全作废。
     * 不说清楚的话，模型会拿旧编号去点新屏，看起来像"点错了"。
     */
    internal var channelSwitchedTo: String? = null

    /**
     * 每次通道迁移**成功后必须走到这里**，否则会误报"界面没变化、你的动作没生效"。
     *
     * 原因：换屏后第一次读到的控件树必然和上一屏不同，但指纹比对只认
     * "和上一次相同"，不认"换屏了"。更坑的是切回来的时候 —— 如果两边
     * 刚好是同一个界面（比如都是桌面），指纹一致，就会判定 AI 的动作没用，
     * 然后注入一段"换一种方式"的提示，把模型带偏。
     *
     * 所以换通道时把整套判据清零：[foregroundYielded]（换屏后要重新考虑
     * 让不让位）、指纹与重复动作计数（[criteria]），并给模型留一行说明
     * （[channelSwitchedTo]，由循环开头消费）。悬浮按钮由 [ChannelMigration]
     * 自己收尾 —— 换通道和按钮状态是同一件事的两个面。
     */
    internal fun onChannelSwitched(to: String) {
        foregroundYielded = false
        criteria.reset()
        channelSwitchedTo = to
    }

    // ---- 通道迁移与自动切副屏的协作者（0.9.11 从本类拆出，见评审报告第 3 节）----

    /** 活动任务栈探测：dumpsys 的解析是纯函数，单测钉住（见 ActivityProbeTest） */
    internal val probe = ActivityProbe(controller.appContext)

    /** 「回桌面自动切副屏」的推断状态机（原 0.8.3 那组散装状态字段） */
    internal val watcher = AutoSwitchWatcher(selfPackage, probe, logger)

    /** 主屏↔副屏迁移：建/销毁副屏、整栈搬运、接管检测 */
    internal val migration = ChannelMigration(
        controller = controller,
        probe = probe,
        watcher = watcher,
        selfPackage = selfPackage,
        logger = logger,
        onEvent = { kind, text, label -> listener.onEvent(kind, text, label) },
        switched = { to -> onChannelSwitched(to) },
        pressHome = { pressHomeToYield() },
    )

    /**
     * 端侧意图执行器（无状态）。模型显式下发 dismiss_dialog 时，
     * 用它在当前页面找安全关闭按钮；端侧不做自主前置决策。
     */
    internal val localRuleEngine = LocalRuleEngine()

    suspend fun run(task: String) {
        // 新任务开头无条件清掉上一轮可能残留的通道切换请求，兜住所有入口
        OverlayBus.clearMoveToVirtualDisplay()
        OverlayBus.clearReturnFromVd()
        // 上一任务结尾若停在"刚换过通道"上，那条说明属于上一任务，别带进新任务
        channelSwitchedTo = null
        // 0.8.3 自动切副屏的推断状态也随新任务重置
        watcher.reset()
        // 「界面变了没有 / 动作重复了几次」的判据同理 —— 上一任务的界面和
        // 动作与这一任务无关，不清零会把新任务的第一步误判成"没变化"
        criteria.reset()
        // 这些计数在原实现里是 run() 的局部变量，天然每趟重置；
        // 拆成 session 字段后必须在这里显式清零，否则同一个 Agent 跑第二趟
        // 会带着上一趟的步数、图片额度和解析失败次数。
        unreadableTreeSteps = 0
        parseFails = 0
        refusedBlindFinish = false
        finishRefusals = 0
        totalBrowserOpens = 0
        stuckAdviceCount = 0
        lastPromptTokens = 0
        lastResult = null
        stepShot = null
        pendingActionShot = null
        totalImages = 0

        // ---- 1. 通道就绪？ ----
        val problem = controller.probe()
        if (problem != null) {
            fail(problem, "通道不可用")
            return
        }
        logger?.line(
            if (controller.isVirtualDisplay) {
                "通道就绪：副屏（display=${controller.virtualDisplayId}，只有截图 + 坐标，没有控件树）"
            } else {
                "通道就绪：${settings.mode.label}"
            },
            "通道",
        )

        // ---- 1.5 无障碍**真的**读到东西了吗 ----
        // `probe()` 只检查"服务对象在不在"。MIUI / HyperOS 上服务被系统停用
        // 之后，对象可能还在、`rootInActiveWindow` 却一直返回 null ——
        // 0.8.5 那份包里就是这样：日志第一行写着「通道就绪：无障碍」，
        // 而整个任务的控件树都是空的、纸盒界面上还挂着"无障碍未开启"。
        // 这里先读一次，把真实情况写进日志开头 —— 复盘时第一眼就能看到，
        // 不用等到第 6 步的止损消息。
        if (!controller.isVirtualDisplay) {
            val preflight = withContext(Dispatchers.IO) { controller.readUiTree() }
            logger?.line(
                "通道自检：" + when {
                    !preflight.rootAvailable ->
                        "❌ 读不到控件树 —— 无障碍服务没有真正生效。" +
                            "请到「权限」页里把纸盒的无障碍关掉再打开。"
                    preflight.nodes.isEmpty() ->
                        "⚠️ 读到根节点，但当前这一屏没有可交互元素" +
                            "（前台可能是纸盒自己，或界面是自绘的）"
                    else -> "✅ 读到 ${preflight.nodes.size} 个元素"
                },
                "通道",
            )
        }

        val screen = controller.screenSize()
        if (screen == null || screen.first <= 0 || screen.second <= 0) {
            fail("拿不到屏幕分辨率，无法把模型给的坐标换算成真实位置。", "通道")
            return
        }
        logger?.line("屏幕：${screen.first} x ${screen.second}", "通道")
        logger?.line(llm.describe(), "模型")
        logger?.line("默认不发截图，模型用 need_image 主动要", "模型")
        logger?.line(if (OverlayBus.isShowing) "悬浮窗已就绪" else "悬浮窗未启动（缺权限或未开）", "悬浮")
        logger?.line("技能：${skills.ids().size} 个（${skills.ids().joinToString("、")}）", "技能")

        // 不在任务开头无条件让开纸盒（那样会先弹一下桌面）；改成 lazy：
        // 第一次截图 / 执行非 open_app 动作前才按需让开，见 yieldForegroundIfNeeded。

        // ---- 开跑 ----
        // 系统提示词**必须逐字稳定**：它是前缀的第 0 个 token，一变整段
        // 上下文的缓存全废。所以记忆用的是调用方固定下来的那一份快照
        // （见 CarriedContext.memorySnapshot），这里不重新去读记忆文件。
        val system = AgentPrompt.system(
            skillCatalogSnapshot ?: skills.catalog(),
            memorySnapshot,
            modelName = settings.modelName,
        )
        systemPrompt = system
        val memoryChars = memorySnapshot?.length ?: 0
        logger?.line(
            when {
                seedHistory.isEmpty() && memoryChars == 0 ->
                    "新开一段上下文，没有记忆可注入"
                seedHistory.isEmpty() ->
                    "新开一段上下文，已注入记忆 $memoryChars 字符"
                memoryChars == 0 ->
                    "接着上一段上下文（${seedHistory.size} 条历史），这段里没有记忆"
                else ->
                    "接着上一段上下文（${seedHistory.size} 条历史），" +
                        "沿用开头注入的同一份记忆 $memoryChars 字符"
            },
            "上下文",
        )
        // 模型那一侧的历史。这里**就是** [allHistory] 本身（同一个列表），
        // 不另建一份 —— 任务中途从任何一条路径返回，攒下的内容都不会丢。
        val history = allHistory
        history.clear()
        // 接着上一段上下文：把发过的历史原样放回去。
        // **必须逐字原样** —— 改动历史消息会使包含它的缓存前缀单元无法完整匹配，
        // 后续历史可能整段按未命中计价（这正是之前命中率低的原因）
        if (seedHistory.isNotEmpty()) {
            history.addAll(seedHistory)
            logger?.line("接着上一段上下文：${seedHistory.size} 条历史已复原", "上下文")
        }
        // 0 = 不限。不设上限不等于失控：模型会主动 finished / failed 收尾，
        // 而且上下文接近窗口上限时下面会强制停下（见 CONTEXT_STOP_TOKENS）
        val stepLimit = if (maxSteps <= 0) Int.MAX_VALUE else maxSteps
        // 分辨率跨步保留：某一步没读到时沿用上一次的值（和拆分前一致）
        var screenW = screen.first
        var screenH = screen.second

        for (step in 1..stepLimit) {
            currentStep = step
            // 暂停点①：步开头。返回 false = 用户在暂停期间按了停止
            if (!awaitResume(step)) {
                finish(false, "你停止了任务（第 $step 步）")
                return
            }

            listener.onProgress(step, maxSteps)
            logger?.section("第 $step / $maxSteps 步")

            // ---- 看一眼（读树 / 通道提示 / 卡死判据都在 observeStep 里）----
            val observation = when (val observed = observeStep(step, screenW, screenH)) {
                ObserveResult.Skip -> continue
                ObserveResult.Stop -> return
                is ObserveResult.Ready -> observed.observation
            }
            screenW = observation.w
            screenH = observation.h
            val w = observation.w
            val h = observation.h
            val foreground = observation.foreground
            val tree = observation.tree
            val treeRead = observation.treeRead
            val treeObservable = observation.treeObservable
            val interruptions = observation.interruptions

            val ready = when (
                val exchange = askModel(
                    step = step,
                    task = task,
                    w = w,
                    h = h,
                    tree = tree,
                    treeRead = treeRead,
                    foreground = foreground,
                    history = history,
                    system = system,
                    treeObservable = treeObservable,
                    initialInterruptions = interruptions,
                )
            ) {
                is AskResult.Ready -> exchange
                AskResult.Stop -> return
            }
            val p = ready.parsed
            val modelOutput = ready.modelOutput

            // ---- 一批动作都没有 ----
            if (p.actions.isEmpty()) {
                val warning = p.warning ?: "没能解析出动作。"
                parseFails++
                logger?.warn("解析失败（第 $parseFails 次）：$warning", "解析")
                listener.onEvent(EventKind.ERROR, warning, "解析失败")
                if (parseFails >= MAX_PARSE_FAILS) {
                    finish(
                        false,
                        "模型连续 $parseFails 次没给出能执行的动作，已经停下。" +
                            "换个模型或者把任务说具体一点再试。",
                    )
                    return
                }
                // 失败原因回灌给模型，让它重出 —— 但**不单独发一条消息**，
                // 而是记进 lastResult，拼到下一条 user 消息里。
                history.add(ChatTurn(ChatTurn.ASSISTANT, modelOutput))
                lastResult = "上一个输出有问题：$warning" +
                    "请重新输出一个 JSON 对象，actions 里放上要执行的动作。"
                continue
            }

            when (
                executeBatch(
                    step = step,
                    task = task,
                    w = w,
                    h = h,
                    tree = tree,
                    treeRead = treeRead,
                    p = p,
                    modelOutput = modelOutput,
                )
            ) {
                BatchResult.Next -> Unit
                BatchResult.Stop -> return
            }
        }

        finish(
            false,
            "到了最大步数 $maxSteps 还没做完。可以加大步数，或者把任务拆小一点。",
        )
    }

    /**
     * 弹出副屏镜像页（用户想看 AI 在副屏上做什么时点悬浮窗上的入口）。
     * 实现在 [ChannelMigration] —— 迁移和镜像页是同一件事的两个面。
     */
    fun openMirrorPage() {
        migration.openMirrorPage()
    }

    /**
     * 复评：拿这趟的完整历史再问一次"哪一步错了"，**不重放动作**。
     *
     * 和 [run] 的区别只有一点：结果只交给人看，不解析成动作 ——
     * 所以复评不会碰手机。系统提示词和历史都原样复用（见 [systemPrompt]），
     * 这一段几乎全是缓存命中，成本比新开一趟任务低一个数量级。
     *
     * @param instruction 用户想让它复盘什么；空的话用默认那三问
     */
    suspend fun review(instruction: String = ""): LlmResult {
        if (allHistory.isEmpty()) {
            return LlmResult.Fail("还没有可复盘的任务：先跑一趟再来。")
        }
        val ask = if (instruction.isBlank()) {
            AgentPrompt.REVIEW_INSTRUCTION
        } else {
            AgentPrompt.REVIEW_INSTRUCTION + "\n\n（这次特别看一下：$instruction）"
        }
        return withContext(Dispatchers.IO) {
            llm.chat(
                system = systemPrompt.ifBlank {
                    AgentPrompt.system(modelName = settings.modelName)
                },
                history = allHistory + ChatTurn(ChatTurn.USER, ask),
                shouldAbort = { isStopped() },
            )
        }
    }

    /**
     * 点击前复核：截当前屏 → 裁落点附近 → 画十字 → 问模型"这一下点对了吗"。
     *
     * 返回 null = 复核没做成（截图失败/模型不接受图片/请求失败），调用方
     * **按原点执行**。复核是增益功能，不是安全闸，不能因为它失败把任务卡死；
     * 真正的安全边界（动作白名单、坐标夹紧、有副作用不自动重试）仍在主循环。
     *
     * 这里刻意用独立的 [reviewLlm] 和**独立的一问一答**：不碰主对话历史，
     * 也不影响主客户端的缓存前缀统计。
     */
    internal suspend fun reviewPointAction(
        action: TouchAction,
        target: UiNode?,
        step: Int,
        task: String,
        screenWidth: Int,
        screenHeight: Int,
    ): TapReview.Decision? {
        // 按编号点击时，真正的落点是控件中心；模型给的 x/y 可能是 0
        val px = target?.centerX ?: action.x
        val py = target?.centerY ?: action.y
        if (px <= 0 || py <= 0) {
            logger?.warn(
                "点击前复核：找不到落点（编号 ${action.targetIndex}，坐标 ${action.x},${action.y}），跳过",
                "复核",
            )
            return null
        }

        OverlayBus.setPhase(AgentPhase.REVIEWING)
        // 复核必须拍到目标应用，不能拍到纸盒自己；lazy 让开是幂等的
        yieldForegroundIfNeeded()
        OverlayBus.hide()
        delay(OVERLAY_SETTLE_MS)
        val shot = try {
            withContext(Dispatchers.IO) { controller.captureFrame() }
        } finally {
            OverlayBus.show()
        }
        if (shot == null || shot.isEmpty()) {
            val why = controller.lastScreenshotError() ?: "未知原因"
            logger?.warn("点击前复核：截图失败（${why}），按原点执行", "复核")
            return null
        }

        val crop = TapReview.cropFor(px, py, screenWidth, screenHeight)
        val marked = withContext(Dispatchers.IO) {
            TapReviewRenderer.cropAndMark(shot, crop, px, py)
        }
        if (marked == null || marked.isEmpty()) {
            logger?.warn("点击前复核：局部图生成失败，按原点执行", "复核")
            return null
        }

        val targetDesc = if (target != null) {
            "[${target.index}] ${target.className} \"${target.text.ifBlank { target.contentDesc }.take(30)}\" " +
                "@(${target.centerX},${target.centerY})"
        } else {
            "模型给的是坐标，没有按元素编号定位"
        }
        val request = TapReview.prompt(
            task = task,
            step = step,
            actionDesc = AgentPrompt.describe(action),
            targetDesc = targetDesc,
            pointX = px,
            pointY = py,
            crop = crop,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        logger?.line(
            "点击前复核：${AgentPrompt.describe(action)}，落点 (${px},${py})，" +
                "裁剪 ${crop.size}px @(${crop.left},${crop.top})，${marked.size} 字节",
            "复核",
        )

        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            // 复核也是一次网络请求，急停同样要能掀掉它
            val watchdog = launch {
                while (isActive) {
                    delay(STOP_POLL_MS)
                    if (isStopped()) {
                        reviewLlm.abort()
                        return@launch
                    }
                }
            }
            try {
                reviewLlm.chat(
                    system = TapReview.SYSTEM,
                    history = listOf(ChatTurn(ChatTurn.USER, request, marked)),
                    shouldAbort = { isStopped() },
                )
            } finally {
                watchdog.cancel()
            }
        }
        val elapsed = System.currentTimeMillis() - started
        return when (result) {
            is LlmResult.Fail -> {
                val msg = result.message
                if (msg.contains("不接受图片") || msg.contains("image", ignoreCase = true)) {
                    tapReviewVisionUnsupported = true
                    logger?.warn(
                        "点击前复核：当前模型不接受图片，本次任务后续不再复核（${msg}）",
                        "复核",
                    )
                } else {
                    logger?.warn("点击前复核请求失败（${msg}），按原点执行", "复核")
                }
                null
            }

            is LlmResult.Ok -> {
                // 复核的 token 也要算进这一趟的总量（它是真花出去的）
                promptTokens += result.promptTokens
                completionTokens += result.completionTokens
                val decision = TapReview.parse(result.text, crop, screenWidth, screenHeight)
                val verdict = when {
                    !decision.parsed -> "输出无法解析，按原点执行"
                    decision.abort -> "不通过（叫停）"
                    decision.correctedX != null -> "不通过（改点 ${decision.correctedX},${decision.correctedY}）"
                    else -> "通过"
                }
                logger?.line(
                    "点击前复核返回 ${elapsed}ms：${verdict}" +
                        if (decision.why.isBlank()) "" else " —— ${decision.why}",
                    "复核",
                )
                decision
            }
        }
    }
}
