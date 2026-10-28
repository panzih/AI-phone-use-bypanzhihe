package com.aiphone.assistant.agent

import com.aiphone.assistant.agent.Agent.EventKind
import com.aiphone.assistant.agent.AgentLimits.FOREGROUND_YIELD_MS
import com.aiphone.assistant.agent.AgentLimits.FOREGROUND_YIELD_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.LOCAL_DISMISS_LIMIT
import com.aiphone.assistant.agent.AgentLimits.OPEN_ARRIVED_SETTLE_MS
import com.aiphone.assistant.agent.AgentLimits.OPEN_STABLE_HARD_MS
import com.aiphone.assistant.agent.AgentLimits.OPEN_STABLE_MIN_MS
import com.aiphone.assistant.agent.AgentLimits.OPEN_STABLE_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.OVERLAY_SETTLE_MS
import com.aiphone.assistant.agent.AgentLimits.PAUSE_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.STABLE_HARD_MS
import com.aiphone.assistant.agent.AgentLimits.STABLE_MIN_MS
import com.aiphone.assistant.agent.AgentLimits.STABLE_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.STABLE_SETTLE_MS
import com.aiphone.assistant.agent.AgentLimits.STOP_POLL_MS
import com.aiphone.assistant.agent.AgentLimits.TAP_HOME
import com.aiphone.assistant.agent.AgentLimits.hasSideEffect
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.RunLogger
import com.aiphone.assistant.overlay.AgentPhase
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Agent 运行期的低层动作：暂停/继续、等待稳定、让开前台、动作后验证、
 * 弹窗处理、收尾与指标。
 *
 * 这些函数都是 [AgentSession] 的扩展，只操作“当前这一趟任务”的状态；
 * 主循环的编排在 [AgentSession.run]，阶段拆分见 AgentStepObserver /
 * AgentModelExchange / AgentActionExecutor。
 */
/** 一次"按本地规则关弹窗"的结果 */
internal data class DismissOutcome(val acted: Boolean, val note: String)

/**
 * 模型显式下发 `dismiss_dialog` 时走这条：结论一律回灌给模型。
 */
internal suspend fun AgentSession.handleDismissDialog(): String = dismissByLocalRules("云端").note

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
internal suspend fun AgentSession.dismissByLocalRules(source: String): DismissOutcome {
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
    val err = controller.execute(tap, { px, py -> OverlayBus.pulse(px, py) })
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
 * 每步读界面**之前**，端侧看一眼有没有挡路的无副作用弹窗。
 *
 * **只报告，不点** —— 点不点由云端决定（真正动手的是 [dismissByLocalRules]，
 * 它只在模型下发 `dismiss_dialog` 时才被调用）。
 *
 * 为什么不再自己点（0.10.1 改的）：用户 2026-10-21 定的权责是
 * **云端模型拥有最终决定权**。词表再保守也是猜的 —— 把「取消订单」
 * 当成「取消」这种事，端侧一旦自己动了手，云端连发生过什么都不知道。
 * 改成建议之后成本几乎不变：这段建议拼在本步的 user 消息里，
 * 不额外多一轮往返，只是多几十个 token。
 *
 * 上限 [LOCAL_DISMISS_LIMIT] 仍是一次任务内的总次数：真碰上
 * 「关掉又弹出来」的页面，提醒太多次也是噪声。
 *
 * @return 给模型看的建议；null = 没发现挡路弹窗
 */
internal suspend fun AgentSession.localDismissAdvice(): String? {
    if (!settings.localDialogDismiss) return null
    if (localDismissCount >= LOCAL_DISMISS_LIMIT) return null
    if (isStopped()) return null

    val nodes = withContext(Dispatchers.IO) { controller.parseNodes() }
    if (nodes.isEmpty()) return null
    val r = localRuleEngine.findSafeDismiss(nodes)
    val target = r.target ?: return null
    localDismissCount++
    val label = target.text.ifBlank { target.contentDesc }.trim().take(20)
    return "端侧建议：这一屏像是挡路的弹窗，安全关闭按钮是「$label」（编号 [${target.index}]）。" +
        "要不要关**由你决定**：要关就直接点这个编号，或下发 dismiss_dialog 让端侧去执行；" +
        "如果它其实是重要提示（授权、支付、数据确认），就别动它。"
}

/**
 * 让开前台（lazy）。
 *
 * 用在"模型要截图"以及"执行非 open_app 动作"之前：纸盒自己还在前台，
 * 模型看到/点到的就是纸盒界面。已经不在前台就什么都不做。全程只让一次。
 */
internal suspend fun AgentSession.yieldForegroundIfNeeded() {
    if (foregroundYielded) return
    // 0.9.18：副屏模式下截图来自 ImageReader（虚拟屏里没有纸盒的悬浮窗），
    // 操作也走副屏通道 —— 主屏让位（按 HOME）既无意义还扰民，直接跳过。
    // 0.9.17 蓝叠实证：副屏模式截一张图白按一次 HOME、白等 600ms，
    // 还把用户正在用的主屏顶掉。
    if (controller.isVirtualDisplay) return
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
internal suspend fun AgentSession.verifyLeftAfterOpenApp() {
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
internal suspend fun AgentSession.pressHomeToYield() {
    OverlayBus.setPhase(AgentPhase.ACTING)
    withContext(Dispatchers.IO) {
        controller.execute(TAP_HOME, { px, py -> OverlayBus.pulse(px, py) })
    }
    // 标记"自己刚按 HOME"，之后 SELF_HOME_SUPPRESS_MS 内不自动切副屏（0.8.3）
    watcher.noteSelfHome()
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
internal enum class WaitOutcome { DONE, STOPPED, SWITCH_REQUESTED, AUTO_DESKTOP_SWITCH }

/**
 * 统一的中断判定：急停优先于切换（两者不该同时出现）。
 * 切换请求 = 用户点了「切到副屏」或「切回主屏」。
 */
internal fun AgentSession.interruption(): WaitOutcome = when {
    isStopped() -> WaitOutcome.STOPPED
    OverlayBus.moveToVdRequested || OverlayBus.returnFromVdRequested ->
        WaitOutcome.SWITCH_REQUESTED
    watcher.ready() -> WaitOutcome.AUTO_DESKTOP_SWITCH
    else -> WaitOutcome.DONE
}

/**
 * 等待，但可以中途响应急停 / 通道切换。
 *
 * 一次 `delay(10000)` 会让"按了急停却还要等十秒"变成常态，
 * 所以拆成小段轮询；每段都顺手查切换请求，长 sleep 中也能立刻
 * 切到副屏 / 切回主屏（0.8.2）。
 */
internal suspend fun AgentSession.awaitWithStop(ms: Long): WaitOutcome {
    var left = ms
    while (left > 0) {
        // 长等待里也要停得住：暂停的时间**不计入** left，
        // 恢复后把剩下的等完 —— 否则"暂停 10 秒"会白送 10 秒
        if (!awaitResume(currentStep)) return WaitOutcome.STOPPED
        val out = interruption()
        if (out != WaitOutcome.DONE) return out
        val chunk = minOf(left, STOP_POLL_MS)
        delay(chunk)
        left -= chunk
    }
    return interruption()
}

/**
 * 「暂停」的落点：循环在这里真正停住，直到用户点继续或停止。
 *
 * ## 为什么必须是一个显式的等待点，而不是"每步查一下 pauseRequested"
 *
 * 查一下只能让**下一步**不开始；用户按暂停时最想要的是"现在别再动了"——
 * 当前这一批剩下的动作、当前这次请求都该停。所以三个地方各插一次：
 * 步开头、每个动作之前、发请求之前。停住之后**不读控件树、不发请求**，
 * 所以暂停期间消耗是零（这和"停止"只差在能不能接着走）。
 *
 * 暂停期间用户补的话进 [OverlayBus.drainUserNotes]，由调用方拼进下一条
 * user 消息 —— 不单独发消息，否则对话就不是严格追加，缓存前缀会断。
 *
 * @return true = 可以继续；false = 用户在等待期间按了停止
 */
internal suspend fun AgentSession.awaitResume(step: Int): Boolean {
    if (isStopped()) return false
    if (!OverlayBus.pauseRequested) return true

    if (!pauseAnnounced) {
        pauseAnnounced = true
        OverlayBus.markPaused(true)
        OverlayBus.setPhase(AgentPhase.PAUSED)
        val msg = "已暂停（第 $step 步）。可以在输入框里补一句话，点发送并进下一轮。"
        logger?.line(msg, "暂停")
        listener.onEvent(EventKind.THOUGHT, msg, "暂停")
    }
    // 轮询间隔取 150ms：比 [STOP_POLL_MS] 短，是因为"按了继续要马上动"
    // 比"按了停止要马上停"更影响手感，而 150ms 的轮询开销可以忽略
    while (OverlayBus.pauseRequested) {
        if (isStopped()) return false
        delay(PAUSE_POLL_MS)
    }
    if (isStopped()) return false

    pauseAnnounced = false
    OverlayBus.markPaused(false)
    logger?.line("继续执行（第 $step 步）", "暂停")
    return true
}

/** 这个动作要不要做“动作后验证”：第一版只验证最可靠的导航类点击/返回 */
internal fun AgentSession.isVerifiable(kind: TouchKind): Boolean =
    kind == TouchKind.TAP || kind == TouchKind.KEY_BACK

/**
 * 动作后验证。
 *
 * 判据是"动作前后的页面指纹有没有变"。**读不到控件树时不做这个判断** ——
 * [beforeNodes] 为 null 就说明那一帧根本没看到东西，此时指纹是个固定值，
 * 说"界面没变化"是无中生有，还会触发一次多余的重试（0.8.5 日志里
 * 高德和中信证券反复出现"重试后界面仍无变化"，其实什么都没数）。
 *
 * - 指纹没变 → 可能没点中：导航类动作**重试一次**；有副作用的动作
 *   （发送/支付/下单/确认/删除等）**只上报、绝不重试**，避免一次误判
 *   就重复发送/付款。
 *
 * @param hint 模型看到的那一份元素列表里的同一个节点；重试时要按身份
 *             找回目标，不能再按编号点（编号可能已经漂移）
 * @return 回灌给模型、写进结果列表的一行
 */
internal suspend fun AgentSession.verifyAction(
    action: TouchAction,
    single: String,
    beforeNodes: List<UiNode>?,
    after: String,
    hint: UiNode? = null,
): String {
    if (beforeNodes == null) {
        // 读不到控件树 → 没有可比对的依据。如实说，不猜"没点中"
        logger?.line("  $single → 已执行（读不到控件树，无法判断界面有没有变化）", "执行")
        return "$single → 已执行（这一屏读不到控件树，无法确认界面是否变化）"
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
        controller.execute(action, { px, py -> OverlayBus.pulse(px, py) }, hint)
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
internal data class StableResult(
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
internal suspend fun AgentSession.awaitStable(
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
    // 这一轮等待里有没有**真正读到过**控件树。一次都没读到的话，
    // 指纹相等只是"两帧都是空的"，不能拿来当"界面已稳定"的证据
    var sawTree = false

    while (true) {
        // 等界面稳定期间同样可暂停 —— 这里不暂停的话，一次"点完等它静下来"
        // 会一直把界面读下去，用户按了暂停还在读树
        // （暂停时长会被算进 elapsed，但那只影响"最少等多久"这一档，
        //  不会提前放行动作，实测无副作用）
        if (OverlayBus.pauseRequested) {
            if (!awaitResume(currentStep)) return StableResult(WaitOutcome.STOPPED)
        }
        // parseNodes() 自己就切到 IO 线程了，不用再包一层 withContext
        val t0 = System.nanoTime()
        val frame = controller.parseNodesOrNull()
        if (frame != null) sawTree = true
        val fp = PageFingerprint.fingerprint(frame.orEmpty())
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
                sawTree = sawTree,
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
                        sawTree = sawTree,
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
                sawTree = sawTree,
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
 */internal fun AgentSession.logStableCost(
    kind: TouchKind,
    elapsedMs: Long,
    minFloorMs: Long,
    early: Boolean,
    parseMs: Long,
    parseCount: Int,
    sawTree: Boolean = true,
) {
    val what = if (kind == TouchKind.OPEN_APP) "等应用起来" else "等界面稳定"
    val why = if (early) "（看到界面变过，未等满兜底 ${minFloorMs}ms）" else ""
    val read = if (parseCount > 0) {
        "，其中读控件树 ${parseMs}ms / $parseCount 次"
    } else {
        ""
    }
    // 一次都没读到控件树：这里的"稳定"只是"两帧都空"，必须说清楚，
    // 否则复盘时会以为界面真的稳定过
    val blind = if (sawTree) "" else "，全程读不到控件树（稳定判据无效）"
    logger?.line("$what ${elapsedMs}ms$why$read$blind", "等待")
}

internal suspend fun AgentSession.fail(message: String, label: String) {
    logger?.error(message, label)
    listener.onEvent(EventKind.ERROR, message, label)
    finish(false, message)
}

internal suspend fun AgentSession.finish(success: Boolean, message: String) {
    // 无条件清掉挂起的通道切换请求，兜住所有终止路径（0.8.2 审阅加固）
    OverlayBus.clearMoveToVirtualDisplay()
    OverlayBus.clearReturnFromVd()
    // 暂停状态也必须收干净：留着的话下一次任务一开头就以为"用户还在暂停"，
    // 而按钮上已经写着「已暂停」——那是最难查的一类僵死。
    pauseAnnounced = false
    OverlayBus.clearPause()
    // 暂停期间打了字、任务却已经结束的那几句，不能带进下一次任务
    OverlayBus.clearUserNotes()

    // 收尾：只要还在副屏、或建过副屏（哪怕迁移失败提前返回），都销毁，不留残屏（0.8.0 验收⑤）
    // （"是不是建过副屏"只有迁移自己知道，所以整段收尾交给它）
    migration.destroyOnFinish()
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
    // 结局要进 report.json：导出包的 index.csv 靠它一眼看出这趟是成功、
    // 失败还是卡住。以前只有 run.log 的最后一行有，批量复盘很费劲。
    logger?.setOutcome(success, message)
    logger?.close()
    listener.onFinished(success, message)
}


/** 状态卡上显示的一行：单个动作直接写，一批就写第一个 + 总数 */
internal fun AgentSession.overlayText(actions: List<TouchAction>): String = when (actions.size) {
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
internal fun AgentSession.touchesOverlayButtons(action: TouchAction): Boolean {
    if (OverlayBus.overlapsOverlayButtons(action.x, action.y)) return true
    // 滑动/拖拽/甩动要连终点一起看，路径可能横穿面板
    return when (action.kind) {
        TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG ->
            OverlayBus.overlapsOverlayButtons(action.x2, action.y2)
        else -> false
    }
}

/** 三个停止来源：Agent 自己的标志、宿主界面的、悬浮窗按钮的 */
internal fun AgentSession.isStopped(): Boolean =
    stopRequested || listener.isStopRequested() || OverlayBus.stopRequested

/** 动作签名，用来识别"反复做同一件事" */
internal fun AgentSession.actionSignature(a: TouchAction): String =
    "${a.kind}|${a.targetIndex}|${a.x},${a.y}|${a.x2},${a.y2}|${a.durationMs}|${a.direction}"

internal fun AgentSession.md5(bytes: ByteArray): Int =
    MessageDigest.getInstance("MD5").digest(bytes).contentHashCode()
