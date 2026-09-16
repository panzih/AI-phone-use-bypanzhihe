package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
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
 * 模型一次给出若干个动作，中间自动补默认间隔（[AgentPrompt.DEFAULT_GAP_MS]）；
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
 * 无障碍读的是当前活动窗口，控制应用自己在前台时读到的是纸盒自己的界面，
 * 模型会对着它操作。所以循环开始前会检查前台包名，是我们就先按一下回桌面。
 * 另外控件树解析时会**按包名过滤掉我们自己的节点**，双保险。
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

        // ---- 2. 别拍到自己 ----
        ensureNotSelfForeground()

        // ---- 3. 开跑 ----
        val system = AgentPrompt.system(skills.catalog())
        val history = mutableListOf<ChatTurn>()

        var lastTreeHash = 0
        var sameTree = 0
        var lastSig = ""
        var repeatAction = 0

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
            var pendingImage: ByteArray? = null
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

                // ⚠️ 必须把**实际发出去的这条文本**原样追加进 history。
                // 发一套记另一套会让前缀从那一轮断开，缓存命中率恒为 0。
                history.add(ChatTurn(ChatTurn.USER, userText))

                listener.onEvent(EventKind.THOUGHT, "正在请求模型 ...", "第 $step 步")
                val callStart = System.currentTimeMillis()
                OverlayBus.setPhase(AgentPhase.UPLOADING)
                val result = withContext(Dispatchers.IO) {
                    llm.chat(system, history, pendingImage) {
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
                        logger?.section("模型原始输出")
                        result.text.lines().forEach { logger?.line("  $it") }

                        val p = ActionParser.parse(result.text, w, h)
                        p.thought?.let {
                            logger?.line("思考：$it", "模型")
                            listener.onEvent(EventKind.THOUGHT, it, "第 $step 步 · 思考")
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

                            OverlayBus.setPhase(AgentPhase.SCREENSHOT)
                            OverlayBus.hide()
                            delay(OVERLAY_SETTLE_MS)
                            val shot = withContext(Dispatchers.IO) { controller.captureFrame() }
                            OverlayBus.show()

                            if (shot == null || shot.isEmpty()) {
                                logger?.error("截图失败，只能靠控件树继续", "截图")
                                imageNote = "截图取不到（可能是安全页面、或者系统限流），只能靠界面元素判断。"
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
                            logger?.warn(
                                "模型又要截图，但这一轮已经给过 $MAX_IMAGE_REQUESTS 次了，先按它给的动作走",
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
                            logger?.warn(
                                "模型又要调技能，但这一轮已经调过 $MAX_SKILL_CALLS 次了，先按它给的动作走",
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
                logger?.warn("解析失败：$warning", "解析")
                listener.onEvent(EventKind.ERROR, warning, "解析失败")
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

            // ---- 逐个执行 ----
            val results = ArrayList<String>(p.actions.size)
            for ((i, action) in p.actions.withIndex()) {
                if (isStopped()) {
                    finish(false, "你停止了任务（执行第 $step 步第 ${i + 1} 个动作之前）")
                    return
                }

                val single = AgentPrompt.describe(action)
                OverlayBus.update(step, maxSteps, overlayText(listOf(action)), p.nextHint)

                // sleep 由这里自己做（用协程 delay，可以中途响应急停），
                // 不走通道 —— 通道里的 Thread.sleep 会把整个线程按住。
                if (action.kind == TouchKind.WAIT) {
                    OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                    logger?.line("  $single", "执行")
                    val completed = awaitWithStop(action.durationMs.toLong())
                    results.add(if (completed) "$single → 已执行" else "$single → 被中断")
                    if (!completed) {
                        finish(false, "你停止了任务（等待中被叫停）")
                        return
                    }
                } else {
                    // 注入前**只在真会撞上急停按钮时**才藏。
                    // 状态卡本身是 FLAG_NOT_TOUCHABLE，永远不会吃点击，
                    // 所以只需要担心按钮那一小块。
                    OverlayBus.setPhase(AgentPhase.ACTING)
                    val mustHide = touchesStopButton(action)
                    if (mustHide) {
                        OverlayBus.hide()
                        delay(OVERLAY_SETTLE_MS)
                    }
                    val execResult = withContext(Dispatchers.IO) { controller.execute(action) }
                    if (mustHide) {
                        OverlayBus.show()
                    }

                    if (execResult == null) {
                        logger?.line("  $single → 已执行", "执行")
                        results.add("$single → 已执行")
                    } else {
                        logger?.error("  $single → $execResult", "执行")
                        listener.onEvent(EventKind.ERROR, execResult, "第 $step 步 · 失败")
                        results.add("$single → 失败：$execResult")
                    }
                }

                // ---- 和下一个动作之间的间隔 ----
                val gap = gapBetween(action, p.actions.getOrNull(i + 1))
                if (gap > 0) {
                    OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                    if (!awaitWithStop(gap.toLong())) {
                        finish(false, "你停止了任务（等待中被叫停）")
                        return
                    }
                }
            }

            // 一批做完，进入下一轮之前再等一次（模型显式 sleep 结尾就不用等）
            val last = p.actions.last()
            if (last.kind != TouchKind.WAIT) {
                OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                if (!awaitWithStop(gapForKind(last.kind).toLong())) {
                    finish(false, "你停止了任务（等待中被叫停）")
                    return
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
     * 让开前台。
     *
     * 无障碍读的是当前活动窗口，如果纸盒自己在前台，
     * 模型看到的就是纸盒的界面 —— 它会开始点自己的按钮。
     */
    private suspend fun ensureNotSelfForeground() {
        val pkg = withContext(Dispatchers.IO) { controller.currentPackage() } ?: return
        if (pkg != selfPackage) return
        logger?.warn("控制应用自己在前台，先把界面让出来（回桌面）", "通道")
        withContext(Dispatchers.IO) { controller.execute(TAP_HOME) }
        delay(800)
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

    /** 两个动作之间补多久 */
    private fun gapBetween(current: TouchAction, next: TouchAction?): Int {
        // 一批的最后一个动作由循环外的收尾等待负责 —— 这里再补一次就成了等两遍
        if (next == null) return 0
        // 刚等过 / 下一个就是显式 sleep —— 都不再补默认间隔
        if (current.kind == TouchKind.WAIT) return 0
        if (next.kind == TouchKind.WAIT) return 0
        return gapForKind(current.kind)
    }

    /**
     * 默认间隔。
     *
     * 只有"打开应用"特殊：冷启动明显比界面切换慢，1.5 秒经常不够，
     * 而模型很难预判这一点。其余一律 1.5 秒，不够就让模型自己写 sleep。
     */
    private fun gapForKind(kind: TouchKind): Int = when (kind) {
        TouchKind.OPEN_APP -> AgentPrompt.OPEN_APP_GAP_MS
        else -> AgentPrompt.DEFAULT_GAP_MS
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

    private companion object {
        val TAP_HOME = TouchAction(kind = TouchKind.KEY_HOME)

        /**
         * 藏完悬浮窗后等一小会儿再截图/注入。
         *
         * 隐藏是异步的（窗口变更要经过 WindowManager 和 SurfaceFlinger
         * 才真正生效），立刻截图有可能拍到还没消失的那一帧。
         * 100ms 对 60Hz 来说是 6 帧，足够。
         */
        const val OVERLAY_SETTLE_MS = 100L

        /** 等待时检查急停的间隔 */
        const val STOP_POLL_MS = 100L

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
