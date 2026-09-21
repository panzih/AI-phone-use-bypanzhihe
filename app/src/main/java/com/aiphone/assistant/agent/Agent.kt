package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.RunLogger
import com.aiphone.assistant.overlay.AgentPhase
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.skill.SkillRegistry
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 一张 1080x2400 的 PNG base64 之后一两兆，是整条链路里最贵的东西。
 * 控件树已经能给出精确的元素编号，截图只在"这一屏说不清是什么"时
 * 才有价值 —— 所以改成由模型自己用 `need_image` 要。
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

    /** 只读快照。带图的消息把图去掉：下一次任务没法原样重发它 */
    val finalHistory: List<ChatTurn>
        get() = synchronized(allHistory) {
            allHistory.map { ChatTurn(role = it.role, text = it.text) }
        }

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
     * 端侧意图执行器（无状态）。模型显式下发 dismiss_dialog 时，
     * 用它在当前页面找安全关闭按钮；端侧不做自主前置决策。
     */
    private val localRuleEngine = LocalRuleEngine()

    suspend fun run(task: String) {
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
        val system = AgentPrompt.system(skills.catalog(), memorySnapshot)
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

            ask@ while (true) {
                if (isStopped()) {
                    finish(false, "你停止了任务（第 $step 步，模型调用前）")
                    return
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
                val result = withContext(Dispatchers.IO) {
                    llm.chat(system, history) {
                        // 请求体传完了，接下来是等服务端算
                        OverlayBus.setPhase(AgentPhase.WAITING_MODEL)
                    }
                }
                val elapsed = System.currentTimeMillis() - callStart

                when (result) {
                    is LlmResult.Fail -> {
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
                        if (p.finished) {
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
            for ((i, action) in p.actions.withIndex()) {
                if (isStopped()) {
                    finish(false, "你停止了任务（执行第 $step 步第 ${i + 1} 个动作之前）")
                    return
                }

                val single = AgentPrompt.describe(action)
                OverlayBus.update(step, maxSteps, overlayText(listOf(action)), p.nextHint)
                val next = p.actions.getOrNull(i + 1)

                // sleep 由这里自己做（协程 delay，可中途急停），不走通道 ——
                // 通道里的 Thread.sleep 会把整个线程按住。
                if (action.kind == TouchKind.WAIT) {
                    OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                    logger?.line("  $single", "执行")
                    val completed = awaitWithStop(action.durationMs.toLong())
                    results.add(if (completed) "$single → 已执行" else "$single → 被中断")
                    if (!completed) {
                        finish(false, "你停止了任务（等待中被叫停）")
                        return
                    }
                } else if (action.kind == TouchKind.DISMISS_DIALOG) {
                    // 云端显式下发：端侧在当前页面找安全关闭按钮，找到就点、
                    // 找不到（或涉及授权/支付）就不操作；然后立刻把控制权交回
                    // 云端，本批后续预排动作暂不执行（break）
                    logger?.line("  $single", "执行")
                    results.add(handleDismissDialog())
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
                    val mustHide = touchesStopButton(action)
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
                        val after = awaitStable(TouchKind.OPEN_APP)
                        if (after == null) {
                            finish(false, "你停止了任务（等待中被叫停）")
                            return
                        }
                        // 启动后仍没切走（包名错/启动失败）才补 HOME，
                        // 避免下一步模型读到纸盒自己的界面。
                        verifyLeftAfterOpenApp()
                        logger?.line("  $single → 已执行", "执行")
                        results.add("$single → 已执行")
                    } else if (next?.kind == TouchKind.WAIT) {
                        // 下一个动作就是显式 sleep，由它去等，这里不再补等待
                        logger?.line("  $single → 已执行", "执行")
                        results.add("$single → 已执行")
                    } else {
                        // 等界面稳定（最后一个动作也等，等价原来的一批收尾），
                        // 拿到稳定指纹后做动作后验证、必要时一次重试
                        OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                        val after = awaitStable(action.kind)
                        if (after == null) {
                            finish(false, "你停止了任务（等待中被叫停）")
                            return
                        }
                        results.add(verifyAction(action, single, beforeNodes, after))
                    }
                }
            }

            val resultText = when (results.size) {
                0 -> "没有动作被执行"
                1 -> results[0]
                else -> "共 ${results.size} 个动作：" + results.joinToString("；")
            }
            logger?.recordStep(
                step = step,
                thought = p.thought,
                action = desc,
                result = resultText,
                rawModelOutput = modelOutput,
                shot = null,
            )

            // ---- 回灌给模型 ----
            history.add(ChatTurn(ChatTurn.ASSISTANT, modelOutput))
            lastResult = resultText
        }

        finish(
            false,
            "到了最大步数 $maxSteps 还没做完。可以加大步数，或者把任务拆小一点。",
        )
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 执行云端显式下发的“关闭弹窗”：端侧在当前页面找安全关闭按钮，
     * 找到就点、找不到（或涉及授权/支付）就不操作，结果交回云端。
     * @return 回灌给模型、写进结果列表的一行
     */
    private suspend fun handleDismissDialog(): String {
        OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
        val nodes = withContext(Dispatchers.IO) { controller.parseNodes() } ?: emptyList()
        val r = localRuleEngine.findSafeDismiss(nodes)
        val target = r.target
            ?: return "关闭弹窗：${r.reason}（未操作）"

        // 找到安全按钮：非 open_app，执行前先按需让开前台，避免点到纸盒自己
        yieldForegroundIfNeeded()
        val tap = TouchAction(TouchKind.TAP, targetIndex = target.index)
        val mustHide = touchesStopButton(tap)
        if (mustHide) {
            OverlayBus.hide()
            delay(OVERLAY_SETTLE_MS)
        }
        val err = withContext(Dispatchers.IO) {
            controller.execute(tap) { px, py -> OverlayBus.pulse(px, py) }
        }
        if (mustHide) OverlayBus.show()
        if (err != null) return "关闭弹窗：点击失败（$err）"

        val after = awaitStable(TouchKind.TAP)
        if (after == null) return "关闭弹窗：等待中被中断"
        return "关闭弹窗：${r.reason}"
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

    /** 按 HOME 把纸盒让到后台，并等界面切走。 */
    private suspend fun pressHomeToYield() {
        OverlayBus.setPhase(AgentPhase.ACTING)
        withContext(Dispatchers.IO) {
            controller.execute(TAP_HOME) { px, py -> OverlayBus.pulse(px, py) }
        }
        // 临时值（固定等待），等 A1 / awaitStable 事件驱动落地后替换
        delay(FOREGROUND_YIELD_MS)
        foregroundYielded = true
    }

    /**
     * 等待，但可以中途响应急停。
     *
     * 一次 `delay(10000)` 会让"按了急停却还要等十秒"变成常态，
     * 所以拆成小段轮询。@return false 表示被叫停
     */
    private suspend fun awaitWithStop(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (isStopped()) return false
            val chunk = minOf(left, STOP_POLL_MS)
            delay(chunk)
            left -= chunk
        }
        return !isStopped()
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
        val after2 = awaitStable(action.kind)
        if (after2 == null) return "$single → 重试中被中断"
        return if (after2 != before) {
            logger?.line("  $single → 重试后界面已变化", "执行")
            "$single → 已执行（首次未生效，重试后生效）"
        } else {
            logger?.warn("  $single → 重试后界面仍无变化，可能点不动", "执行")
            "$single → 已执行但界面无变化，可能没点中"
        }
    }

    /**
     * 等当前界面稳定，可被急停打断。
     *
     * 判据：`max(最小起步, 连续两次指纹相同)` 且不超过 hardCap —— 动作刚
     * 发出时界面还没开始变，立刻采样会误判“已稳定”，所以先等一个最小起步；
     * 之后每 [STABLE_POLL_MS] 取一次控件树算指纹，连续两次相同就认为界面
     * 停了。时钟/进度条这类每秒变字的页面可能永远不稳定，hardCap 是唯一
     * 出口。全程走 [awaitWithStop]，急停能立刻打断。
     *
     * @return 稳定（或到 hardCap）时的页面指纹；null 表示被急停叫停
     */
    private suspend fun awaitStable(kind: TouchKind): String? {
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000L

        val (minFloor, hardCap) = when (kind) {
            TouchKind.OPEN_APP -> OPEN_STABLE_MIN_MS to OPEN_STABLE_HARD_MS
            else -> STABLE_MIN_MS to STABLE_HARD_MS
        }

        // 最小起步（可急停），随后采第一次指纹
        if (!awaitWithStop(minFloor)) return null
        var fp = PageFingerprint.fingerprint(
            withContext(Dispatchers.IO) { controller.parseNodes() }
        )
        if (isStopped()) return null

        while (true) {
            // 轮询间隔（不越过 hardCap），再采样比对
            val waitMs = minOf(STABLE_POLL_MS, hardCap - elapsedMs())
            if (!awaitWithStop(waitMs)) return null

            val newFp = PageFingerprint.fingerprint(
                withContext(Dispatchers.IO) { controller.parseNodes() }
            )
            if (isStopped()) return null
            // 连续两次指纹相同 → 稳定
            if (newFp == fp) return newFp
            fp = newFp
            // 一直不稳定 → hardCap 兜底，返回当前指纹
            if (elapsedMs() >= hardCap) return fp
        }
    }

    private fun fail(message: String, label: String) {
        logger?.error(message, label)
        listener.onEvent(EventKind.ERROR, message, label)
        finish(false, message)
    }

    private fun finish(success: Boolean, message: String) {
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
     * 这个动作的路径会不会压到底部急停按钮上。
     *
     * 按钮只占底部中间一小块，绝大多数点击都碰不到它 ——
     * 所以大多数步骤里悬浮窗可以一直留着，用户能看见"正在操作手机"。
     */
    private fun touchesStopButton(action: TouchAction): Boolean {
        if (OverlayBus.overlapsStopButton(action.x, action.y)) return true
        // 滑动/拖拽/甩动要连终点一起看，路径可能横穿按钮
        return when (action.kind) {
            TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG ->
                OverlayBus.overlapsStopButton(action.x2, action.y2)
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
         * 按 HOME 让开后、等界面切走的时间。
         *
         * ⚠️ 临时值（固定等待），等 A1 / awaitStable 事件驱动落地后替换。
         */
        const val FOREGROUND_YIELD_MS = 600L

        /** 等待时检查急停的间隔 */
        const val STOP_POLL_MS = 100L

        /**
         * 普通动作后等界面稳定的参数：
         * - 最小起步：动作刚发出界面还没开始变，立刻采样会误判“已稳定”，
         *   先等这么久给界面开始变化的时间。
         * - 硬上限：时钟/进度条这类每秒变字的页面可能永远不稳定，到点就放行。
         * - 轮询间隔：每次取控件树是一次 binder 调用，不宜更短。
         */
        const val STABLE_MIN_MS = 400L
        const val STABLE_HARD_MS = 1200L
        const val STABLE_POLL_MS = 200L

        /**
         * 打开应用（冷启动）的稳定参数：最小起步更长 —— 启动页常常整段
         * 不在控件树里，指纹会误判“已稳定”；硬上限也相应放宽。
         */
        const val OPEN_STABLE_MIN_MS = 1500L
        const val OPEN_STABLE_HARD_MS = 2500L

        /**
         * 卡死的硬上限。
         *
         * 2 次是"提醒模型换做法"，到 5 次就说明换做法也没用 —— 再跑下去
         * 纯粹是烧 token。步数上限现在是"不限"，所以这条兜底必须存在。
         */
        const val STUCK_LIMIT = 5

        /** 连续几次解析不出动作就停 */
        const val MAX_PARSE_FAILS = 5

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
