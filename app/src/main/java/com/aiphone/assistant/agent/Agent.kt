package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
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
 * ## 关于"自己拍自己"
 *
 * 无障碍读的是当前活动窗口，纸盒自己在前台时模型读到的是纸盒界面、会对着
 * 它操作。但又不能任务一启动就盲目回桌面（第一批动作若是 open_app，多按一次
 * HOME 只是白弹一下桌面）。所以改成 lazy：模型要截图、或执行非 open_app
 * 动作前才按需让开；open_app 执行后若没切走再补一次。控件树解析还会按包名
 * 过滤掉我们自己的节点，双保险。
 */
class Agent(
    private val controller: ChannelController,
    private val llm: LlmClient,
    private val settings: AppSettings,
    private val maxSteps: Int,
    /** 本应用的包名，用来识别"自己在前台" */
    private val selfPackage: String,
    /** 技能注册表 —— 模型用 use_skill 主动要"屏幕上没有的信息" */
    private val skills: SkillRegistry,
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
    private val skillCatalogSnapshot: String? = null,
    /**
     * 用户的记忆。**只在上下文是新开的时候非空**。
     *
     * 由调用方决定传不传：往一段正在进行的对话里插记忆会把缓存前缀
     * 打断，代价比省下的 token 大得多（见 ContextPolicy）。
     */
    private val memorySnapshot: String? = null,
    /**
     * 上一段上下文里模型自己的历史。**空表示这是一段全新的上下文**。
     *
     * 有它模型才真的"记得上一轮说了什么"。因为每次都把同一份前缀原样
     * 重发，重复的部分会命中服务端的硬盘缓存 —— 官方定价里命中的输入
     * 只有未命中的 1/50，所以带上历史不但不贵，反而是最省的走法。
     */
    private val seedHistory: List<ChatTurn> = emptyList(),
    private val logger: RunLogger?,
    private val listener: Listener,
) {

    interface Listener {
        /** 一条要显示给用户的日志 */
        fun onEvent(kind: EventKind, text: String, label: String?)

        /** 第几步 / 共几步，用来更新界面 */
        fun onProgress(step: Int, maxSteps: Int)

        /** 结束 */
        fun onFinished(success: Boolean, message: String)

        /** 用户按了停止吗（每步开始时检查） */
        fun isStopRequested(): Boolean
    }

    enum class EventKind { THOUGHT, ACTION, RESULT, ERROR }

    /** 用户中途叫停 */
    @Volatile
    var stopRequested: Boolean = false

    /**
     * 这次任务结束时，模型那一侧攒下的完整历史。
     *
     * 调用方在 [run] 返回后读它、存进 [com.aiphone.assistant.data.ContextStore]，
     * 下一次任务再原样喂回来 —— 这样"上下文"才真的是同一段上下文。
     *
     * 名字不叫 history，是因为 [run] 里有一个同名的局部变量（那才是
     * 真正发给模型的那一份），这里只是它结束时的快照。
     */
    private val allHistory = mutableListOf<ChatTurn>()

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
    private var promptTokens = 0
    private var completionTokens = 0

    /**
     * 上下文缓存的命中情况。
     *
     * 本轮对话是**严格追加**的，所以从第 2 步开始，
     * 系统提示词和前面所有轮次都应该命中缓存。
     * 如果稳定是 0，说明前缀被打断了 —— 先查是不是有人往 history 里
     * 塞了和实际发送内容不一致的东西。
     */
    private var cacheHitTokens = 0
    private var cacheMissTokens = 0

    /** 这次任务是否已经把纸盒让到后台（lazy，全程只让一次） */
    private var foregroundYielded = false

    /**
     * 这次任务里端侧**主动**关掉弹窗的次数。
     *
     * 只在 [dismissBlockingDialogLocally] 里自增，用来止损：正常任务遇不到
     * 几个弹窗；真碰上"关掉又弹出来"的循环时，靠 [LOCAL_DISMISS_LIMIT] 收手，
     * 把判断交回模型（那种情况多半该换个做法，而不是继续关）。
     */
    private var localDismissCount = 0

    /**
     * 「用户把应用挪回主屏自己用了」的连续命中次数（副屏模式）。
     *
     * 要连续 [TAKEOVER_STRIKES] 次都成立才认定 —— 切通道那一瞬间
     * `dumpsys` 会出现"两边都没有"的中间态，只看一次会误判成用户接管，
     * 而误判的后果是平白把还在正常跑的任务掐掉。
     */
    private var takeoverStrikes = 0

    /**
     * 刚换过通道，等下一轮开头把「界面变了没有」那套判据清零。
     *
     * 为什么不做成直接调一个函数去清：`lastTreeHash` / `sameTree` / `lastSig` /
     * `repeatAction` 都是 [run] 里的**局部变量**，外面够不到。所以这里留个信标，
     * 由循环自己在开头消费 —— 那里正好是它们都在作用域内的位置。
     */
    private var channelSwitchedTo: String? = null

    /**
     * 每次通道迁移**成功后必须调用**，否则会误报"界面没变化、你的动作没生效"。
     *
     * 原因：换屏后第一次读到的控件树必然和上一屏不同，但指纹比对只认
     * "和上一次相同"，不认"换屏了"。更坑的是切回来的时候 —— 如果两边
     * 刚好是同一个界面（比如都是桌面），指纹一致，就会判定 AI 的动作没用，
     * 然后注入一段"换一种方式"的提示，把模型带偏。
     *
     * 所以换通道时把整套判据清零：[foregroundYielded]（换屏后要重新考虑
     * 让不让位）、指纹与重复动作计数（下一轮开头消费）、并在对话里留一行
     * 说明 —— 编号体系是**逐屏**的，换了屏旧编号就全作废了。
     */
    private suspend fun onChannelSwitched(to: String) {
        foregroundYielded = false
        channelSwitchedTo = to
        refreshMoveButton()
    }

    /**
     * 本次任务里 Agent 是否已经成功建过副屏（建屏成功即置位），销毁后清位。
     *
     * 兜底失败路径：建屏成功、但整栈迁移和副屏重开都失败、直接返回时，
     * 通道还没切（isVirtualDisplay 仍 false），finish() 也不能漏销毁副屏。
     */
    private var vdCreatedByAgent = false

    // ---- 0.8.3 回桌面自动切副屏 ----
    /** AI 最近在操作的目标 app 包名（过滤纸盒/桌面） */
    private var lastTargetPkg: String? = null
    /** 目标 app 的 taskId（包名变化时 dumpsys 一次缓存） */
    private var lastTargetTaskId: Int = -1
    /** 目标 app 最近在前台的时间，用于"够不够新"判定 */
    private var lastTargetAtMs: Long = 0L
    /** Agent 自己按 HOME 的时间，之后短时间内不自动触发 */
    private var selfHomeAtMs: Long = 0L
    /** 自动迁移失败后的冷却截止时间 */
    private var autoMoveCooldownUntilMs: Long = 0L
    /** 自动切副屏使能（主屏 + Shizuku READY），每步刷新一次、interruption 只读它 */
    @Volatile
    private var autoSwitchArmed = false

    /**
     * 端侧意图执行器（无状态）。模型显式下发 dismiss_dialog 时，
     * 用它在当前页面找安全关闭按钮；端侧不做自主前置决策。
     */
    private val localRuleEngine = LocalRuleEngine()

    suspend fun run(task: String) {
        // 新任务开头无条件清掉上一轮可能残留的通道切换请求，兜住所有入口
        OverlayBus.clearMoveToVirtualDisplay()
        OverlayBus.clearReturnFromVd()
        // 0.8.3 自动切副屏的推断状态也随新任务重置
        lastTargetPkg = null
        lastTargetTaskId = -1
        lastTargetAtMs = 0L
        selfHomeAtMs = 0L
        autoMoveCooldownUntilMs = 0L

        // ---- 1. 通道就绪？ ----
        val problem = controller.probe()
        if (problem != null) {
            fail(problem, "通道不可用")
            return
        }
        logger?.line("通道就绪：${settings.mode.label}", "通道")

        var w = 0
        var h = 0
        controller.screenSize()?.let { if (it.first > 0 && it.second > 0) { w = it.first; h = it.second } }
        if (w <= 0 || h <= 0) {
            fail("拿不到屏幕分辨率，无法把模型给的坐标换算成真实位置。", "通道")
            return
        }
        logger?.line("屏幕：${w} x ${h}", "通道")
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
        // **必须逐字原样** —— 差一个字，服务端的最长公共前缀就在那里断开，
        // 后面全部按未命中计价（这正是之前命中率低的原因）
        if (seedHistory.isNotEmpty()) {
            history.addAll(seedHistory)
            logger?.line("接着上一段上下文：${seedHistory.size} 条历史已复原", "上下文")
        }
        var lastTreeHash = 0
        var sameTree = 0
        var lastSig = ""
        var repeatAction = 0
        // 模型连续几次没给出可用动作。原本这种情况会一直 continue 到步数上限 ——
        // 步数改成"不限"之后，那等于死循环烧钱，所以单独计数
        var parseFails = 0

        // 上一批动作的执行结果。它会拼进**下一条** user 消息里，
        // 而不是单独发一条 —— 这样整条对话是严格追加，缓存才命中得了。
        var lastResult: String? = null

        // capture 动作截到的图：跨步携带，随**下一步**的 user 消息一起发。
        // 不在截到的当下发，是因为图和"界面元素"必须来自同一瞬间 ——
        // 早一步截的图配晚一步的树，模型会把坐标算到错的地方。
        var pendingActionShot: ByteArray? = null

        // 任务级图片计数：need_image 和 capture 共同消耗，
        // 超过 MAX_TOTAL_IMAGES 就不再给图（副屏自动图是刚需、不计入）。
        var totalImages = 0

        // 0 = 不限。不设上限不等于失控：模型会主动 finished / failed 收尾，
        // 而且上下文接近窗口上限时下面会强制停下（见 CONTEXT_STOP_TOKENS）
        val stepLimit = if (maxSteps <= 0) Int.MAX_VALUE else maxSteps

        for (step in 1..stepLimit) {
            if (isStopped()) {
                finish(false, "你停止了任务（第 $step 步）")
                return
            }

            listener.onProgress(step, maxSteps)
            logger?.section("第 $step / $maxSteps 步")

            // ---- 看一眼 ----
            // 分辨率每步重读：横竖屏切换、部分 ROM 的游戏模式都会改它
            controller.screenSize()?.let {
                if (it.first > 0 && it.second > 0) { w = it.first; h = it.second }
            }
            val foreground = withContext(Dispatchers.IO) { controller.currentPackage() }
            updateLastTarget(foreground)

            // 刷新「切到副屏」按钮：仅主屏模式 + Shizuku READY 时可点
            refreshMoveButton()

            // 用户按了「切到副屏」：在动作间隙完成迁移；成功后从下一步起读副屏
            if (OverlayBus.moveToVdRequested) {
                val moved = handleMoveToVirtualDisplay()
                OverlayBus.clearMoveToVirtualDisplay()
                if (moved) continue
            }

            // 用户按了「切回主屏」（副屏模式绿按钮）：动作间隙回迁；成功后从下一步起读主屏
            if (OverlayBus.returnFromVdRequested) {
                val back = handleReturnToMainScreen()
                OverlayBus.clearReturnFromVd()
                if (back) continue
            }

            // 刚换过通道：把「界面变了没有」那一整套判据清零（见 onChannelSwitched）。
            // 放在这里是因为 lastTreeHash / sameTree / lastSig / repeatAction
            // 都是**本轮 run() 的局部变量**，只有这个位置够得到它们。
            channelSwitchedTo?.let { to ->
                channelSwitchedTo = null
                lastTreeHash = 0
                sameTree = 0
                lastSig = ""
                repeatAction = 0
                // 编号是**逐屏**的：换了屏，上一步那些 [3][7] 全作废。
                // 不说清楚的话，模型会拿旧编号去点新屏，看起来像"点错了"。
                lastResult = "已切换到$to，之前的界面元素编号作废，请重新看本屏的列表"
                logger?.line(lastResult, "通道")
            }

            // 每步刷新一次自动切副屏使能（主屏 + Shizuku READY），interruption() 只读内存、零 binder
            autoSwitchArmed = !controller.isVirtualDisplay &&
                ShizukuBridge.state(controller.appContext) == ShizukuBridge.State.READY
            // 用户回桌面 + 400ms 防抖：自动把目标 app 迁到副屏（0.8.3）
            if (autoDesktopSwitchReady() && handleAutoMoveToVirtualDisplay()) continue

            // ---- 用户接管检测（只在副屏模式）----
            // 用户可能把应用从副屏**挪回主屏**自己去用。这时副屏是空的，
            // 再跑下去就是对着一块空屏烧 token、而且每一步都在"看不到东西"的
            // 前提下瞎猜。判据要两条同时成立：副屏上没有前台任务 + 目标应用
            // 出现在主屏。连查两次都成立才认定（切通道瞬间会有短暂的"两边都没有"）。
            if (controller.isVirtualDisplay) {
                val vdId = controller.virtualDisplayId
                val tPkg = lastTargetPkg
                if (vdId != null && !tPkg.isNullOrBlank() && currentTaskOnDisplay(vdId) == null) {
                    val onMain = currentTaskOnDisplay(0)
                    if (onMain?.second == tPkg) {
                        takeoverStrikes++
                        if (takeoverStrikes >= TAKEOVER_STRIKES) {
                            takeoverStrikes = 0
                            // 切回主屏**继续跑**，而不是停下。
                            //
                            // 依据是项目自己定的方案（else/工作汇报-0.8.5.md §附加触发源）：
                            // "若被用户从主屏或最近任务列表拉回主屏，则判定用户接管，
                            // 自动切回主屏模式继续"。外部项目 ShadowAuto 也把
                            // "用户把被自动化的应用拉回主屏"列为会打断自动化的已知情况。
                            //
                            // 这里不用 handleReturnToMainScreen()：那套是给"应用还在副屏"
                            // 写的，要搬栈；而此刻应用**已经在主屏**了，只需要换读取通道。
                            controller.exitVirtualDisplay()
                            onChannelSwitched("主屏")
                            // 压住自动切副屏，否则下一步"前台是目标应用 + 后来用户回桌面"
                            // 会立刻把它搬回副屏，用户一拉回来又被搬走，来回打乒乓
                            autoMoveCooldownUntilMs =
                                System.currentTimeMillis() + TAKEOVER_COOLDOWN_MS
                            val msg = "检测到你把「$tPkg」挪回主屏了，AI 已切回主屏模式继续" +
                                "（$TAKEOVER_COOLDOWN_MS / 1000 秒内不会再自动搬去副屏）。"
                            logger?.line(msg, "接管")
                            listener.onEvent(EventKind.THOUGHT, msg, "接管")
                            continue
                        }
                        logger?.warn("副屏上没有前台应用，且「$tPkg」在主屏 —— 再确认一次", "接管")
                    } else {
                        takeoverStrikes = 0
                    }
                } else {
                    takeoverStrikes = 0
                }
            }

            // ---- 端侧先清一遍挡路的无副作用弹窗 ----
            // 放在读控件树**之前**：这样模型拿到的是清理干净的页面，
            // 不会为一个广告弹窗白花一步，也不用为它多走一轮网络。
            // 安全闸在 LocalRuleEngine 里，端侧只挪开挡路的东西、不推进任务。
            dismissBlockingDialogLocally()?.let { note ->
                lastResult = note
                listener.onEvent(EventKind.THOUGHT, note, "端侧处理")
            }

            // 读控件树。这次**不藏悬浮窗** —— 我们自己的节点已经在
            // 解析层按包名过滤掉了，没必要为它闪一下。
            val tree = withContext(Dispatchers.IO) { controller.readUiTree() }
            val nodeCount = tree?.lines()?.count { it.isNotBlank() } ?: 0
            logger?.line("控件树：$nodeCount 个元素", "UI")

            // ---- 界面变了没有 ----
            // 用控件树文本的指纹，不再依赖截图（默认没有截图了）
            val treeHash = if (tree.isNullOrBlank()) 0 else md5(tree.toByteArray())
            if (treeHash != 0 && treeHash == lastTreeHash) sameTree++ else sameTree = 0
            if (treeHash != 0) lastTreeHash = treeHash

            // ---- 卡死止损 ----
            // 注入提示只到 2 次；再往下就是明知道没用还在烧钱，直接停。
            if (sameTree >= STUCK_LIMIT || repeatAction >= STUCK_LIMIT) {
                val why = if (sameTree >= STUCK_LIMIT) {
                    "界面连续 ${sameTree + 1} 步没有任何变化"
                } else {
                    "模型连续 ${repeatAction + 1} 次给出同一批动作"
                }
                val msg = "卡住了：$why，已经停下。可能是这个界面点不动、" +
                    "或者需要你自己操作一下（比如输入密码）。"
                logger?.error(msg, "卡死")
                listener.onEvent(EventKind.ERROR, msg, "卡住")
                finish(false, msg)
                return
            }

            val interruptions = buildList {
                // 纸盒自己在前台：比"界面没变化"更根本的问题，先说它
                if (foreground != null && foreground == selfPackage) {
                    add(
                        "现在前台是「纸盒」自己，不是用户要操作的应用。先用 open_app 打开目标应用" +
                            "（包名不确定就先调 list_apps 查真实包名），不要在纸盒的界面上点击。"
                    )
                }
                if (sameTree >= 2) {
                    add(
                        "界面元素和上一步完全一样，说明你上一批动作**没有生效**。" +
                            "不要再用同样的方式。换一个元素编号，或者换一种动作。"
                    )
                }
                if (repeatAction >= 2) {
                    add(
                        "你已经连续 $repeatAction 次给出同一批动作了，它显然不起作用。" +
                            "这一次必须换一个完全不同的做法。"
                    )
                }
            }.joinToString("\n")

            // ---- 问模型（可能要图、可能要调技能，可能来回几次）----
            var parsed: ActionParser.Parsed? = null
            var modelOutput = ""
            var imageRequests = 0
            var skillCalls = 0
            var imageOverLimitRetries = 0
            var skillOverLimitRetries = 0

            /**
             * 读不到控件树时（副屏模式就是这种），**截图是模型唯一的信息来源**。
             *
             * 主屏模式下控件树已经能说清界面，图是按需要才给；但副屏上
             * 一条控件树都读不到 —— 这时如果还等模型主动要图，第一轮
             * 它就是在完全瞎的情况下做判断（而且它连"现在是什么界面"
             * 都不知道，甚至不知道要不要图）。所以这种情况直接带上图。
             *
             * 代价是每一步都多一张图的 token。但这是副屏模式必然的成本，
             * 不是可以优化掉的东西。
             */
            val autoImage: ByteArray? = if (tree == null) {
                withContext(Dispatchers.IO) { controller.captureFrame() }
            } else {
                null
            }
            if (autoImage != null) {
                logger?.line(
                    "读不到控件树，本轮自动带上截图（${autoImage.size} 字节）",
                    "截图",
                )
            }

            var pendingImage: ByteArray? = autoImage
            var imageNote: String? = null
            var skillNote: String? = null

            // ---- 上一步 capture 截到的图：和这步的界面元素一起发 ----
            if (pendingActionShot != null) {
                val shot = pendingActionShot
                pendingActionShot = null // 无条件清，副屏 / 超限都不残留
                when {
                    // 副屏每步自带 autoImage，再附一张没有意义（而且是同一个画面）
                    tree == null ->
                        logger?.line("忽略 capture 的图：这一屏没有控件树，本步已自动带图", "截图")
                    totalImages >= MAX_TOTAL_IMAGES -> {
                        logger?.line(
                            "忽略 capture 的图：已达本次任务图片上限（$MAX_TOTAL_IMAGES 张）",
                            "截图",
                        )
                        lastResult = (lastResult?.let { "$it " } ?: "") +
                            "（已达本次任务图片上限，上一步截的图没有发给你）"
                    }
                    shot.isEmpty() -> {
                        val err = controller.lastScreenshotError() ?: "未知原因"
                        logger?.error("capture 的图取不到了：$err", "截图")
                    }
                    else -> {
                        pendingImage = shot
                        imageNote = "这是你要的那张截图（capture）。"
                        totalImages++
                        logger?.line("capture 的图随本步发送：${shot.size} 字节", "截图")
                    }
                }
            }

            ask@ while (true) {
                if (isStopped()) {
                    finish(false, "你停止了任务（第 $step 步，模型调用前）")
                    return
                }

                // 控件树为空时要说清是**哪一种**空 —— 副屏、纸盒自己在前台、
                // 界面自绘，这三种情况模型该做的事完全不同（见 AgentPrompt 常量）
                val noTreeReason = when {
                    tree != null -> null
                    controller.isVirtualDisplay -> AgentPrompt.NO_TREE_VIRTUAL_DISPLAY
                    foreground != null && foreground == selfPackage -> AgentPrompt.NO_TREE_SELF
                    else -> null // 兜底：AgentPrompt 里会用 DEFAULT_NO_TREE_REASON
                }

                val userText = AgentPrompt.stepMessage(
                    step = step,
                    maxSteps = maxSteps,
                    task = task,
                    screenWidth = w,
                    screenHeight = h,
                    foregroundPackage = foreground,
                    uiTree = tree,
                    interruption = interruptions.ifBlank { null },
                    lastResult = lastResult,
                    imageNote = imageNote,
                    skillNote = skillNote,
                    noTreeReason = noTreeReason,
                )

                // ⚠️ 必须把**实际发出去的这条消息**原样追加进 history ——
                // 包括它带的那张图。图片如果只挂在"最后一条 user"上，
                // 同一条消息在两次请求里会一次带图一次不带，
                // 前缀从第一条 user 就分叉，缓存等于没有。
                //
                // 现在整条对话严格递增、每个字节都可复现：
                //     [system][user1(+图)][assistant1][user2(+图)]...
                history.add(ChatTurn(ChatTurn.USER, userText, pendingImage))

                listener.onEvent(EventKind.THOUGHT, "正在请求模型 ...", "第 $step 步")
                val callStart = System.currentTimeMillis()
                OverlayBus.setPhase(AgentPhase.UPLOADING)

                // 请求期间也必须能被急停打断。
                //
                // 为什么不能只靠"动作间隙查一次"：模型卡住时这一行会阻塞几十秒
                // 到几分钟，那段时间里按急停是**完全没有反应**的 —— 协程取消
                // 穿不透一个阻塞中的 socket 读。所以另起一个轻量看门狗盯着，
                // 一发现要停就 abort()，把连接掀掉让请求立刻失败。
                // 这是用户能"随时抽身"的最后一道保障。
                val result = withContext(Dispatchers.IO) {
                    val stopWatchdog = launch {
                        while (isActive) {
                            delay(STOP_POLL_MS)
                            if (isStopped()) {
                                llm.abort()
                                return@launch
                            }
                        }
                    }
                    try {
                        llm.chat(
                            system = system,
                            history = history,
                            onUploaded = {
                                // 请求体传完了，接下来是等服务端算
                                OverlayBus.setPhase(AgentPhase.WAITING_MODEL)
                            },
                            // 重发是本轮新增的：以前一旦卡住就只能干等到超时，
                            // 现在会自己退避重试，并把"正在重发"告诉用户 ——
                            // 状态卡和日志各给一份，免得他以为程序死了
                            onRetry = { attempt, maxAttempts, why, waitMs ->
                                OverlayBus.setPhase(AgentPhase.RETRYING)
                                val secs = (waitMs + 999) / 1000
                                val line = "模型没有正常响应（$why）—— ${secs} 秒后重发" +
                                    "（第 $attempt 次失败，最多试 $maxAttempts 次）"
                                // 回调在 IO 线程上；日志列表和运行日志都按惯例在主线程写，切回去
                                launch(Dispatchers.Main) {
                                    // 两处都要写：界面让用户**当下**知道它在自救，
                                    // 运行日志留着事后回答"这一趟为什么慢"
                                    listener.onEvent(EventKind.THOUGHT, line, "重发")
                                    logger?.line(line, "重发")
                                }
                            },
                            shouldAbort = { isStopped() },
                        )
                    } finally {
                        stopWatchdog.cancel()
                    }
                }
                val elapsed = System.currentTimeMillis() - callStart

                when (result) {
                    is LlmResult.Fail -> {
                        // 因为是急停而被中断的，别报成"模型出错" ——
                        // 那是用户自己按的停止，弹一条红色的报错只会让人困惑
                        if (isStopped()) {
                            finish(false, "你停止了任务（模型请求被中断）")
                            return
                        }
                        logger?.error("模型调用失败：${result.message}", "模型")
                        fail(result.message, "模型出错")
                        return
                    }

                    is LlmResult.Ok -> {
                        promptTokens += result.promptTokens
                        completionTokens += result.completionTokens
                        cacheHitTokens += result.cacheHitTokens
                        cacheMissTokens += result.cacheMissTokens

                        // ---- 上下文长度兜底 ----
                        // 上下文不裁剪（缓存按前缀匹配），所以得防着撑爆模型窗口。
                        // 用服务端返回的真实 prompt_tokens，比本地估算准。
                        if (result.promptTokens >= CONTEXT_STOP_TOKENS) {
                            val msg = "上下文已经 ${result.promptTokens} token，接近模型上限，" +
                                "为避免请求被拒先停下。开个新任务继续吧。"
                            logger?.error(msg, "上下文")
                            listener.onEvent(EventKind.ERROR, msg, "上下文过长")
                            finish(false, msg)
                            return
                        }
                        if (result.promptTokens >= CONTEXT_WARN_TOKENS) {
                            logger?.warn("上下文已到 ${result.promptTokens} token，快满了", "上下文")
                        }
                        logger?.line(
                            "模型返回 ${elapsed}ms，token：输入 ${result.promptTokens} / " +
                                "输出 ${result.completionTokens}" +
                                "（缓存命中 ${result.cacheHitTokens} / " +
                                "未命中 ${result.cacheMissTokens}）" +
                                if (pendingImage != null) "（本轮带图）" else "",
                            "模型",
                        )
                        // 前缀复用率是"我们这一侧有没有把前缀维持住"的答案。
                        // 它高、而服务端命中率低 → 前缀没问题，是服务端缓存的事；
                        // 它本身就低 → 我们的请求构造有问题，历史被改动了
                        if (result.prefixTotal > 0) {
                            logger?.line(
                                "前缀复用：${result.prefixReused} / ${result.prefixTotal} 条" +
                                    if (result.prefixReused == result.prefixTotal) {
                                        "（完整）"
                                    } else {
                                        "（⚠️ 从第 ${result.prefixReused + 1} 条起分叉，" +
                                            "这之后的缓存都用不上）"
                                    },
                                "缓存",
                            )
                        }

                        logger?.section("模型原始输出")
                        result.text.lines().forEach { logger?.line("  $it") }

                        val p = ActionParser.parse(result.text, w, h)
                        p.thought?.let {
                            logger?.line("思考：$it", "模型")
                            listener.onEvent(EventKind.THOUGHT, it, "第 $step 步 · 思考")
                        }

                        // ---- 模型说做不下去 ----
                        if (p.failed) {
                            val why = p.summary.ifBlank { "模型判断这个任务做不下去" }
                            logger?.warn("模型主动放弃：$why", "任务")
                            listener.onEvent(EventKind.ERROR, why, "做不了")
                            finish(false, why)
                            return
                        }

                        // ---- 任务完成？ ----
                        // 模型可能同一批既给 actions 又说 finished（语义是"做完这批
                        // 动作任务就完成了"）。有动作时不能在这里结束，否则动作一个都
                        // 不会执行；放去这批动作执行完之后再收尾（见循环末尾）。
                        if (p.finished && p.actions.isEmpty()) {
                            val summary = p.summary.ifBlank { "模型判断任务已完成" }
                            logger?.line("任务结束：$summary", "任务")
                            listener.onEvent(EventKind.RESULT, summary, "完成")
                            finish(true, summary)
                            return
                        }

                        // ---- 模型要截图 ----
                        if (p.needImage && imageRequests < MAX_IMAGE_REQUESTS) {
                            history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                            imageRequests++
                            logger?.line("模型要求看截图（第 $imageRequests 次）", "截图")

                            // 截图前先确保纸盒不在前台，否则模型看到的是纸盒自己
                            yieldForegroundIfNeeded()
                            OverlayBus.setPhase(AgentPhase.SCREENSHOT)
                            OverlayBus.hide()
                            delay(OVERLAY_SETTLE_MS)
                            val shot = withContext(Dispatchers.IO) { controller.captureFrame() }
                            OverlayBus.show()

                            if (shot == null || shot.isEmpty()) {
                                val err = controller.lastScreenshotError() ?: "未知原因"
                                logger?.error("截图失败：$err", "截图")
                                imageNote = "截图失败（$err）。只能靠界面元素判断。"
                                // 截图失败时清空 pendingImage，防止旧图污染
                                pendingImage = null
                            } else {
                                pendingImage = shot
                                totalImages++
                                logger?.line("截图：${w}x$h，${shot.size} 字节（本次发送）", "截图")
                                logger?.saveScreenshot(step, shot)
                                imageNote = "这是你要的截图。"
                            }
                            // 重新问一次：这次带上图，不算新的一步
                            continue@ask
                        }
                        if (p.needImage) {
                            // 超限后如果模型还是只要图不给动作，直接终止，不要继续烧钱
                            if (p.actions.isEmpty()) {
                                val msg = "模型已经要求了 $MAX_IMAGE_REQUESTS 次截图，仍然没有给出任何动作。" +
                                    "可能是这个界面只靠截图也看不懂，或者模型卡住了。"
                                logger?.error(msg, "截图超限")
                                listener.onEvent(EventKind.ERROR, msg, "截图超限")
                                finish(false, msg)
                                return
                            }
                            // 超限但模型给了动作 → 执行动作（别丢有效动作），但计数器 +1
                            imageOverLimitRetries++
                            if (imageOverLimitRetries > OVER_LIMIT_MAX_RETRY) {
                                val msg = "模型连续 $imageOverLimitRetries 次坚持要截图，即使已经给了动作。" +
                                    "说明它根本没打算靠界面元素做决策，继续下去就是烧钱。"
                                logger?.error(msg, "截图超限")
                                listener.onEvent(EventKind.ERROR, msg, "截图超限")
                                finish(false, msg)
                                return
                            }
                            // 告诉模型以后不会再给图了
                            imageNote = "这一轮不会再给截图了（已经要了 $MAX_IMAGE_REQUESTS 次）。" +
                                "请根据界面元素列表继续操作，不要再要截图了。"
                            logger?.warn(
                                "模型又要截图，但这一轮已经给过 $MAX_IMAGE_REQUESTS 次了，" +
                                    "给了动作，先执行（第 $imageOverLimitRetries 次超限重试）",
                                "截图",
                            )
                        }

                        // ---- 模型要调用技能 ----
                        // 和要截图一样：先给它信息，这一轮不算一步。
                        // 技能取的是"屏幕上没有的信息"（装了什么应用之类），
                        // 所以必须在给动作之前拿到。
                        val skillId = p.skillId
                        if (skillId != null && skillCalls < MAX_SKILL_CALLS) {
                            history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                            skillCalls++
                            logger?.line("调用技能：$skillId（第 $skillCalls 次）", "技能")

                            OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                            val outcome = skills.run(skillId, p.skillArgs)

                            // 技能返回会留在上下文里，日志里给个缩略就够了 ——
                            // 应用列表那种几千字符的全打进日志会把 run.log 撑爆
                            logger?.line(
                                "技能返回${if (outcome.ok) "" else "（失败）"}：" +
                                    outcome.text.take(200).replace("\n", " | ") +
                                    if (outcome.text.length > 200) " …（共 ${outcome.text.length} 字符，已发给模型）" else "",
                                "技能",
                            )
                            if (!outcome.ok) {
                                listener.onEvent(EventKind.ERROR, outcome.text, "技能失败")
                            }
                            skillNote = if (outcome.ok) {
                                "技能 $skillId 返回：\n${outcome.text}"
                            } else {
                                "技能 $skillId 没能取到信息：${outcome.text}"
                            }
                            continue@ask
                        }
                        if (skillId != null) {
                            // 超限后如果模型还是只要技能不给动作，直接终止，不要继续烧钱
                            if (p.actions.isEmpty()) {
                                val msg = "模型已经要求了 $MAX_SKILL_CALLS 次调用技能，仍然没有给出任何动作。" +
                                    "可能是模型卡住了，或者技能也解决不了问题。"
                                logger?.error(msg, "技能超限")
                                listener.onEvent(EventKind.ERROR, msg, "技能超限")
                                finish(false, msg)
                                return
                            }
                            // 超限但模型给了动作 → 执行动作（别丢有效动作），但计数器 +1
                            skillOverLimitRetries++
                            if (skillOverLimitRetries > OVER_LIMIT_MAX_RETRY) {
                                val msg = "模型连续 $skillOverLimitRetries 次坚持要调用技能，即使已经给了动作。" +
                                    "说明它根本没打算靠现有信息做决策，继续下去就是烧钱。"
                                logger?.error(msg, "技能超限")
                                listener.onEvent(EventKind.ERROR, msg, "技能超限")
                                finish(false, msg)
                                return
                            }
                            // 告诉模型以后不会再调技能了
                            skillNote = "这一轮不会再调用技能了（已经调了 $MAX_SKILL_CALLS 次）。" +
                                "请根据已有信息继续操作，不要再调技能了。"
                            logger?.warn(
                                "模型又要调技能，但这一轮已经调过 $MAX_SKILL_CALLS 次了，" +
                                    "给了动作，先执行（第 $skillOverLimitRetries 次超限重试）",
                                "技能",
                            )
                        }

                        parsed = p
                        modelOutput = result.text
                        break@ask
                    }
                }
            }

            // 循环里的每条出口要么已经 return，要么就是带着 parsed 跳出 ——
            // 所以这里实际上一定不是 null，兜底只是为了不写 !!
            val p = parsed ?: run {
                fail("内部错误：没有拿到可用的模型输出。", "模型")
                return
            }

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

            p.warning?.let { logger?.warn("解析提示：$it", "解析") }

            // ---- 防死循环：整批动作的签名 ----
            val sig = p.actions.joinToString(";") { actionSignature(it) }
            if (sig == lastSig) repeatAction++ else repeatAction = 0
            lastSig = sig

            // ---- 显示这一批要干什么 ----
            val desc = AgentPrompt.describeSequence(p.actions)
            logger?.line("动作（${p.actions.size} 个）：$desc", "执行")
            listener.onEvent(EventKind.ACTION, desc, "第 $step 步 · 动作")
            if (p.nextHint.isNotBlank()) {
                logger?.line("下一步预告：${p.nextHint}", "模型")
            }
            // 状态卡放不下 12 个动作，只显示第一个 + 总数
            OverlayBus.update(step, maxSteps, overlayText(p.actions), p.nextHint)

            // ---- 逐个执行（执行 + 等界面稳定 + 动作后验证）----
            val results = ArrayList<String>(p.actions.size)
            // 因切换通道而完全没执行（没进 results）的动作数；-1 表示没被切换中断
            var switchPendingCount = -1
            // 本批截了几张图。一批最多一张：模型可能连写几个 capture，
            // 但同一批里画面几乎没变，多截只是多花钱
            var shotsThisBatch = 0
            for ((i, action) in p.actions.withIndex()) {
                // 动作间隙统一查中断：急停立刻收尾；切换请求 break、交本步 tail 留痕，
                // 下一轮 for(step) 开头 261/268 处理迁移/回迁（0.8.2）
                when (interruption()) {
                    WaitOutcome.STOPPED -> {
                        finish(false, "你停止了任务（执行第 $step 步第 ${i + 1} 个动作之前）")
                        return
                    }
                    WaitOutcome.SWITCH_REQUESTED -> {
                        switchPendingCount = p.actions.size - i
                        break
                    }
                    WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                        if (handleAutoMoveToVirtualDisplay()) {
                            switchPendingCount = p.actions.size - i
                        } else {
                            results.add("自动切副屏未成功（已交回云端）")
                            switchPendingCount = p.actions.size - i
                        }
                        break
                    }
                    WaitOutcome.DONE -> {}
                }

                val single = AgentPrompt.describe(action)
                OverlayBus.update(step, maxSteps, overlayText(listOf(action)), p.nextHint)
                val next = p.actions.getOrNull(i + 1)

                // sleep 由这里自己做（协程 delay，可中途急停/切换），不走通道 ——
                // 通道里的 Thread.sleep 会把整个线程按住。
                if (action.kind == TouchKind.WAIT) {
                    OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                    logger?.line("  $single", "执行")
                    when (awaitWithStop(action.durationMs.toLong())) {
                        WaitOutcome.DONE -> results.add("$single → 已执行")
                        WaitOutcome.STOPPED -> {
                            results.add("$single → 被中断")
                            finish(false, "你停止了任务（等待中被叫停）")
                            return
                        }
                        WaitOutcome.SWITCH_REQUESTED -> {
                            results.add("$single → 因切换通道中止")
                            switchPendingCount = p.actions.size - i - 1
                            break
                        }
                        WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                            val moved = handleAutoMoveToVirtualDisplay()
                            results.add(
                                if (moved) "$single → 已自动切到副屏"
                                else "$single → 自动切副屏未成功（已交回云端）"
                            )
                            switchPendingCount = p.actions.size - i - 1
                            break
                        }
                    }
                } else if (action.kind == TouchKind.CAPTURE) {
                    // 截屏**不走设备通道**（DeviceChannel.perform 不认识它）：
                    // 藏悬浮窗 → 截图 → 显示，图交给**下一步**和新界面元素一起发。
                    OverlayBus.setPhase(AgentPhase.SCREENSHOT)
                    logger?.line("  $single", "执行")
                    when {
                        shotsThisBatch >= MAX_CAPTURE_PER_BATCH ->
                            results.add("$single → 本批已经截过一张，这张跳过")

                        // 副屏每步都会自动带一张图，再截就是同一个画面
                        tree == null ->
                            results.add("$single → 这一屏已自动带图，跳过")

                        totalImages >= MAX_TOTAL_IMAGES ->
                            results.add("$single → 已达本次任务图片上限（$MAX_TOTAL_IMAGES 张），未截图")

                        else -> {
                            // 和 need_image 同一套仪式：先让开纸盒、藏悬浮窗、截、再显示
                            yieldForegroundIfNeeded()
                            OverlayBus.hide()
                            delay(OVERLAY_SETTLE_MS)
                            val shot = withContext(Dispatchers.IO) { controller.captureFrame() }
                            OverlayBus.show()
                            if (shot == null || shot.isEmpty()) {
                                val err = controller.lastScreenshotError() ?: "未知原因"
                                logger?.error("  $single → 截图失败：$err", "执行")
                                results.add("$single → 截图失败：$err")
                            } else {
                                // 计数留给"真正发出去"那一步（见 pendingActionShot 的消费）
                                pendingActionShot = shot
                                shotsThisBatch++
                                logger?.saveScreenshot(step, shot)
                                logger?.line(
                                    "  $single → 已截（${shot.size} 字节），随下一步发送",
                                    "执行",
                                )
                                results.add("$single → 已截图，随下一步发给你")
                            }
                        }
                    }
                } else if (action.kind == TouchKind.DISMISS_DIALOG) {
                    // 云端显式下发：端侧在当前页面找安全关闭按钮，找到就点、
                    // 找不到（或涉及授权/支付）就不操作；然后立刻把控制权交回
                    // 云端，本批后续预排动作暂不执行（break）
                    logger?.line("  $single", "执行")
                    results.add(handleDismissDialog())
                    when (interruption()) {
                        WaitOutcome.SWITCH_REQUESTED ->
                            // dismiss 等待期间用户切了通道：后续动作交给切换流程（0.8.2）
                            switchPendingCount = p.actions.size - i - 1
                        WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                            val moved = handleAutoMoveToVirtualDisplay()
                            if (moved) results.add("已自动切到副屏")
                            switchPendingCount = p.actions.size - i - 1
                        }
                        else -> {
                            val skipped = p.actions.size - i - 1
                            if (skipped > 0) {
                                results.add("dismiss_dialog 后的 $skipped 个动作本轮未执行（已交回云端）")
                            }
                        }
                    }
                    break
                } else {
                    OverlayBus.setPhase(AgentPhase.ACTING)
                    val isOpenApp = action.kind == TouchKind.OPEN_APP
                    // 非 open_app 动作：执行前先按需让开（纸盒在前台就回桌面），
                    // 否则这一下会点到纸盒自己。
                    if (!isOpenApp) yieldForegroundIfNeeded()

                    // 动作前控件树：用来算“动作前指纹”，tap 时还用来判断点的
                    // 是不是发送/支付等有副作用的按钮（决定要不要自动重试）。
                    val beforeNodes = if (isVerifiable(action.kind)) {
                        withContext(Dispatchers.IO) { controller.parseNodes() }
                    } else {
                        null
                    }

                    // 注入前**只在真会撞上急停按钮时**才藏。
                    // 状态卡本身是 FLAG_NOT_TOUCHABLE，永远不会吃点击，
                    // 所以只需要担心按钮那一小块。
                    val mustHide = touchesOverlayButtons(action)
                    if (mustHide) {
                        OverlayBus.hide()
                        delay(OVERLAY_SETTLE_MS)
                    }
                    // 上报真实落点，屏幕上闪一圈水波 ——
                    // 用户能看见 AI 点在哪，是"点错了"还是"点了没反应"一眼可辨
                    val execResult = withContext(Dispatchers.IO) {
                        controller.execute(action) { px, py -> OverlayBus.pulse(px, py) }
                    }
                    if (mustHide) {
                        OverlayBus.show()
                    }

                    if (execResult != null) {
                        logger?.error("  $single → $execResult", "执行")
                        listener.onEvent(EventKind.ERROR, execResult, "第 $step 步 · 失败")
                        results.add("$single → 失败：$execResult")
                    } else if (isOpenApp) {
                        // 先等目标应用启动，再确认前台是否切走 —— 顺序不能反：
                        // 冷启动慢（软件渲染/重 app）时，刚发出 open_app 就检查，
                        // 界面还停在纸盒会被误判“没切走”而补按 HOME。
                        OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                        // 带上目标包名：本地"前台是不是它了"的判据全靠它，
                        // 有它才知道什么时候可以不再等（见 awaitStable）
                        val sr = awaitStable(TouchKind.OPEN_APP, targetPkg = action.packageName)
                        when (sr.outcome) {
                            WaitOutcome.STOPPED -> {
                                finish(false, "你停止了任务（等待中被叫停）")
                                return
                            }
                            WaitOutcome.SWITCH_REQUESTED -> {
                                results.add("$single → 因切换通道中止")
                                switchPendingCount = p.actions.size - i - 1
                                break
                            }
                            WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                                val moved = handleAutoMoveToVirtualDisplay()
                                results.add(
                                    if (moved) "$single → 已自动切到副屏"
                                    else "$single → 自动切副屏未成功（已交回云端）"
                                )
                                switchPendingCount = p.actions.size - i - 1
                                break
                            }
                            WaitOutcome.DONE -> {}
                        }
                        // 启动后仍没切走（包名错/启动失败）才补 HOME，
                        // 避免下一步模型读到纸盒自己的界面。
                        verifyLeftAfterOpenApp()
                        // open_app 与后续 sleep 常在同一批：步开头记的还是旧前台，
                        // 这里补记新目标，否则同批 sleep 中回桌面拿不到目标（0.8.3）
                        updateLastTarget(withContext(Dispatchers.IO) { controller.currentPackage() })
                        logger?.line("  $single → 已执行", "执行")
                        results.add("$single → 已执行")
                    } else if (next?.kind == TouchKind.WAIT) {
                        // 下一个动作就是显式 sleep，由它去等，这里不再补等待
                        logger?.line("  $single → 已执行", "执行")
                        results.add("$single → 已执行")
                    } else {
                        // 等界面稳定（最后一个动作也等，等价原来的一批收尾），
                        // 拿到稳定指纹后做动作后验证、必要时一次重试。
                        // 把**动作前**的指纹一起传进去：有它才能在起步期就判断
                        // "界面开始变了没有"，从而提前放行（见 awaitStable）
                        OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                        val sr = awaitStable(
                            action.kind,
                            beforeFp = beforeNodes?.let { PageFingerprint.fingerprint(it) },
                        )
                        when (sr.outcome) {
                            WaitOutcome.STOPPED -> {
                                finish(false, "你停止了任务（等待中被叫停）")
                                return
                            }
                            WaitOutcome.SWITCH_REQUESTED -> {
                                results.add("$single → 因切换通道中止")
                                switchPendingCount = p.actions.size - i - 1
                                break
                            }
                            WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                                val moved = handleAutoMoveToVirtualDisplay()
                                results.add(
                                    if (moved) "$single → 已自动切到副屏"
                                    else "$single → 自动切副屏未成功（已交回云端）"
                                )
                                switchPendingCount = p.actions.size - i - 1
                                break
                            }
                            WaitOutcome.DONE ->
                                results.add(verifyAction(action, single, beforeNodes, sr.fingerprint ?: ""))
                        }
                    }
                }
            }

            val baseText = when (results.size) {
                0 -> "没有动作被执行"
                1 -> results[0]
                else -> "共 ${results.size} 个动作：" + results.joinToString("；")
            }
            // 半截批次留痕：标明因切换通道中止、还有多少动作没执行（0.8.2 验收）
            //
            // ⚠️ 解析提示（`p.warning`）必须也拼进来。它里面是"你的参数被端侧改过"
            // 这类事（最典型：sleep 超过 5000ms 没声明 long_wait，被夹短了）。
            // 只写进日志的话模型永远不知道，下一次还会写同样的值 ——
            // 这就是 0.9.0 当初漏掉的一环（写成"回灌"实际只在日志里）。
            val resultText = buildString {
                append(baseText)
                if (switchPendingCount > 0) {
                    append("（因切换通道中止，剩余 $switchPendingCount 个动作未执行）")
                }
                p.warning?.let { append("（解析提示：$it）") }
            }
            logger?.recordStep(
                step = step,
                thought = p.thought,
                action = desc,
                result = resultText,
                rawModelOutput = modelOutput,
                shot = null,
            )

            // 要图这件事已经变成 actions 里的一个 capture 动作（见 ActionParser），
            // 不再需要"这批结束时立一个旗标"的跨步机制了

            // ---- 回灌给模型 ----
            history.add(ChatTurn(ChatTurn.ASSISTANT, modelOutput))
            lastResult = resultText

            // ---- 这批动作就是任务收尾（模型给动作的同时说 finished）----
            // 动作已经逐个执行完，这里再正常结束，不再请求下一步。
            if (p.finished) {
                // 挂起的切换请求由 finish() 无条件清理，无需在此分支处理
                val summary = p.summary.ifBlank { "模型判断任务已完成" }
                logger?.line("任务结束：$summary", "任务")
                listener.onEvent(EventKind.RESULT, summary, "完成")
                finish(true, summary)
                return
            }
        }

        finish(
            false,
            "到了最大步数 $maxSteps 还没做完。可以加大步数，或者把任务拆小一点。",
        )
    }

    // ------------------------------------------------------------------
    // 切到副屏（0.8.0）
    // ------------------------------------------------------------------

    /**
     * 把主屏上正在操作的 app 整栈迁到新建副屏，并切到副屏通道。
     *
     * 调用点在每步读控件树**之前**（动作间隙、没有注入在进行），
     * 所以通道切换安全，不会在一次注入中途 release 旧通道。
     *
     * @return true 已切到副屏；false 没切（原因已给用户看，留在主屏继续）
     */
    /**
     * 手动「切到副屏」：用户点了悬浮按钮。从主屏顶部任务拿目标，
     * 再走 [performMoveToVirtualDisplay]。
     */
    private suspend fun handleMoveToVirtualDisplay(): Boolean {
        if (controller.isVirtualDisplay) {
            logger?.line("已经在副屏运行，忽略重复的切换请求", "副屏")
            return false
        }
        val top = currentMainScreenTask()
        if (top == null) {
            val msg = "迁移失败：没能从系统读到当前前台任务（taskId）。"
            logger?.error(msg, "副屏")
            listener.onEvent(EventKind.ERROR, msg, "副屏")
            return false
        }
        val (taskId, pkg) = top
        if (pkg == selfPackage) {
            // 用户点开纸盒看一眼、再点「切到副屏」—— 这时前台是纸盒自己，
            // 但我们要搬的是 **AI 正在操作的那个应用**，不是"现在前台是谁"。
            // 所以退回用上一步记下的目标；只有真的从来没记过才让人先去开应用。
            // （批 3 附加项：原来这里一律拒绝，等于用户一碰纸盒就切不了副屏）
            val t = lastTargetPkg
            if (t.isNullOrBlank()) {
                val msg = "请先让 AI 打开要操作的应用，再切到副屏" +
                    "（现在前台还是纸盒自己，也没记录到目标应用）。"
                logger?.line(msg, "副屏")
                listener.onEvent(EventKind.THOUGHT, msg, "副屏")
                return false
            }
            val tid = lastTargetTaskId.takeIf { it >= 0 && taskExists(it) }
                ?: resolveTaskIdForPackage(t)
            if (tid < 0) {
                val msg = "没找到「$t」的任务栈，先让 AI 打开它再切副屏（可能已经被关掉了）。"
                logger?.line(msg, "副屏")
                listener.onEvent(EventKind.THOUGHT, msg, "副屏")
                return false
            }
            logger?.line("前台是纸盒自己，改为迁移上次操作的目标应用：$t", "副屏")
            return performMoveToVirtualDisplay(tid, t, automatic = false).also { moved ->
                if (moved) openMirrorPage()
            }
        }
        return performMoveToVirtualDisplay(taskId, pkg, automatic = false).also { moved ->
            // 切过去之后把镜像页弹出来。以前手动切到副屏，用户**完全看不到**
            // AI 在那块屏上干什么 —— 副屏是虚拟的，切过去主屏就回桌面了，
            // 画面没有任何出口。这是"手动切屏"独有的空缺（定时任务走副屏时
            // MainActivity 已经会弹，那条路不受影响）。
            //
            // 只手动切时弹：自动迁出是**用户回桌面**触发的，这时候弹镜像
            // 等于把他从桌面拽走，是打扰。
            if (moved) openMirrorPage()
        }
    }

    /**
     * 弹出副屏镜像页（拿到副屏画面去看 AI 在干什么）。
     *
     * 从应用进程启动 Activity 必须带 NEW_TASK。后台启动 Activity 在 Android 10+
     * 默认被拦，但纸盒持有悬浮窗权限（SYSTEM_ALERT_WINDOW），属于系统放行的
     * 白名单，所以这条路通。万一拉不起来也只是"看不到画面"，
     * **不影响任务本身**，所以这里只记一行日志、不抛错。
     */
    fun openMirrorPage() {
        val id = controller.virtualDisplayId ?: return
        runCatching {
            controller.appContext.startActivity(
                com.aiphone.assistant.display.MirrorActivity
                    .intent(controller.appContext, id)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { logger?.warn("副屏镜像页没能弹出：${it.message}", "副屏") }
    }

    /**
     * 自动迁出（0.8.3）：用户回桌面、顶部已是桌面，目标用缓存的 lastTarget*。
     * 迁移前校验缓存 taskId、不在则按包名重解析；两层都拿不到就不迁、加冷却。
     */
    private suspend fun handleAutoMoveToVirtualDisplay(): Boolean {
        val pkg = lastTargetPkg
        if (pkg.isNullOrBlank()) {
            markAutoMoveFailed("没有记录到要操作的目标应用")
            return false
        }
        // 护栏2：触发后、动手前最后复核——前台已离开桌面（新 app 被切到前台）
        // 就中止本次迁移、进冷却、不建屏。currentPackage 为 null（切换中）则不拦。
        val nowFg = withContext(Dispatchers.IO) { controller.currentPackage() }
        if (nowFg != null && AutoService.get()?.isHomePackage(nowFg) != true) {
            markAutoMoveFailed("已离开桌面（$nowFg）")
            return false
        }
        var taskId = lastTargetTaskId
        if (taskId < 0 || !taskExists(taskId)) taskId = resolveTaskIdForPackage(pkg)
        if (taskId < 0) {
            markAutoMoveFailed("没找到 $pkg 的任务栈（可手动点切到副屏）")
            return false
        }
        return performMoveToVirtualDisplay(taskId, pkg, automatic = true)
    }

    /** 自动迁移没成：记日志 + 冷却，避免停在桌面被反复尝试。 */
    private fun markAutoMoveFailed(reason: String) {
        logger?.line("检测到回到桌面，但$reason，本次不自动迁移", "副屏")
        autoMoveCooldownUntilMs = System.currentTimeMillis() + AUTO_FAIL_COOLDOWN_MS
    }

    /**
     * 建副屏 → 整栈迁移（失败重开）→ 校验 → 主屏回桌面 → 切副屏通道。
     * 手动（[handleMoveToVirtualDisplay]）和自动（[handleAutoMoveToVirtualDisplay]）共用。
     */
    private suspend fun performMoveToVirtualDisplay(
        taskId: Int,
        pkg: String,
        automatic: Boolean,
    ): Boolean {
        val ctx = controller.appContext
        // 切之前抓主屏物理尺寸（副屏照抄它）
        val mainSize = controller.screenSize()?.takeIf { it.first > 0 && it.second > 0 }
        if (mainSize == null) {
            logger?.error("迁移失败：拿不到主屏分辨率。", "副屏")
            return false
        }

        // 1) 建副屏（ShellService 内部已有则复用）
        OverlayBus.setPhase(AgentPhase.ACTING)
        val vdId = ShizukuBridge.createDisplay(ctx)
        if (vdId < 0) {
            val why = ShizukuBridge.lastError ?: "未知原因"
            val msg = "建副屏失败，留在主屏继续：$why"
            logger?.error(msg, "副屏")
            listener.onEvent(EventKind.ERROR, msg, "副屏")
            return false
        }
        vdCreatedByAgent = true

        // 2) 整栈迁移；失败则降级为「重开应用」
        val keptStack = moveStack(taskId, vdId)
        if (!keptStack) {
            logger?.line("整栈迁移失败，将重开应用（临时内容可能丢失）", "副屏")
            listener.onEvent(EventKind.THOUGHT, "迁移失败，正在副屏重开应用 …", "副屏")
            if (!reopenOnDisplay(pkg, vdId)) {
                // 副屏已建好、应用又被 force-stop：立即销毁副屏，不必等任务结束
                val dr = ShizukuBridge.destroyDisplay(ctx)
                vdCreatedByAgent = false
                val tail = if (dr >= 0) "（副屏已收回）" else "（副屏没收回，可到副屏调试页手动销毁）"
                val msg = "在副屏重开应用也失败了，应用已被关闭，需要重新打开$tail。"
                logger?.error(msg, "副屏")
                listener.onEvent(EventKind.ERROR, msg, "副屏")
                autoMoveCooldownUntilMs = System.currentTimeMillis() + AUTO_FAIL_COOLDOWN_MS
                return false
            }
        }

        // 3) 校验：整栈迁移时该 task 确实到了新 display
        if (keptStack && !verifyTaskOnDisplay(taskId, vdId)) {
            logger?.line("迁移后校验没通过，继续按副屏模式跑", "副屏")
            listener.onEvent(EventKind.RESULT, "没在副屏找到该应用，可能显示为空屏", "副屏")
        }

        // 4) 主屏回桌面（必须在切通道前，HOME 走主屏无障碍）。
        // R3：前台已是桌面就不再按 HOME——自动场景用户本就在桌面，再按会把
        // “正在冷启动、还没发窗口事件”的 app 压回（反例2 真因）；手动场景才按。
        val fgBeforeHome = withContext(Dispatchers.IO) { controller.currentPackage() }
        if (AutoService.get()?.isHomePackage(fgBeforeHome) != true) {
            pressHomeToYield()
        }

        // 5) 切通道（此刻无注入在进行）
        controller.enterVirtualDisplay(vdId, mainSize)

        val lead = if (automatic) "已检测到你回到桌面，自动" else "已"
        logger?.line("${lead}切到副屏（display=$vdId），AI 改用截图 + 坐标操作", "副屏")
        listener.onEvent(EventKind.ACTION, "${lead}切到副屏运行", "副屏")
        onChannelSwitched("副屏")
        return true
    }

    /**
     * 每步记录"AI 正在操作的目标 app"（0.8.3）。
     * 包名便宜（一次 rootInActiveWindow）；包名变化时才 dumpsys 拿 taskId。
     * 过滤纸盒自己和桌面。
     */
    private suspend fun updateLastTarget(pkg: String?) {
        if (pkg.isNullOrBlank() || pkg == selfPackage) return
        if (AutoService.get()?.isHomePackage(pkg) == true) return
        if (pkg != lastTargetPkg) {
            lastTargetPkg = pkg
            lastTargetTaskId = resolveTaskIdForPackage(pkg)
        }
        lastTargetAtMs = System.currentTimeMillis()
    }

    /**
     * 在主屏（Display #0）段里按包名找任务，取最后一个匹配（最近活跃）。
     * 包名变化 / 缓存失效时调用，不在每步调用。
     */
    private suspend fun resolveTaskIdForPackage(pkg: String): Int {
        val out = ShizukuBridge.run(controller.appContext, "dumpsys activity activities")
        val start = out.indexOf("Display #0")
        if (start < 0) return -1
        val next = out.indexOf("Display #", start + 1)
        val seg = if (next < 0) out.substring(start) else out.substring(start, next)
        val ids = Regex("""${Regex.escape(pkg)}/\S+\s+t(\d+)""")
            .findAll(seg).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return ids.lastOrNull() ?: -1
    }

    /** 该 taskId 是否还在活动列表（迁移前校验缓存）。 */
    private suspend fun taskExists(taskId: Int): Boolean {
        val out = ShizukuBridge.run(controller.appContext, "dumpsys activity activities")
        return out.contains(" t$taskId}")
    }

    /**
     * 是否满足"自动切副屏"（0.8.3，Q3 六条）。interruption() 每 100ms 调、只读内存、零 binder。
     * 推断意图、误判代价是凭空切屏，所以条件从严、宁可漏不可错。
     */
    private fun autoDesktopSwitchReady(): Boolean {
        val now = System.currentTimeMillis()
        // ① 主屏 + Shizuku READY（autoSwitchArmed 每步刷新）
        if (!autoSwitchArmed) return false
        // ② 目标包有效、非纸盒（桌面已在 updateLastTarget 过滤）
        val pkg = lastTargetPkg ?: return false
        if (pkg == selfPackage) return false
        // ③ 目标 app 最近还在前台（够新）。
        // R4：长 sleep 期间没有步边界刷新时间戳，30s 新鲜度会误挡；补一条因果
        // 证明——这次回桌面就是从目标 app 切走的（packageBeforeDesktop==pkg）也成立。
        val fresh = now - lastTargetAtMs <= TARGET_FRESH_MS
        val fromTarget = AutoService.get()?.packageBeforeDesktop() == pkg
        if (!fresh && !fromTarget) return false
        // ④ 不在"自己刚按 HOME"的抑制窗口
        if (selfHomeAtMs != 0L && now - selfHomeAtMs < SELF_HOME_SUPPRESS_MS) return false
        // 失败冷却
        if (now < autoMoveCooldownUntilMs) return false
        // ⑤ 回桌面已持续 400ms（防抖，时间戳由无障碍事件写入）
        val since = AutoService.get()?.desktopSinceMs() ?: 0L
        if (since == 0L || now - since < DESKTOP_DEBOUNCE_MS) return false
        return true
    }

    // ------------------------------------------------------------------
    // 切回主屏（0.8.1）
    // ------------------------------------------------------------------

    /**
     * 把副屏上正在操作的 app 整栈迁回主屏，切回无障碍通道，再销毁副屏。
     * [handleMoveToVirtualDisplay] 的反向。调用点同样在每步读控件树**之前**
     * （动作间隙、没有注入在进行）。
     *
     * @return true 已切回主屏；false 没切（原因已给用户看，留在副屏继续）
     */
    private suspend fun handleReturnToMainScreen(): Boolean {
        val ctx = controller.appContext
        if (!controller.isVirtualDisplay) {
            logger?.line("已经在主屏运行，忽略重复的回迁请求", "副屏")
            return false
        }
        // exitVirtualDisplay 会把 display 置 null，先把 id 抓进局部
        val vdId = controller.virtualDisplayId
        if (vdId == null) {
            val msg = "回迁失败：读不到副屏编号。"
            logger?.error(msg, "副屏")
            listener.onEvent(EventKind.ERROR, msg, "副屏")
            return false
        }

        // 1) 副屏顶部任务的包名 + taskId（在已知 vdId 段里找，不猜"第一个非零段"）
        val top = currentTaskOnDisplay(vdId)
        if (top == null) {
            val msg = "回迁失败：没能从副屏读到当前任务（taskId）。"
            logger?.error(msg, "副屏")
            listener.onEvent(EventKind.ERROR, msg, "副屏")
            return false
        }
        val (taskId, pkg) = top

        // 2) 先切回主屏无障碍（move-stack 走 shell、与通道无关；先切降级才自洽）
        OverlayBus.setPhase(AgentPhase.ACTING)
        controller.exitVirtualDisplay()

        // 3) 整栈迁回主屏；失败降级为「主屏重开应用」
        val keptStack = moveStack(taskId, 0)
        if (!keptStack) {
            logger?.line("整栈回迁失败，将在主屏重开应用（临时内容可能丢失）", "副屏")
            listener.onEvent(EventKind.THOUGHT, "回迁失败，应用将被重开（临时内容可能丢失） …", "副屏")
            if (!reopenOnDisplay(pkg, 0)) {
                // 通道已回主屏、应用又被 force-stop：销毁副屏兜底，再报错
                val dr = ShizukuBridge.destroyDisplay(ctx)
                vdCreatedByAgent = false
                val tail = if (dr >= 0) "（副屏已收回）" else "（副屏没收回，可到副屏调试页手动销毁）"
                val msg = "在主屏重开应用也失败了，应用已被关闭，需要重新打开$tail。"
                logger?.error(msg, "副屏")
                listener.onEvent(EventKind.ERROR, msg, "副屏")
                return false
            }
        }

        // 4) 校验：任务确实回到主屏
        if (!verifyTaskOnDisplay(taskId, 0)) {
            logger?.line("回迁后校验没通过（主屏段没找到该任务）", "副屏")
            listener.onEvent(EventKind.RESULT, "回迁可能没成功，主屏没找到该应用", "副屏")
        }

        // 5) 任务在主屏但没获焦（顶部还是桌面）：把已有 task 抬到前台，保留深层页
        if (!isTopResumedOnDisplay(taskId, 0)) {
            logger?.line("任务已回主屏但没在前台，尝试把它抬到前台", "副屏")
            bringTaskToFront(taskId, pkg)
        }

        // 6) 任务确认回主屏后，销毁副屏、清位
        val dr = ShizukuBridge.destroyDisplay(ctx)
        vdCreatedByAgent = false
        if (dr < 0) {
            logger?.line("副屏没收回（$dr），可到副屏调试页手动销毁", "副屏")
        }

        val why = OverlayBus.returnFromVdReason
        logger?.line("已切回主屏（display=0），AI 恢复无障碍操作（原因：$why）", "副屏")
        listener.onEvent(EventKind.ACTION, "已切回主屏运行", "副屏")
        // 自动触发的那次要进 30s 静默期，否则会和"按 HOME 自动切副屏"打成乒乓球
        if (why != RETURN_REASON_MANUAL) OverlayBus.suppressAutoReturnFor(AUTO_RETURN_COOLDOWN_MS)
        onChannelSwitched("主屏")
        return true
    }

    /**
     * 解析指定 display 段顶部 resumed 任务：返回 (taskId, packageName)。
     * 只看 Display #<displayId> 段第一个 topResumedActivity，右界到下一个 Display 段。
     */
    private suspend fun currentTaskOnDisplay(displayId: Int): Pair<Int, String>? {
        val out = ShizukuBridge.run(controller.appContext, "dumpsys activity activities")
        val start = out.indexOf("Display #$displayId")
        if (start < 0) return null
        val next = out.indexOf("Display #", start + 1)
        val seg = if (next < 0) out.substring(start) else out.substring(start, next)
        val line = seg.lineSequence().firstOrNull { it.contains("topResumedActivity=ActivityRecord") }
            ?: return null
        // topResumedActivity=ActivityRecord{93e952e u0 com.android.settings/.SubSettings t190}
        val m = Regex("""ActivityRecord\{\S+\s+\S+\s+([\w.]+)/\S+\s+t(\d+)\}""").find(line)
            ?: return null
        val taskId = m.groupValues[2].toIntOrNull() ?: return null
        return taskId to m.groupValues[1]
    }

    /** 该 display 段的 topResumedActivity 是不是这个 task（判断回迁后有没有获焦）。 */
    private suspend fun isTopResumedOnDisplay(taskId: Int, displayId: Int): Boolean {
        val out = ShizukuBridge.run(controller.appContext, "dumpsys activity activities")
        val idx = out.indexOf("Display #$displayId")
        if (idx < 0) return false
        val next = out.indexOf("Display #", idx + 1)
        val seg = if (next < 0) out.substring(idx) else out.substring(idx, next)
        val line = seg.lineSequence().firstOrNull { it.contains("topResumedActivity=ActivityRecord") }
            ?: return false
        return line.contains(" t$taskId}")
    }

    /**
     * 把已存在的 task 抬到主屏前台、尽量不重建页面。
     * 先试 `am task focus`；不行再 resolve launcher 组件、用 NEW_TASK 把现有 task 带上来
     * （不带 CLEAR_TOP，投递给现有实例、保留深层页）。
     */
    private suspend fun bringTaskToFront(taskId: Int, pkg: String) {
        val ctx = controller.appContext
        val focusOut = ShizukuBridge.run(ctx, "am task focus $taskId")
        delay(300)
        if (focusOut.isBlank() && isTopResumedOnDisplay(taskId, 0)) return

        val resolved = ShizukuBridge.run(ctx, "cmd package resolve-activity --brief $pkg")
        val component = resolved.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("$pkg/") } ?: return
        ShizukuBridge.run(ctx, "am start --display 0 -n $component -f 0x10000000")
    }

    /** 主屏（Display #0）顶部 resumed 任务：currentTaskOnDisplay 的特例。 */
    private suspend fun currentMainScreenTask(): Pair<Int, String>? = currentTaskOnDisplay(0)

    /** 整栈迁移：`am display move-stack <taskId> <displayId>`。成功无输出。 */
    private suspend fun moveStack(taskId: Int, displayId: Int): Boolean {
        val out = ShizukuBridge.run(
            controller.appContext,
            "am display move-stack $taskId $displayId",
        )
        val ok = out.isBlank()
        if (!ok) logger?.line("move-stack 输出：${out.trim()}", "副屏")
        return ok
    }

    /** 校验 task 是否已经在指定 display 的段落里。 */
    private suspend fun verifyTaskOnDisplay(taskId: Int, displayId: Int): Boolean {
        val out = ShizukuBridge.run(controller.appContext, "dumpsys activity activities")
        val idx = out.indexOf("Display #$displayId")
        if (idx < 0) return false
        // 只在该 display 段内匹配，右界到下一个 Display 段，防跨段误判"已迁过去"
        val next = out.indexOf("Display #", idx + 1)
        val seg = if (next < 0) out.substring(idx) else out.substring(idx, next)
        return seg.contains(" t$taskId}")
    }

    /**
     * 降级：force-stop 后在副屏重开应用。
     * 先 `cmd package resolve-activity` 拿启动组件，再 start 到副屏。
     */
    private suspend fun reopenOnDisplay(pkg: String, displayId: Int): Boolean {
        val ctx = controller.appContext
        ShizukuBridge.run(ctx, "am force-stop $pkg")
        val resolved = ShizukuBridge.run(ctx, "cmd package resolve-activity --brief $pkg")
        val component = resolved.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$pkg/") }
            ?: return false
        val out = ShizukuBridge.startOnDisplay(ctx, component, displayId)
        return out.isBlank() ||
            (!out.contains("Error", true) && !out.contains("Exception", true))
    }

    /**
     * 刷新悬浮按钮：
     * 副屏模式 → 绿「切回主屏」可点；主屏模式 → 蓝「切到副屏」、仅 Shizuku READY 可点。
     */
    private suspend fun refreshMoveButton() {
        if (controller.isVirtualDisplay) {
            OverlayBus.updateMoveButton(true, "切回主屏", backMode = true)
            return
        }
        when (ShizukuBridge.state(controller.appContext)) {
            ShizukuBridge.State.READY ->
                OverlayBus.updateMoveButton(true, "切到副屏")
            ShizukuBridge.State.NOT_INSTALLED ->
                OverlayBus.updateMoveButton(false, "切到副屏（需 Shizuku）")
            ShizukuBridge.State.NOT_RUNNING ->
                OverlayBus.updateMoveButton(false, "切到副屏（启动 Shizuku）")
            ShizukuBridge.State.NO_PERMISSION ->
                OverlayBus.updateMoveButton(false, "切到副屏（需授权）")
        }
    }

    /**
     * 执行云端显式下发的“关闭弹窗”：端侧在当前页面找安全关闭按钮，
     * 找到就点、找不到（或涉及授权/支付）就不操作，结果交回云端。
     * @return 回灌给模型、写进结果列表的一行
     */
    /** 一次"按本地规则关弹窗"的结果 */
    private data class DismissOutcome(val acted: Boolean, val note: String)

    /**
     * 模型显式下发 `dismiss_dialog` 时走这条：结论一律回灌给模型。
     */
    private suspend fun handleDismissDialog(): String = dismissByLocalRules("云端").note

    /**
     * 按本地规则找无副作用的关闭按钮并点掉。
     *
     * ## 为什么端侧可以自己决定这件事
     *
     * 这个动作**不产生任何任务进展**，只把挡路的东西挪开。原来必须
     * 「模型看到弹窗 → 下发 dismiss_dialog → 再看到清理后的页面」——
     * 一来一回是整整一轮网络 + 模型推理（通常 1~3 秒），
     * 而这一步要做的判断本地全都有：词表和安全闸本来就在 [LocalRuleEngine] 里，
     * 端侧执行和模型下发走的是**同一个函数**，不是两套逻辑。
     *
     * 安全闸一条都没松：授权/支付页不碰、带副作用词的不点、
     * 通用关闭词只在稀疏页面才动。端侧**依然不自主推进任务** ——
     * 它只负责把挡路的挪开，做什么仍旧由模型决定。
     *
     * @param source 只用于日志："云端"= 模型要求的，"端侧"= 每步开头主动清场
     */
    private suspend fun dismissByLocalRules(source: String): DismissOutcome {
        OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
        val nodes = controller.parseNodes()
        val r = localRuleEngine.findSafeDismiss(nodes)
        val target = r.target
            ?: return DismissOutcome(false, "关闭弹窗：${r.reason}（未操作）")

        // 找到安全按钮：非 open_app，执行前先按需让开前台，避免点到纸盒自己
        yieldForegroundIfNeeded()
        val tap = TouchAction(TouchKind.TAP, targetIndex = target.index)
        val mustHide = touchesOverlayButtons(tap)
        if (mustHide) {
            OverlayBus.hide()
            delay(OVERLAY_SETTLE_MS)
        }
        val err = controller.execute(tap) { px, py -> OverlayBus.pulse(px, py) }
        if (mustHide) OverlayBus.show()
        if (err != null) return DismissOutcome(false, "关闭弹窗：点击失败（$err）")

        logger?.line("[$source] ${r.reason}", "弹窗")
        val after = awaitStable(TouchKind.TAP, beforeFp = PageFingerprint.fingerprint(nodes))
        if (after.outcome != WaitOutcome.DONE) {
            return DismissOutcome(false, "关闭弹窗：等待中被中断")
        }
        return DismissOutcome(true, "关闭弹窗：${r.reason}")
    }

    /**
     * 每步读界面**之前**，端侧主动清一遍无副作用的挡路弹窗。
     *
     * 这是"多用本地分析"最划算的一处：命中时直接省掉一整次模型往返，
     * 而且模型看到的是**已经清理干净的页面**，不会为一个广告弹窗浪费一步。
     *
     * 上限 [LOCAL_DISMISS_LIMIT] 是一次任务内的总次数：正常任务遇不到几个弹窗，
     * 真遇到"关掉又弹出来"的循环时，靠它止损，不至于把整趟任务耗在这上面
     * （那种情况下模型多半该换个做法，而不是继续关）。
     *
     * @return 给模型看的一行说明；null = 没动任何东西
     */
    private suspend fun dismissBlockingDialogLocally(): String? {
        if (!settings.localDialogDismiss) return null
        if (localDismissCount >= LOCAL_DISMISS_LIMIT) return null
        if (isStopped()) return null

        val o = dismissByLocalRules("端侧")
        if (!o.acted) return null
        localDismissCount++
        logger?.line(
            "端侧自动关掉挡路弹窗（本次任务第 $localDismissCount 次），模型无需为此花一步",
            "弹窗",
        )
        return o.note
    }

    /**
     * 让开前台（lazy）。
     *
     * 用在"模型要截图"以及"执行非 open_app 动作"之前：纸盒自己还在前台，
     * 模型看到/点到的就是纸盒界面。已经不在前台就什么都不做。全程只让一次。
     */
    private suspend fun yieldForegroundIfNeeded() {
        if (foregroundYielded) return
        val pkg = withContext(Dispatchers.IO) { controller.currentPackage() }
        if (pkg != null && pkg != selfPackage) {
            foregroundYielded = true
            return
        }
        logger?.warn("即将截图 / 操作别的界面，先把纸盒让到后台（回桌面）", "通道")
        pressHomeToYield()
    }

    /**
     * open_app 执行之后确认前台已经切走。
     *
     * open_app 可能因为包名错 / 启动失败而没切走，纸盒还在前台；不补一下，
     * 下一步模型读到的就是纸盒自己的界面、开始点自己。
     */
    private suspend fun verifyLeftAfterOpenApp() {
        if (foregroundYielded) return
        val pkg = withContext(Dispatchers.IO) { controller.currentPackage() }
        if (pkg != null && pkg != selfPackage) {
            foregroundYielded = true
            return
        }
        logger?.warn("open_app 似乎没切到目标应用，补按 HOME 让开", "通道")
        pressHomeToYield()
    }

    /**
     * 按 HOME 把纸盒让到后台，并等界面切走。
     *
     * 等待方式改成了**轮询前台包名**。之前是固定 `delay(600)`：HOME 一般
     * 100~200ms 就生效了，固定等 600ms 每次白扔 400ms —— 而"让开"一趟任务里
     * 要发生很多次（每次截图、每次操作非纸盒界面之前）。
     * [FOREGROUND_YIELD_MS] 保留原值，现在当**上限**用：真卡住了也不会比原来等更久。
     */
    private suspend fun pressHomeToYield() {
        OverlayBus.setPhase(AgentPhase.ACTING)
        withContext(Dispatchers.IO) {
            controller.execute(TAP_HOME) { px, py -> OverlayBus.pulse(px, py) }
        }
        // 标记"自己刚按 HOME"，之后 SELF_HOME_SUPPRESS_MS 内不自动切副屏（0.8.3）
        selfHomeAtMs = System.currentTimeMillis()
        // 按都按了，就算让开过了 —— 后面的让开检查不必再来一遍
        foregroundYielded = true

        var waited = 0L
        while (waited < FOREGROUND_YIELD_MS) {
            val w = awaitWithStop(FOREGROUND_YIELD_POLL_MS)
            if (w != WaitOutcome.DONE) return
            waited += FOREGROUND_YIELD_POLL_MS
            val pkg = controller.currentPackage()
            if (pkg != null && pkg != selfPackage) {
                logger?.line("已让开前台（${pkg}），等了 ${waited}ms", "通道")
                return
            }
        }
        logger?.warn("按 HOME 后 ${FOREGROUND_YIELD_MS}ms 前台还没切走", "通道")
    }

    /** 等待/稳定轮询的结果：正常完成 / 急停 / 通道切换请求（迁移或回迁） */
    private enum class WaitOutcome { DONE, STOPPED, SWITCH_REQUESTED, AUTO_DESKTOP_SWITCH }

    /**
     * 统一的中断判定：急停优先于切换（两者不该同时出现）。
     * 切换请求 = 用户点了「切到副屏」或「切回主屏」。
     */
    private fun interruption(): WaitOutcome = when {
        isStopped() -> WaitOutcome.STOPPED
        OverlayBus.moveToVdRequested || OverlayBus.returnFromVdRequested ->
            WaitOutcome.SWITCH_REQUESTED
        autoDesktopSwitchReady() -> WaitOutcome.AUTO_DESKTOP_SWITCH
        else -> WaitOutcome.DONE
    }

    /**
     * 等待，但可以中途响应急停 / 通道切换。
     *
     * 一次 `delay(10000)` 会让"按了急停却还要等十秒"变成常态，
     * 所以拆成小段轮询；每段都顺手查切换请求，长 sleep 中也能立刻
     * 切到副屏 / 切回主屏（0.8.2）。
     */
    private suspend fun awaitWithStop(ms: Long): WaitOutcome {
        var left = ms
        while (left > 0) {
            val out = interruption()
            if (out != WaitOutcome.DONE) return out
            val chunk = minOf(left, STOP_POLL_MS)
            delay(chunk)
            left -= chunk
        }
        return interruption()
    }

    /** 这个动作要不要做“动作后验证”：第一版只验证最可靠的导航类点击/返回 */
    private fun isVerifiable(kind: TouchKind): Boolean =
        kind == TouchKind.TAP || kind == TouchKind.KEY_BACK

    /**
     * 动作执行并等界面稳定后，比动作前后指纹判断动作有没有生效。
     * - 指纹变了 → 界面变化，动作生效。
     * - 指纹没变 → 可能没点中：导航类动作**重试一次**；有副作用的动作
     *   （发送/支付/下单/确认/删除等）**只上报、绝不重试**，避免一次误判
     *   就重复发送/付款。
     *
     * @return 回灌给模型、写进结果列表的一行
     */
    private suspend fun verifyAction(
        action: TouchAction,
        single: String,
        beforeNodes: List<UiNode>?,
        after: String,
    ): String {
        if (beforeNodes == null) {
            logger?.line("  $single → 已执行", "执行")
            return "$single → 已执行"
        }
        val before = PageFingerprint.fingerprint(beforeNodes)
        if (after != before) {
            logger?.line("  $single → 已执行（界面已变化）", "执行")
            return "$single → 已执行"
        }

        // 界面没变化 → 动作可能没生效
        if (hasSideEffect(action, beforeNodes)) {
            logger?.warn("  $single → 已执行但界面没变化，请确认是否点中（不自动重试）", "执行")
            return "$single → 已执行，但界面没变化，请确认是否点中"
        }

        // 导航类：重试一次
        logger?.warn("  $single → 界面没变化，重试一次", "执行")
        OverlayBus.setPhase(AgentPhase.ACTING)
        val retryError = withContext(Dispatchers.IO) {
            controller.execute(action) { px, py -> OverlayBus.pulse(px, py) }
        }
        if (retryError != null) {
            logger?.error("  $single → 重试失败：$retryError", "执行")
            return "$single → 已执行但界面无变化，可能没点中（重试失败：$retryError）"
        }
        OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
        // 这次等待也可以提前放行：把弹窗那一屏的指纹当"动作前"，点完一看它变了就走
        val r2 = awaitStable(action.kind, beforeFp = before)
        if (r2.outcome != WaitOutcome.DONE) return "$single → 重试中被中断"
        val after2 = r2.fingerprint ?: ""
        return if (after2 != before) {
            logger?.line("  $single → 重试后界面已变化", "执行")
            "$single → 已执行（首次未生效，重试后生效）"
        } else {
            logger?.warn("  $single → 重试后界面仍无变化，可能点不动", "执行")
            "$single → 已执行但界面无变化，可能没点中"
        }
    }

    /**
     * awaitStable 的结果：outcome 区分正常/急停/切换；DONE 时 fingerprint 有效。
     */
    private data class StableResult(
        val outcome: WaitOutcome,
        val fingerprint: String? = null,
    )

    /**
     * 等当前界面稳定，可被急停 / 通道切换打断。
     *
     * ## 判据（这一版改了三处，都是为了少等）
     *
     * **1. 起步期就开始采样，不再先盲等一个固定值。**
     * 动作发出后立刻采第一帧，之后每 [STABLE_POLL_MS] 一帧；只要
     * **观测到**界面变过、且变化之后静止够了，立刻放行。
     * 原来的实现是任何情况都先等满 [STABLE_MIN_MS]（400ms）才采第一帧 ——
     * "点一下就跳页"这种最常见的情况等于白等 400ms。
     * 保守性没丢：**没观测到变化**时仍旧等满 [STABLE_MIN_MS]，
     * 因为那时候分不清是"动作没生效"还是"界面还没来得及开始动"
     * （判据本身在 [WaitPolicy.settled] 里，有单测覆盖）。
     *
     * **2. 采样间隔 200ms → [STABLE_POLL_MS]。**
     * 原来"界面 500ms 就停住了"也得等到 600~800ms 才返回。
     *
     * **3. open_app 用本地判据提前放行。**
     * 传了 [targetPkg] 时，本地直接问系统"前台现在是谁"：目标应用到了前台、
     * 界面也静止够 [OPEN_ARRIVED_SETTLE_MS] 就走，不必等满
     * [OPEN_STABLE_MIN_MS]（冷启动的 1.5s 对"秒开"的应用是纯浪费）。
     * 敢比常规判据更激进，是因为 open_app 那一支**不使用**返回的指纹
     * （动作后验证对它不适用），提前放行的唯一后果是早一点进下一步，
     * 而下一步会重新读界面。
     *
     * @param beforeFp  动作**发出之前**的界面指纹。有它才能判断"界面开始变了没有"；
     *                  没有（首个动作、读不到树）就退回保守判据。
     * @param targetPkg open_app 的目标包名，用来做"到了没有"的本地判断。
     */
    private suspend fun awaitStable(
        kind: TouchKind,
        beforeFp: String? = null,
        targetPkg: String? = null,
    ): StableResult {
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000L

        val openApp = kind == TouchKind.OPEN_APP
        val minFloor = if (openApp) OPEN_STABLE_MIN_MS else STABLE_MIN_MS
        val hardCap = if (openApp) OPEN_STABLE_HARD_MS else STABLE_HARD_MS
        val pollMs = if (openApp) OPEN_STABLE_POLL_MS else STABLE_POLL_MS

        var prev: String? = null
        var seenChange = false
        var lastChangeAt = start
        var arrivedAt = 0L
        // 读控件树的累计耗时/次数。以前完全看不见，而它经常是"这步为什么等了 3 秒"
        // 的真正答案 —— 界面切换中无障碍服务本身很忙，一次读树可能要一秒多，
        // 比我们所有固定等待加起来都大。不把它单列出来就会一直误判成"等待参数没调好"
        var parseMs = 0L
        var parseCount = 0

        while (true) {
            // parseNodes() 自己就切到 IO 线程了，不用再包一层 withContext
            val t0 = System.nanoTime()
            val fp = PageFingerprint.fingerprint(controller.parseNodes())
            parseMs += (System.nanoTime() - t0) / 1_000_000L
            parseCount++
            interruption().let { if (it != WaitOutcome.DONE) return StableResult(it) }

            val now = System.nanoTime()
            val equal = prev != null && fp == prev

            if (beforeFp != null && fp != beforeFp) {
                // 还在持续变化就不断刷新计时；一旦停住，这个值就冻住，
                // [WaitPolicy.settled] 靠"距离最后一次变化过了多久"判断静了没有
                if (fp != prev) lastChangeAt = now
                seenChange = true
            }

            if (equal && WaitPolicy.settled(
                    samplesEqual = true,
                    seenChange = seenChange,
                    msSinceChange = (now - lastChangeAt) / 1_000_000L,
                    elapsedMs = elapsedMs(),
                    minFloorMs = minFloor,
                    settleMs = STABLE_SETTLE_MS,
                )) {
                // 记一行实际等了多久。这不是调试残留 —— "这步为什么慢"是
                // 排查体验时第一个要问的问题，而等待时间以前**完全不可见**
                // （只知道固定的那几个常量，不知道实际走了哪条路）
                logStableCost(
                    kind, elapsedMs(), minFloor,
                    early = elapsedMs() < minFloor,
                    parseMs = parseMs, parseCount = parseCount,
                )
                return StableResult(WaitOutcome.DONE, fp)
            }

            if (targetPkg != null) {
                val fg = controller.currentPackage()
                if (fg == targetPkg) {
                    if (arrivedAt == 0L) arrivedAt = now
                    if (equal && WaitPolicy.arrived(
                            targetPkg = targetPkg,
                            foregroundPkg = fg,
                            samplesEqual = true,
                            msSinceArrival = (now - arrivedAt) / 1_000_000L,
                            arrivedSettleMs = OPEN_ARRIVED_SETTLE_MS,
                        )) {
                        logStableCost(
                            kind, elapsedMs(), minFloor, early = true,
                            parseMs = parseMs, parseCount = parseCount,
                        )
                        return StableResult(WaitOutcome.DONE, fp)
                    }
                } else {
                    arrivedAt = 0L
                }
            }

            prev = fp
            // 一直不稳定（时钟/进度条每秒变字）→ hardCap 兜底，返回当前指纹
            val left = hardCap - elapsedMs()
            if (left <= 0) {
                logStableCost(
                    kind, elapsedMs(), minFloor, early = false,
                    parseMs = parseMs, parseCount = parseCount,
                )
                return StableResult(WaitOutcome.DONE, fp)
            }
            val w = awaitWithStop(minOf(pollMs, left))
            if (w != WaitOutcome.DONE) return StableResult(w)
        }
    }

    /**
     * 记一行"这步等界面稳定等了多久"。
     *
     * [early] = 走的是"看到界面变过又停住"的提前放行，而不是保守兜底。
     * 前者是新加的路，也是等待时间下降的来源 —— 分不清两者就没法判断
     * 优化到底有没有生效。
     *
     * [parseMs]/[parseCount] 单列读树的成本：它是**唯一可能吃掉几秒**的项，
     * 界面切换中无障碍服务很忙，一次读树能到一秒多。不写出来，
     * 看到"等了 3 秒"只会去怀疑等待参数，而参数其实没毛病。
     */
    private fun logStableCost(
        kind: TouchKind,
        elapsedMs: Long,
        minFloorMs: Long,
        early: Boolean,
        parseMs: Long,
        parseCount: Int,
    ) {
        val what = if (kind == TouchKind.OPEN_APP) "等应用起来" else "等界面稳定"
        val why = if (early) "（看到界面变过，未等满兜底 ${minFloorMs}ms）" else ""
        val read = if (parseCount > 0) {
            "，其中读控件树 ${parseMs}ms / $parseCount 次"
        } else {
            ""
        }
        logger?.line("$what ${elapsedMs}ms$why$read", "等待")
    }

    private suspend fun fail(message: String, label: String) {
        logger?.error(message, label)
        listener.onEvent(EventKind.ERROR, message, label)
        finish(false, message)
    }

    private suspend fun finish(success: Boolean, message: String) {
        // 无条件清掉挂起的通道切换请求，兜住所有终止路径（0.8.2 审阅加固）
        OverlayBus.clearMoveToVirtualDisplay()
        OverlayBus.clearReturnFromVd()

        // 收尾：只要还在副屏、或建过副屏（哪怕迁移失败提前返回），都销毁，不留残屏（0.8.0 验收⑤）
        if (controller.isVirtualDisplay || vdCreatedByAgent) {
            if (controller.isVirtualDisplay) controller.exitVirtualDisplay()
            val dr = ShizukuBridge.destroyDisplay(controller.appContext)
            vdCreatedByAgent = false
            logger?.line(
                if (dr >= 0) "副屏已随任务结束销毁" else "副屏销毁没成功（$dr），可到副屏调试页手动销毁",
                "副屏",
            )
        }
        OverlayBus.setPhase(AgentPhase.IDLE)
        val total = promptTokens + completionTokens
        val cacheTotal = cacheHitTokens + cacheMissTokens
        val hitRate = if (cacheTotal > 0) {
            "%.0f%%".format(cacheHitTokens * 100.0 / cacheTotal)
        } else {
            "无数据"
        }
        logger?.line(
            "本次共用 token：输入 $promptTokens / 输出 $completionTokens / 合计 $total",
            "统计",
        )
        logger?.line(
            "上下文缓存：命中 $cacheHitTokens / 未命中 $cacheMissTokens（命中率 $hitRate）",
            "统计",
        )
        logger?.line("结束：$message", "任务")
        logger?.close()
        listener.onFinished(success, message)
    }

    /** 状态卡上显示的一行：单个动作直接写，一批就写第一个 + 总数 */
    private fun overlayText(actions: List<TouchAction>): String = when (actions.size) {
        0 -> "准备中 ..."
        1 -> AgentPrompt.describe(actions[0])
        else -> "${AgentPrompt.describe(actions[0])} 等 ${actions.size} 个动作"
    }

    /**
     * 这个动作的路径会不会压到底部按钮面板（「切到副屏」+「急停」）上。
     *
     * 面板只占底部中间一小块，绝大多数点击都碰不到它 ——
     * 所以大多数步骤里悬浮窗可以一直留着，用户能看见"正在操作手机"。
     */
    private fun touchesOverlayButtons(action: TouchAction): Boolean {
        if (OverlayBus.overlapsOverlayButtons(action.x, action.y)) return true
        // 滑动/拖拽/甩动要连终点一起看，路径可能横穿面板
        return when (action.kind) {
            TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG ->
                OverlayBus.overlapsOverlayButtons(action.x2, action.y2)
            else -> false
        }
    }

    /** 三个停止来源：Agent 自己的标志、宿主界面的、悬浮窗按钮的 */
    private fun isStopped(): Boolean =
        stopRequested || listener.isStopRequested() || OverlayBus.stopRequested

    /** 动作签名，用来识别"反复做同一件事" */
    private fun actionSignature(a: TouchAction): String =
        "${a.kind}|${a.targetIndex}|${a.x},${a.y}|${a.x2},${a.y2}|${a.durationMs}|${a.direction}"

    private fun md5(bytes: ByteArray): Int =
        MessageDigest.getInstance("MD5").digest(bytes).contentHashCode()

    companion object {
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

        // ---- 0.8.3 自动切副屏 ----
        /** 回到桌面后持续多久才认定是"用户真的回桌面"（防抖） */
        const val DESKTOP_DEBOUNCE_MS = 900L
        /** 目标 app 多少毫秒内还在前台才算"够新"，防止拿陈旧目标乱迁 */
        const val TARGET_FRESH_MS = 30_000L
        /** Agent 自己按 HOME 后多久内不自动触发（覆盖让开/补 HOME 过渡） */
        const val SELF_HOME_SUPPRESS_MS = 1_500L
        /** 自动迁移失败后的冷却，避免停在桌面被反复尝试 */
        const val AUTO_FAIL_COOLDOWN_MS = 10_000L

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
         * 卡死的硬上限。
         *
         * 2 次是"提醒模型换做法"，到 5 次就说明换做法也没用 —— 再跑下去
         * 纯粹是烧 token。步数上限现在是"不限"，所以这条兜底必须存在。
         */
        const val STUCK_LIMIT = 5

        /** `OverlayBus.returnFromVdReason` 的"用户手动"取值，用来区分要不要进冷却（批 3） */
        const val RETURN_REASON_MANUAL = OverlayBus.REASON_MANUAL

        /**
         * 「自动回迁」成功后的静默期（批 3，防乒乓）。
         *
         * 30 秒是 HANDOFF §9.4 定的：足够长到用户点开纸盒看一眼、再自己按 HOME
         * 回桌面时不会立刻又被搬回去；又短到"用户真的想继续在副屏上跑"时
         * 不至于等太久。**只压自动**，手动按悬浮钮不受影响。
         */
        const val AUTO_RETURN_COOLDOWN_MS = 30_000L

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
         * 「用户把应用挪回主屏」要连续命中几次才认定（副屏模式，批 3 附加项）。
         *
         * 2 次是为了滤掉切通道瞬间"两边都没有"的中间态；代价是最多白跑一步。
         */
        const val TAKEOVER_STRIKES = 2

        /**
         * 判定「用户接管」之后，多久内不再自动把应用搬去副屏。
         *
         * 一拉回来就被搬走、再拉回来再被搬走，来回打乒乓比不搬更烦人。
         * 一分钟够用户把手上那点事做完；真要搬，悬浮窗上的「切到副屏」随时能按
         * （那个按钮不受这条冷却影响）。
         */
        const val TAKEOVER_COOLDOWN_MS = 60_000L

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
}
