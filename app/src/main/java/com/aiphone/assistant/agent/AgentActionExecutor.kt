package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.channel.UiTreeRead
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
 * 执行一批动作：单批预算、通道切换中断、点击前复核、等待稳定、
 * 动作后验证、截图/浏览器/弹窗等特殊动作，以及本批的结果回灌与收尾判定。
 *
 * 从 [AgentSession.run] 原样抽出。返回 [BatchResult.Stop] 表示任务已经
 * 收尾（调用方直接 return），[BatchResult.Next] 表示回到主循环看下一步。
 */
internal sealed interface BatchResult {
    /** 这一批处理完了，回主循环读下一屏。 */
    data object Next : BatchResult

    /** 已经调用过 finish()，run() 应当直接返回。 */
    data object Stop : BatchResult
}

internal suspend fun AgentSession.executeBatch(
    step: Int,
    task: String,
    w: Int,
    h: Int,
    tree: String?,
    treeRead: UiTreeRead,
    p: ActionParser.Parsed,
    modelOutput: String,
): BatchResult {

    p.warning?.let { logger?.warn("解析提示：$it", "解析") }

    // ---- 防死循环：整批动作的签名 ----
    val sig = p.actions.joinToString(";") { actionSignature(it) }
    if (sig == criteria.lastSig) criteria.repeatAction++ else criteria.repeatAction = 0
    criteria.lastSig = sig

    // ---- 单批预算 ----
    // 解析层允许 12 个，但一次真跑 12 个太长了：中间任何一步的界面都和
    // 模型想象的不一样，后面全打在错的地方。裁剪**只影响这一轮执行**，
    // 提示词里也写明了上限，让模型自己别写长脚本。
    val batch = p.actions.take(MAX_BATCH_ACTIONS)
    val droppedActions = p.actions.size - batch.size
    if (droppedActions > 0) {
        logger?.warn(
            "这一批给了 ${p.actions.size} 个动作，只执行前 ${batch.size} 个" +
                "（剩下的等下一轮重看界面后再给）",
            "执行",
        )
    }

    // ---- 显示这一批要干什么 ----
    val desc = AgentPrompt.describeSequence(batch)
    logger?.line("动作（${batch.size} 个）：$desc", "执行")
    listener.onEvent(EventKind.ACTION, desc, "第 $step 步 · 动作")
    if (p.nextHint.isNotBlank()) {
        logger?.line("下一步预告：${p.nextHint}", "模型")
    }
    // 状态卡放不下 12 个动作，只显示第一个 + 总数
    OverlayBus.update(step, maxSteps, overlayText(batch), p.nextHint)

    // ---- 逐个执行（执行 + 等界面稳定 + 动作后验证）----
    val results = ArrayList<String>(batch.size + 2)
    // 因切换通道而完全没执行（没进 results）的动作数；-1 表示没被切换中断
    var switchPendingCount = -1
    // 这一步**真正执行过**几个动作。收尾闸只认它和"界面变了"两样证据，
    // 模型自己说的"已完成"不算
    var executedActions = 0
    // 本批有没有动作被"点击前复核"叫停。被叫停的批次即使模型说
    // finished，也不能当作完成 —— 那一下根本没点。
    var reviewVetoedThisBatch = false
    // 本批截了几张图。一批最多一张：模型可能连写几个 capture，
    // 但同一批里画面几乎没变，多截只是多花钱
    var shotsThisBatch = 0
    for ((i, action) in batch.withIndex()) {
        // 暂停点③：每个动作之前 —— 暂停不该"再把这一批跑完"
        if (!awaitResume(step)) {
            finish(false, "你停止了任务（执行第 $step 步第 ${i + 1} 个动作之前）")
            return BatchResult.Stop
        }
        // 动作间隙统一查中断：急停立刻收尾；切换请求 break、交本步 tail 留痕，
        // 下一轮 for(step) 开头 261/268 处理迁移/回迁（0.8.2）
        when (interruption()) {
            WaitOutcome.STOPPED -> {
                finish(false, "你停止了任务（执行第 $step 步第 ${i + 1} 个动作之前）")
                return BatchResult.Stop
            }
            WaitOutcome.SWITCH_REQUESTED -> {
                // 0.9.18 死锁护栏：目标还没记上时，open_app 正是目标的唯一
                // 来源 —— 掐了它 = 应用永远起不来 = 目标永远为空 = 切换请求
                // 永远被拒（蓝叠实证：用户连点「切到副屏」4 次，前 3 次全撞进
                // 这个环）。放行 open_app 把应用拉起来，下一步边界自然拿着
                // 真目标完成切换。
                if (switchMustYieldToOpenApp(action.kind, watcher.targetPkg)) {
                    // 放行：继续执行这个动作
                } else {
                    switchPendingCount = batch.size - i
                    break
                }
            }
            WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                if (migration.autoMove()) {
                    switchPendingCount = batch.size - i
                } else {
                    results.add("自动切副屏未成功（已交回云端）")
                    switchPendingCount = batch.size - i
                }
                break
            }
            WaitOutcome.DONE -> {}
        }

        val single = AgentPrompt.describe(action)
        OverlayBus.update(step, maxSteps, overlayText(listOf(action)), p.nextHint)
        val next = batch.getOrNull(i + 1)

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
                    return BatchResult.Stop
                }
                WaitOutcome.SWITCH_REQUESTED -> {
                    results.add("$single → 因切换通道中止")
                    switchPendingCount = batch.size - i - 1
                    break
                }
                WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                    val moved = migration.autoMove()
                    results.add(
                        if (moved) "$single → 已自动切到副屏"
                        else "$single → 自动切副屏未成功（已交回云端）"
                    )
                    switchPendingCount = batch.size - i - 1
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
                        stepShot = logger?.saveScreenshot(step, shot)
                        logger?.line(
                            "  $single → 已截（${shot.size} 字节），随下一步发送",
                            "执行",
                        )
                        results.add("$single → 已截图，随下一步发给你")
                    }
                }
            }
        } else if (action.kind == TouchKind.BROWSER_OPEN) {
            // 打开内置浏览器：**这是"用哪条信息源"的决定，由云端模型下**。
            // 端侧只负责把窗口拉起来、把通道切过去，然后立刻交回云端 ——
            // 因为它下一步要看的是一张全新的 DOM 摘要，不能拿旧编号继续点。
            OverlayBus.setPhase(AgentPhase.LAUNCHING)
            logger?.line("  $single", "执行")
            if (totalBrowserOpens >= MAX_BROWSER_OPENS) {
                results.add("$single → 本次任务已经开过 $totalBrowserOpens 次浏览器，不再重复打开")
                break
            }
            val err = com.aiphone.assistant.browser.BrowserDriver.open(
                controller.appContext,
                action.url,
            )
            if (err != null) {
                logger?.error("  $single → 打开浏览器失败：$err", "执行")
                results.add("$single → 打开浏览器失败：$err")
                break
            }
            controller.enterBrowser()
            channelSwitchedTo = "内置浏览器"
            totalBrowserOpens++
            results.add(
                "$single → 已打开内置浏览器" +
                    (if (action.url.isBlank()) "（空白页）" else "：${action.url}") +
                    "。从下一步起元素列表就是**网页的 DOM 摘要**（编号点击 = 点网页元素），" +
                    "要源码可以用 browser_html，要精确结果可以用 browser_eval。"
            )
            // 页面要先加载完：否则下一步读到的是空白页
            delay(BROWSER_LOAD_SETTLE_MS)
            break
        } else if (action.kind == TouchKind.BROWSER_HTML) {
            // 读网页背后的代码。**只读一次、能截断就截断** ——
            // 整页 HTML 几十 KB，每步都发会打断缓存前缀（那是 50 倍差价）
            OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
            logger?.line("  $single", "执行")
            val limit = (if (action.maxChars > 0) action.maxChars else BROWSER_HTML_DEFAULT_CHARS)
                .coerceIn(500, BROWSER_HTML_MAX_CHARS)
            val html = com.aiphone.assistant.browser.BrowserDriver.html(
                action.selector.ifBlank { null },
                limit,
            )
            if (html == null) {
                val why = com.aiphone.assistant.browser.BrowserDriver.lastError() ?: "未知原因"
                logger?.error("  $single → 读源码失败：$why", "执行")
                results.add("$single → 读源码失败：$why")
            } else {
                results.add(
                    "$single → 已取到网页源码（${html.length} 字符" +
                        (if (action.selector.isBlank()) "，整页" else "，selector=${action.selector}") +
                        "）：\n" + html
                )
            }
            break
        } else if (action.kind == TouchKind.BROWSER_EVAL) {
            // 在网页里跑一段 JS 并把结果拿回来
            OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
            logger?.line("  $single", "执行")
            val out = com.aiphone.assistant.browser.BrowserDriver.eval(action.js)
            if (out == null) {
                val why = com.aiphone.assistant.browser.BrowserDriver.lastError() ?: "未知原因"
                logger?.error("  $single → 执行JS失败：$why", "执行")
                results.add("$single → 执行JS失败：$why")
            } else {
                val trimmed = if (out.length > BROWSER_EVAL_MAX_CHARS) {
                    out.take(BROWSER_EVAL_MAX_CHARS) + "…（已截断，共 ${out.length} 字符）"
                } else {
                    out
                }
                results.add("$single → JS 返回：$trimmed")
            }
            break
        } else if (action.kind == TouchKind.DISMISS_DIALOG) {
            // 云端显式下发：端侧在当前页面找安全关闭按钮，找到就点、
            // 找不到（或涉及授权/支付）就不操作；然后立刻把控制权交回
            // 云端，本批后续预排动作暂不执行（break）
            logger?.line("  $single", "执行")
            results.add(handleDismissDialog())
            when (interruption()) {
                WaitOutcome.SWITCH_REQUESTED ->
                    // dismiss 等待期间用户切了通道：后续动作交给切换流程（0.8.2）
                    switchPendingCount = batch.size - i - 1
                WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                    val moved = migration.autoMove()
                    if (moved) results.add("已自动切到副屏")
                    switchPendingCount = batch.size - i - 1
                }
                else -> {
                    val skipped = batch.size - i - 1
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

            // 模型看到的那个控件（按编号操作时用来在当前帧里按身份找回它）。
            // 编号是逐帧的，模型往返期间界面可能重排，直接点第 N 个
            // 很可能点到别的控件上。
            val hint = if (action.targetIndex > 0) {
                treeRead.nodes.firstOrNull { it.index == action.targetIndex }
            } else {
                null
            }

            // ---- 点击前复核（可选）----
            // 只改"这一下落在哪"，不改"谁来拍板"：动作仍是模型给的，
            // 复核员只在注入之前把"点偏/点空"这种错误拦下来。
            // 复核要额外一次模型请求（还带局部图），所以默认关、按档位开。
            var runAction = action
            var reviewNote: String? = null
            if (!tapReviewVisionUnsupported &&
                TapReview.shouldReview(
                    settings.tapReview,
                    action.kind,
                    action.targetIndex,
                    controller.isBrowser,
                )
            ) {
                val decision = reviewPointAction(action, hint, step, task, w, h)
                if (isStopped()) {
                    finish(false, "你停止了任务（点击前复核时）")
                    return BatchResult.Stop
                }
                when {
                    // 截图失败/复核请求失败：按原点执行，别让增益功能拖垮任务
                    decision == null -> {}

                    decision.abort -> {
                        val why = decision.why.ifBlank { "复核员认为落点不对" }
                        reviewNote = "复核叫停：$why"
                        logger?.warn("点击前复核叫停 $single：$why", "复核")
                        listener.onEvent(EventKind.THOUGHT, "点击前复核叫停：$why", "复核")
                        results.add("$single → 复核叫停（$why），没有执行")
                        reviewVetoedThisBatch = true
                        break
                    }

                    decision.correctedX != null && decision.correctedY != null -> {
                        val nx = decision.correctedX
                        val ny = decision.correctedY
                        reviewNote = "复核改点：(${action.x},${action.y}) → ($nx,$ny)" +
                            if (decision.why.isBlank()) "" else "（${decision.why}）"
                        logger?.line(
                            "点击前复核改点：$single → ($nx,$ny)，${decision.why}",
                            "复核",
                        )
                        listener.onEvent(
                            EventKind.THOUGHT,
                            "点击前复核改点：($nx,$ny) ${decision.why}",
                            "复核",
                        )
                        // 改点后必须清掉 targetIndex：通道层只要看到编号
                        // 就会按元素中心点，坐标会被无视。
                        runAction = action.copy(targetIndex = 0, x = nx, y = ny)
                        results.add("$single → $reviewNote")
                    }

                    !decision.parsed -> {
                        logger?.warn("点击前复核：${decision.why}，按原点执行", "复核")
                    }

                    else -> {
                        logger?.line("点击前复核通过：$single", "复核")
                    }
                }
            }

            // 动作前控件树：用来算“动作前指纹”，tap 时还用来判断点的
            // 是不是发送/支付等有副作用的按钮（决定要不要自动重试）。
            //
            // 放在复核**之后**读：复核可能等上几秒，动作前的界面
            // 必须以真正要注入的那一刻为准。
            //
            // 用 parseNodesOrNull：读不到控件树时返回 null，而不是一个
            // 空列表 —— 空列表的指纹是固定值，会把"没看到东西"算成
            // "界面没变化"，于是每次点击都被误判成没点中（0.8.5 日志里
            // 高德、中信证券都有这种无意义的"重试后仍无变化"）。
            val beforeNodes = if (isVerifiable(runAction.kind) && !controller.isBrowser) {
                withContext(Dispatchers.IO) { controller.parseNodesOrNull() }
            } else {
                null
            }

            // 注入前**只在真会撞上急停按钮时**才藏。
            // 状态卡本身是 FLAG_NOT_TOUCHABLE，永远不会吃点击，
            // 所以只需要担心按钮那一小块。
            val mustHide = touchesOverlayButtons(runAction)
            if (mustHide) {
                OverlayBus.hide()
                delay(OVERLAY_SETTLE_MS)
            }
            // 上报真实落点，屏幕上闪一圈水波 ——
            // 用户能看见 AI 点在哪，是"点错了"还是"点了没反应"一眼可辨
            val execResult = withContext(Dispatchers.IO) {
                controller.execute(
                    runAction,
                    { px, py -> OverlayBus.pulse(px, py) },
                    if (runAction.targetIndex > 0) hint else null,
                )
            }
            if (mustHide) {
                OverlayBus.show()
            }

            if (execResult != null) {
                logger?.error("  $single → $execResult", "执行")
                listener.onEvent(EventKind.ERROR, execResult, "第 $step 步 · 失败")
                results.add("$single → 失败：$execResult")
                // 默认**中止本批**：后面的动作几乎都是"在刚才那个动作打开的
                // 界面上"继续操作，前一步没成，后面必然打在错的屏幕上。
                // 模型想"失败了也往下试"必须显式写 "on_error":"continue"。
                if (!p.continueOnError) {
                    val skipped = batch.size - i - 1
                    if (skipped > 0) {
                        results.add(
                            "这一批后面 $skipped 个动作没有执行：" +
                                "前一个动作失败后默认中止（要强行继续就写 \"on_error\":\"continue\"）"
                        )
                    }
                    break
                }
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
                        return BatchResult.Stop
                    }
                    WaitOutcome.SWITCH_REQUESTED -> {
                        // 0.9.18：启动意图已经发出，此刻中止 = observeForeground
                        // 没机会跑、目标记不上，用户的切换请求也拿不到目标
                        // （死锁的另一半）。没有目标时放行走完收尾（补验证 +
                        // 记目标），切换请求留给下一步边界 —— 那时目标已就位，
                        // 一次成功。有目标时维持原样：中止并交边界处理。
                        if (!switchMustYieldToOpenApp(action.kind, watcher.targetPkg)) {
                            results.add("$single → 因切换通道中止")
                            switchPendingCount = batch.size - i - 1
                            break
                        }
                    }
                    WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                        val moved = migration.autoMove()
                        results.add(
                            if (moved) "$single → 已自动切到副屏"
                            else "$single → 自动切副屏未成功（已交回云端）"
                        )
                        switchPendingCount = batch.size - i - 1
                        break
                    }
                    WaitOutcome.DONE -> {}
                }
                // 启动后仍没切走（包名错/启动失败）才补 HOME，
                // 避免下一步模型读到纸盒自己的界面。
                verifyLeftAfterOpenApp()
                // open_app 与后续 sleep 常在同一批：步开头记的还是旧前台，
                // 这里补记新目标，否则同批 sleep 中回桌面拿不到目标（0.8.3）
                watcher.observeForeground(withContext(Dispatchers.IO) { controller.currentPackage() })
                executedActions++
                logger?.line("  $single → 已执行", "执行")
                results.add("$single → 已执行")
            } else if (next?.kind == TouchKind.WAIT) {
                // 下一个动作就是显式 sleep，由它去等，这里不再补等待
                executedActions++
                logger?.line("  $single → 已执行", "执行")
                results.add("$single → 已执行")
            } else {
                // 等界面稳定（最后一个动作也等，等价原来的一批收尾），
                // 拿到稳定指纹后做动作后验证、必要时一次重试。
                // 把**动作前**的指纹一起传进去：有它才能在起步期就判断
                // "界面开始变了没有"，从而提前放行（见 awaitStable）
                OverlayBus.setPhase(AgentPhase.WAITING_SYSTEM)
                val sr = awaitStable(
                    runAction.kind,
                    beforeFp = beforeNodes?.let { PageFingerprint.fingerprint(it) },
                )
                when (sr.outcome) {
                    WaitOutcome.STOPPED -> {
                        finish(false, "你停止了任务（等待中被叫停）")
                        return BatchResult.Stop
                    }
                    WaitOutcome.SWITCH_REQUESTED -> {
                        results.add("$single → 因切换通道中止")
                        switchPendingCount = batch.size - i - 1
                        break
                    }
                    WaitOutcome.AUTO_DESKTOP_SWITCH -> {
                        val moved = migration.autoMove()
                        results.add(
                            if (moved) "$single → 已自动切到副屏"
                            else "$single → 自动切副屏未成功（已交回云端）"
                        )
                        switchPendingCount = batch.size - i - 1
                        break
                    }
                    WaitOutcome.DONE -> {
                        executedActions++
                        results.add(
                            verifyAction(
                                runAction,
                                if (reviewNote != null) "$single（$reviewNote）" else single,
                                beforeNodes,
                                sr.fingerprint ?: "",
                                if (runAction.targetIndex > 0) hint else null,
                            )
                        )
                    }
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
        if (droppedActions > 0) {
            append("（这一批原本 ${p.actions.size} 个动作，只执行了前 ${batch.size} 个；" +
                "剩下 $droppedActions 个没执行，重新看界面后再决定要不要给）")
        }
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
        shot = stepShot,
    )

    // 要图这件事已经变成 actions 里的一个 capture 动作（见 ActionParser），
    // 不再需要"这批结束时立一个旗标"的跨步机制了

    // ---- 回灌给模型 ----
    allHistory.add(ChatTurn(ChatTurn.ASSISTANT, modelOutput))
    lastResult = resultText

    // ---- 这批动作就是任务收尾（模型给动作的同时说 finished）----
    // 动作已经逐个执行完，这里再正常结束，不再请求下一步。
    if (p.finished) {
        if (reviewVetoedThisBatch) {
            // 复核叫停了其中一下，这一批就不能算"做完" —— 即使模型
            // 在同一个输出里写了 finished。把决定交回模型重新看一屏。
            val why = "这一批里有动作被点击前复核叫停，任务不能算完成"
            logger?.warn(why, "复核")
            lastResult = (lastResult?.let { "$it " } ?: "") +
                "（$why。请重新看当前界面，确认下一步该怎么做；" +
                "如果确实已经完成，再回答一次 finished 并说明依据。）"
        } else {
            // 有动作、但**一个都没真正执行**（全落空/全报错）时不能算完成。
            // 以前这里无条件 success —— 用户看到"任务完成"，而屏幕上什么都没发生。
            if (executedActions == 0) {
                val why = "这一批动作没有一个真正执行成功（${resultText.take(120)}），" +
                    "任务并没有完成。"
                logger?.error(why, "防幻觉")
                listener.onEvent(EventKind.ERROR, why, "没做成")
                finish(false, why)
                return BatchResult.Stop
            }
            // 挂起的切换请求由 finish() 无条件清理，无需在此分支处理
            val summary = p.summary.ifBlank { "模型判断任务已完成" }
            logger?.line("任务结束：$summary", "任务")
            listener.onEvent(EventKind.RESULT, summary, "完成")
            finish(true, summary)
            return BatchResult.Stop
        }
    }
    return BatchResult.Next
}
