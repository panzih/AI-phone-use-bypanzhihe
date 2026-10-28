package com.aiphone.assistant.agent

import com.aiphone.assistant.agent.Agent.EventKind
import com.aiphone.assistant.agent.AgentLimits.MAX_IMAGE_REQUESTS
import com.aiphone.assistant.agent.AgentLimits.MAX_SKILL_CALLS
import com.aiphone.assistant.agent.AgentLimits.MAX_TOTAL_IMAGES
import com.aiphone.assistant.agent.AgentLimits.OVERLAY_SETTLE_MS
import com.aiphone.assistant.agent.AgentLimits.OVER_LIMIT_MAX_RETRY
import com.aiphone.assistant.agent.AgentLimits.STOP_POLL_MS
import com.aiphone.assistant.channel.UiTreeRead
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.overlay.AgentPhase
import com.aiphone.assistant.overlay.OverlayBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 问模型阶段的结果：要么拿到一批可执行动作，要么已经收尾。 */
internal sealed interface AskResult {
    data class Ready(
        val parsed: ActionParser.Parsed,
        val modelOutput: String,
    ) : AskResult

    data object Stop : AskResult
}

/**
 * 问模型：截图/技能请求、急停、重试、防幻觉完成都在这里闭环。
 *
 * 从 [AgentSession.run] 的 500 多行 ask 循环原样抽出，行为不变；
 * 外层只关心“拿到动作”或“已经结束”。
 */
internal suspend fun AgentSession.askModel(
    step: Int,
    task: String,
    w: Int,
    h: Int,
    tree: String?,
    treeRead: UiTreeRead,
    foreground: String?,
    history: MutableList<ChatTurn>,
    system: String,
    treeObservable: Boolean,
    initialInterruptions: String,
): AskResult {

    var interruptions = initialInterruptions

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
     *
     * ⚠️ 例外：**前台就是纸盒自己**时不带图。那种情况下没有元素列表
     * 是因为我们按包名把自己的节点过滤掉了，不是"看不到界面" ——
     * 提示词里已经写清"用 open_app 打开目标应用"，再附一张纸盒
     * 自己的截图纯属浪费（每个任务的第一步都会白花一张全分辨率图）。
     */
    val selfForeground = foreground != null && foreground == selfPackage
    val autoImage: ByteArray? = if (tree == null && !selfForeground) {
        withContext(Dispatchers.IO) { controller.captureFrame() }
    } else {
        null
    }
    if (autoImage != null) {
        logger?.line(
            "读不到控件树，本轮自动带上截图（${autoImage.size} 字节）",
            "截图",
        )
    } else if (controller.isVirtualDisplay) {
        logger?.warn(
            "副屏现在没有画面（连截图都抓不到）：多半是还没有应用切到副屏上",
            "副屏",
        )
    }
    // 副屏 + 这一屏没有控件树 = 模型手里可能**一点信息都没有**
    // （若连截图也抓不到）。这种情况必须告诉它"先开应用"，
    // 否则它会拿历史里的主屏布局瞎点 —— 2026-10-22 AVD 实测：
    // 副屏第一步就是这么把任务直接判死的。

    var pendingImage: ByteArray? = autoImage
    var imageNote: String? = null
    var skillNote: String? = null

    // ---- 上一步 capture 截到的图：和这步的界面元素一起发 ----
    if (pendingActionShot != null) {
        val shot = pendingActionShot!!
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
        // 暂停点②：发请求之前（模型要图/要技能会在这里绕回，也停得住）
        if (!awaitResume(step)) {
            finish(false, "你停止了任务（第 $step 步，模型调用前）")
            return AskResult.Stop
        }

        // 没有元素列表时要说清是**哪一种**没有 —— 这四种情况模型该做的事
        // 完全不同（副屏 / 无障碍没工作 / 纸盒自己在前台 / 这一屏真没控件），
        // 所以由 Agent 判断后传进来；不传时用 DEFAULT_NO_TREE_REASON。
        //
        // ⚠️ 必须先判"读不到根节点"：以前这一支永远走不到，因为无障碍
        // 通道在没有元素时返回的是一句占位语而不是 null，`tree == null`
        // 永远不成立 —— 于是"无障碍没工作"被当成了"界面自绘"。
        val noTreeReason = if (tree != null) {
            null
        } else {
            when {
                // ⚠️ 副屏必须排在"读不到根节点"**前面**：
                // 副屏本来就拿不到控件树（rootAvailable 恒 false），
                // 排在后面的话模型会收到"无障碍服务没真正生效"——
                // 那是瞎报，还会把它引导成"直接 failed"。
                // 2026-10-22 在 AVD 上实测到：副屏任务第一步就被这么误导。
                controller.isVirtualDisplay -> AgentPrompt.NO_TREE_VIRTUAL_DISPLAY
                !treeRead.rootAvailable -> AgentPrompt.NO_TREE_UNREADABLE
                foreground != null && foreground == selfPackage -> AgentPrompt.NO_TREE_SELF
                else -> AgentPrompt.NO_TREE_NO_ELEMENTS
            }
        }

        // 暂停期间用户补的话：拼进**这一条** user 消息（不单独发一条，
        // 否则对话就不算严格追加，缓存前缀从这里断开）
        val userNote = OverlayBus.drainUserNotes().takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
        if (userNote != null) {
            logger?.line("用户补充指令：$userNote", "暂停")
            listener.onEvent(EventKind.THOUGHT, "收到补充指令：$userNote", "暂停")
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
            userNote = userNote,
        )

        // ⚠️ 必须把**实际发出去的这条消息**原样追加进 history ——
        // 包括它带的那张图。图片如果只挂在"最后一条 user"上，
        // 同一条消息在两次请求里会一次带图一次不带，
        // 前缀从第一条 user 就分叉，缓存等于没有。
        //
        // 现在整条对话严格递增、每个字节都可复现：
        //     [system][user1(+图)][assistant1][user2(+图)]...
        history.add(ChatTurn(ChatTurn.USER, userText, pendingImage))
        // 原样落盘：导出包里要能看到"我们问了什么"，而不只是模型答了什么
        logger?.recordPrompt(step, userText)

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
                    return AskResult.Stop
                }
                logger?.error("模型调用失败：${result.message}", "模型")
                fail(result.message, "模型出错")
                return AskResult.Stop
            }

            is LlmResult.Ok -> {
                promptTokens += result.promptTokens
                completionTokens += result.completionTokens
                cacheHitTokens += result.cacheHitTokens
                cacheMissTokens += result.cacheMissTokens

                // 状态条指标行：命中率 / 首字节耗时 / 累计 token / 上下文占用。
                //
                // 「命中」用**本轮**而不是累计值 —— 用户要判断的是
                // "刚才这次请求贵不贵"；「总token」用**累计** ——
                // 用户问的是"这一趟花了多少"，只看本轮看不出量级。
                val stepCacheTotal = result.cacheHitTokens + result.cacheMissTokens
                OverlayBus.updateMetrics(
                    hitPercent = if (stepCacheTotal > 0) {
                        result.cacheHitTokens * 100.0 / stepCacheTotal
                    } else {
                        null
                    },
                    ttftMs = llm.lastTtftMs,
                    // 上下文占用：服务端给的真实 prompt_tokens ÷ 窗口上限。
                    // 用真实值而不是本地估算 —— 估算会把图片/系统提示词的
                    // token 数算偏，占用百分比跟着失真。
                    contextPercent = if (result.promptTokens > 0) {
                        (result.promptTokens * 100 / contextBudget.window).coerceIn(0, 100)
                    } else {
                        null
                    },
                    totalTokens = promptTokens + completionTokens,
                )

                // ---- 上下文长度兜底 ----
                // 上下文不裁剪（缓存按前缀匹配），所以得防着撑爆模型窗口。
                // 用服务端返回的真实 prompt_tokens，比本地估算准。
                if (result.promptTokens >= contextBudget.stopTokens) {
                    val msg = "上下文已经 ${result.promptTokens} token（模型窗口 ${contextBudget.window}），" +
                        "再发就要顶到上限了，先停下。开个新任务继续吧。"
                    logger?.error(msg, "上下文")
                    listener.onEvent(EventKind.ERROR, msg, "上下文过长")
                    finish(false, msg)
                    return AskResult.Stop
                }
                if (result.promptTokens >= contextBudget.warnTokens) {
                    logger?.warn(
                        "上下文已到 ${result.promptTokens} token（窗口 ${contextBudget.window}），快满了",
                        "上下文",
                    )
                }
                logger?.line(
                    "模型返回 ${elapsed}ms，token：输入 ${result.promptTokens} / " +
                        "输出 ${result.completionTokens}" +
                        "（缓存命中 ${result.cacheHitTokens} / " +
                        "未命中 ${result.cacheMissTokens}）" +
                        if (pendingImage != null) "（本轮带图）" else "",
                    "模型",
                )
                // ---- 命中自检 ----
                // 光有"前缀复用 N/N 条"不够：那只比了消息条数。这里用服务端
                // 真给的命中数比"上一轮的全部输入"，对不上就当场报警 ——
                // 缓存断了不会报错，只会让输入按 50 倍价计费。
                if (RunGuards.cacheBroke(lastPromptTokens, result.cacheHitTokens)) {
                    logger?.warn(
                        "缓存断了（第 $step 步）：上一轮输入 $lastPromptTokens token，" +
                            "这一轮只命中 ${result.cacheHitTokens}。" +
                            "查一下这段时间里历史有没有被改过（改一个字就全断）",
                        "缓存",
                    )
                }
                lastPromptTokens = result.promptTokens

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
                    return AskResult.Stop
                }

                // ---- 防"幻觉完成"（0.8.5 最贵的一次失败）----
                //
                // 现象：任务「打开蓝牙」刚发出去，模型一个动作都没做就回
                // "上一批已点击蓝牙开关……任务完成"，summary 还是**上一个
                // 任务**（关上蓝牙）的结论。根因是这一段接着旧上下文跑，
                // 历史里那条 assistant 已经 finished=true，而这一步它连
                // 界面元素都没拿到，于是直接复述了上一段的结论。
                //
                // 处理：**新任务第一步 + 带着历史 + 零动作就要收尾**时不采纳，
                // 把"你没有界面依据"当场回灌给模型（同一步重新问一次，
                // 不算新的一步），让它要么先看清界面、要么给出动作。
                // 只拦一次：模型第二次仍然坚持就放行，避免和"任务其实
                // 已经完成"的正常情况来回拉锯。
                //
                // ⚠️ 必须放在这里：这一支原本在循环内就 return 了，
                // 把判断放到 ask 循环外面是永远走不到的死代码。
                if (RunGuards.shouldRefuseBlindFinish(
                        step = step,
                        carriedHistoryCount = seedHistory.size,
                        finished = p.finished,
                        actionCount = p.actions.size,
                        alreadyRefused = refusedBlindFinish,
                    )
                ) {
                    refusedBlindFinish = true
                    val refuseMsg = "拦下一次没有依据的「任务已完成」：这是新任务的" +
                        "第一步，又带着上一段已经结束的上下文，这一步既没有可用的" +
                        "界面元素、也没有任何动作，无法确认任务真的做完了。"
                    logger?.warn(refuseMsg, "防幻觉")
                    listener.onEvent(EventKind.THOUGHT, refuseMsg, "防幻觉")
                    logger?.recordStep(
                        step = step,
                        thought = p.thought,
                        action = "（被端侧拦下：新任务第一步就宣布完成）",
                        result = "未采纳，已要求模型先确认当前界面",
                        rawModelOutput = result.text,
                        shot = stepShot,
                    )
                    // 提醒拼进本步的 interruption，重新问一次（不算新一步）
                    interruptions = (interruptions + "\n" +
                        "⚠️ 你刚刚宣布「任务完成」，但这一步你**没有任何界面依据**：" +
                        "既没有可用的界面元素列表，也没有给出任何动作。" +
                        "上面历史里那句「已完成」属于**旧任务**，不算数。" +
                        "请先确认当前界面的真实状态（需要就先 need_image 要截图，" +
                        "或 open_app 打开目标应用），再决定：给出下一步动作，" +
                        "或者在确实做不到时用 failed 说明原因。" +
                        "**最终决定权在你**：如果你确认确实已完成，直接再回答一次 finished，我会采纳。").trim()
                    // 这次输出照样记进历史，保持"严格追加"的对话结构
                    history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                    continue@ask
                }

                // ---- 任务完成？ ----
                // 模型可能同一批既给 actions 又说 finished（语义是"做完这批
                // 动作任务就完成了"）。有动作时不能在这里结束，否则动作一个都
                // 不会执行；放去这批动作执行完之后再收尾（见循环末尾）。
                if (p.finished && p.actions.isEmpty()) {
                    // ---- 收尾闸 ----
                    // 一句话就宣布完成、这一步既没动作、界面也没变过 ——
                    // 这是幻觉成本最低的入口（模型复述历史里的结论、
                    // 或者把"我看不到"当成"已经做好了"）。
                    // 只认两种端侧证据：这一步执行过动作，或界面相对上一步变了。
                    val interfaceChanged = treeObservable &&
                        criteria.sameTree == 0 && step > 1
                    if (RunGuards.shouldRefuseUnverifiedFinish(
                            finished = true,
                            executedActions = 0,
                            interfaceChanged = interfaceChanged,
                            refusals = finishRefusals,
                        )
                    ) {
                        finishRefusals++
                        val refuseMsg = "拦下一次没有证据的「任务已完成」：" +
                            "这一步既没有执行任何动作，界面也没有相对上一步发生过变化。"
                        logger?.warn(refuseMsg, "防幻觉")
                        listener.onEvent(EventKind.THOUGHT, refuseMsg, "防幻觉")
                        logger?.recordStep(
                            step = step,
                            thought = p.thought,
                            action = "（被端侧拦下：无动作、无界面变化就宣布完成）",
                            result = "未采纳，已要求模型给出依据",
                            rawModelOutput = result.text,
                            shot = stepShot,
                        )
                        interruptions = (interruptions + "\n" +
                            "⚠️ 你刚刚说「任务完成」，但这一步你**没有任何依据**：" +
                            "一个动作都没执行，界面也和上一步一模一样（或者根本读不到界面）。" +
                            "请先确认当前屏的真实状态：确实完成，就用一句话说明你依据的是" +
                            "哪个可见状态（比如屏幕上已经出现「发送成功」）；" +
                            "没完成就给下一步动作；做不到就用 failed 说明原因。" +
                            "**最终决定权在你**：确认完成了就直接再回答一次 finished，我会采纳。").trim()
                        // 这次输出照样进历史，保持严格追加
                        history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                        continue@ask
                    }
                    val summary = p.summary.ifBlank { "模型判断任务已完成" }
                    logger?.line("任务结束：$summary", "任务")
                    listener.onEvent(EventKind.RESULT, summary, "完成")
                    finish(true, summary)
                    return AskResult.Stop
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
                        stepShot = logger?.saveScreenshot(step, shot)
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
                        return AskResult.Stop
                    }
                    // 超限但模型给了动作 → 执行动作（别丢有效动作），但计数器 +1
                    imageOverLimitRetries++
                    if (imageOverLimitRetries > OVER_LIMIT_MAX_RETRY) {
                        val msg = "模型连续 $imageOverLimitRetries 次坚持要截图，即使已经给了动作。" +
                            "说明它根本没打算靠界面元素做决策，继续下去就是烧钱。"
                        logger?.error(msg, "截图超限")
                        listener.onEvent(EventKind.ERROR, msg, "截图超限")
                        finish(false, msg)
                        return AskResult.Stop
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
                        return AskResult.Stop
                    }
                    // 超限但模型给了动作 → 执行动作（别丢有效动作），但计数器 +1
                    skillOverLimitRetries++
                    if (skillOverLimitRetries > OVER_LIMIT_MAX_RETRY) {
                        val msg = "模型连续 $skillOverLimitRetries 次坚持要调用技能，即使已经给了动作。" +
                            "说明它根本没打算靠现有信息做决策，继续下去就是烧钱。"
                        logger?.error(msg, "技能超限")
                        listener.onEvent(EventKind.ERROR, msg, "技能超限")
                        finish(false, msg)
                        return AskResult.Stop
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

    val p = parsed ?: run {
        fail("内部错误：没有拿到可用的模型输出。", "模型")
        return AskResult.Stop
    }
    return AskResult.Ready(p, modelOutput)
}
