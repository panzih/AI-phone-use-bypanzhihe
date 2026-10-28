package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.channel.UiTreeRead
import com.aiphone.assistant.agent.Agent.EventKind
import com.aiphone.assistant.agent.Agent.Listener
import com.aiphone.assistant.agent.AgentLimits.BROWSER_EVAL_MAX_CHARS
import com.aiphone.assistant.agent.AgentLimits.BROWSER_HTML_DEFAULT_CHARS
import com.aiphone.assistant.agent.AgentLimits.BROWSER_HTML_MAX_CHARS
import com.aiphone.assistant.agent.AgentLimits.BROWSER_LOAD_SETTLE_MS
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
 * 观察一步：刷新分辨率/前台/通道状态，读控件树，算指纹与卡死判据，
 * 并把端侧提示整理成这一步的 interruptions。
 *
 * 从 [AgentSession.run] 原样抽出。返回：
 *  - [ObserveResult.Skip]：这一步被通道迁移/用户接管吃掉了，直接下一步；
 *  - [ObserveResult.Stop]：已经收尾（卡死/无障碍失效），调用方 return；
 *  - [ObserveResult.Ready]：拿着观察结果去问模型。
 */
internal sealed interface ObserveResult {
    data class Ready(val observation: StepObservation) : ObserveResult
    data object Skip : ObserveResult
    data object Stop : ObserveResult
}

/** 一步观察的结果：模型这一轮能看到的全部输入。 */
internal data class StepObservation(
    val w: Int,
    val h: Int,
    val foreground: String?,
    val tree: String?,
    val treeRead: UiTreeRead,
    val treeObservable: Boolean,
    val interruptions: String,
)

internal suspend fun AgentSession.observeStep(
    step: Int,
    screenWidth: Int,
    screenHeight: Int,
): ObserveResult {

    var w = screenWidth
    var h = screenHeight

    // ---- 看一眼 ----
    // 分辨率每步重读：横竖屏切换、部分 ROM 的游戏模式都会改它
    controller.screenSize()?.let {
        if (it.first > 0 && it.second > 0) { w = it.first; h = it.second }
    }
    val foreground = withContext(Dispatchers.IO) { controller.currentPackage() }
    watcher.observeForeground(foreground)

    // 刷新「切到副屏」按钮：仅主屏模式 + Shizuku READY 时可点
    migration.refreshMoveButton()

    // 用户按了「切到副屏」：在动作间隙完成迁移；成功后从下一步起读副屏
    //
    // T32：迁移期间显式进「暂停」态 —— 迁移要搬任务栈、建虚拟屏，
    // 这几秒里既不该读树也不该发请求（读到的也是搬家途中的半截界面）。
    // 这不是用户按的暂停，所以结束后**无条件**恢复，不改变用户的暂停选择。
    if (OverlayBus.moveToVdRequested) {
        OverlayBus.markPaused(true)
        val moved = migration.moveRequestedByUser()
        OverlayBus.clearMoveToVirtualDisplay()
        OverlayBus.markPaused(false)
        if (moved) return ObserveResult.Skip
    }

    // 用户按了「切回主屏」（副屏模式绿按钮）：动作间隙回迁；成功后从下一步起读主屏
    if (OverlayBus.returnFromVdRequested) {
        OverlayBus.markPaused(true)
        val back = migration.returnRequestedByUser()
        OverlayBus.clearReturnFromVd()
        OverlayBus.markPaused(false)
        if (back) return ObserveResult.Skip
    }

    // 刚换过通道：给模型留一行说明（判据清零已在 onChannelSwitched 里做完）。
    // 编号是**逐屏**的：换了屏，上一步那些 [3][7] 全作废。
    // 不说清楚的话，模型会拿旧编号去点新屏，看起来像"点错了"。
    channelSwitchedTo?.let { to ->
        channelSwitchedTo = null
        val note = "已切换到$to，之前的界面元素编号作废，请重新看本屏的列表"
        lastResult = note
        logger?.line(note, "通道")
    }

    // 每步刷新一次自动切副屏使能（主屏 + Shizuku READY），interruption() 只读内存、零 binder
    watcher.arm(
        // 浏览器模式下不自动迁副屏：把我们的 BrowserActivity 搬到副屏
        // 既没有意义（模型读的是 DOM，不是屏幕），还会把用户甩到桌面上
        !controller.isVirtualDisplay && !controller.isBrowser &&
            ShizukuBridge.state(controller.appContext) == ShizukuBridge.State.READY,
    )
    // 用户回桌面 + 防抖：自动把目标 app 迁到副屏（0.8.3）
    if (watcher.ready() && migration.autoMove()) return ObserveResult.Skip

    // ---- 用户接管检测（只在副屏模式）----
    // 用户可能把应用从副屏**挪回主屏**自己去用。判据、连击计数与
    // "切回主屏继续跑"的后果都在 ChannelMigration.detectTakeover() 里，
    // 这里只消费结果：处理过就进下一步（那边已经换了读取通道）。
    if (migration.detectTakeover()) return ObserveResult.Skip

    // 本步的端侧"可能卡住"提示（null = 没到该说的程度）
    var stuckAdvice: String? = null

    // ---- 端侧先看一眼有没有挡路的弹窗（**只建议，不动手**）----
    //
    // 用户 2026-10-21 定的权责：**云端模型拥有最终决定权**。
    // 端侧可以「发现问题」（词表和安全闸本来就在本地），但**点不点由云端决定**：
    // 要关就直接点那个编号，或者下发 dismiss_dialog 让端侧去执行。
    // 以前这里是端侧自己点掉 —— 那等于端侧替云端做了决定，
    // 一旦词表误判（把「取消订单」当成「取消」），云端连发生了什么都不知道。
    //
    // 成本没有变化：这段建议拼在这一步的 user 消息里，
    // 不额外多一轮往返（只是多几十个 token）。
    val localAdvice = localDismissAdvice()
    if (localAdvice != null) {
        logger?.line(localAdvice, "端侧建议")
        listener.onEvent(EventKind.THOUGHT, localAdvice, "端侧建议")
    }

    // 读控件树。这次**不藏悬浮窗** —— 我们自己的节点已经在
    // 解析层按包名过滤掉了，没必要为它闪一下。
    //
    // ⚠️ 用 [UiTreeRead] 而不是一段文本：文本在"没有元素"时是一句
    // 占位语，按行数会数成"1 个元素"。0.8.5 的日志里整整一批失败
    // （微信、蓝牙、中信证券第一次）都是这么被盖住的 ——
    // 明明是无障碍没工作，日志却写着"控件树：1 个元素"。
    val treeRead = withContext(Dispatchers.IO) { controller.readUiTree() }
    // 没有元素就等于**没有元素列表可用**：模型该走"看图 + 坐标"这条路。
    // 这里直接归一成 null，下游 noTreeReason / 自动带图 全都按它判断。
    // 撞到上限时必须**明说**：静默截断会让模型以为"界面上就这么点东西"，
    // 于是找不到目标就乱点（0.8.5 里"找不到编号 30 的元素"部分来自这里）。
    val tree = treeRead.text?.let { text ->
        if (treeRead.truncated) {
            "$text\n（以上是前 ${treeRead.nodes.size} 个元素，已达上限：" +
                "界面上还有更多元素没列出来。要找的目标不在这里就用 scroll 翻，或用 x/y 给坐标。）"
        } else {
            text
        }
    }
    val nodeCount = treeRead.nodes.size
    val treeLine = buildTreeLogLine(treeRead, vdMode = controller.isVirtualDisplay)
    logger?.line(treeLine, "UI")
    // ---- 完整控件树落盘 ----
    // 复盘时最需要的是"模型当时到底看到了什么"，光有个数字不够。
    // 写进 run.log（给人看），同时交给 logger 存成每步一个文件、
    // 并进 report.json（给以后的 AI 复盘用）。
    logger?.recordUiTree(
        step = step,
        summary = treeLine,
        treeText = tree,
        rootAvailable = treeRead.rootAvailable,
        elementCount = nodeCount,
        truncated = treeRead.truncated,
        rawNodeCount = treeRead.rawNodeCount,
    )
    if (tree != null) {
        logger?.section("第 $step 步界面元素（原样发给模型）")
        tree.lines().forEach { logger?.line("  $it") }
    }

    // ---- 这一屏能不能拿"界面变了没有"当判据 ----
    // 读不到树、或这一屏压根没有元素时，指纹是一个**恒定值**，
    // 拿它比就是在比空气：连续几步必然"一模一样"，于是第 6 步
    // 一定会撞上"卡住了：界面连续 6 步没有任何变化"（0.8.5 日志里
    // 两次微信任务都死在这一条）。所以这里明确要求"看得见且有东西"
    // 才启用这个判据。
    val treeObservable = RunGuards.treeObservable(treeRead.rootAvailable, nodeCount)
    // 浏览器模式：DOM 摘要基本上一直是那几个元素（点一下只改文字），
    // 拿它算"界面没变化"既会误判成动作没生效、也会一路累加到"卡住"。
    // 所以这个模式下**不算**同屏指纹 —— 网页的真实变化由模型用
    // browser_eval / browser_html 自己确认。
    val treeHash = if (!treeObservable || controller.isBrowser) 0 else md5(tree!!.toByteArray())
    criteria.sameTree =
        RunGuards.nextSameTreeCount(criteria.sameTree, treeHash, criteria.lastTreeHash)
    if (treeHash != 0) criteria.lastTreeHash = treeHash

    // 连续读不到控件树：这是**环境坏了**，不是模型的问题。
    // 单独计数，用来在止损消息里说清原因，并在提示词里提醒模型。
    //
    // ⚠️ 只对"本该读得到"的通道计数：副屏、以及 ADB 模式没开无障碍时，
    // 读不到是那条通道的**形态**（模型本来就走截图 + 坐标），
    // 数下去会把它在第 6 步误杀。
    unreadableTreeSteps = if (controller.canDumpUiTree) {
        RunGuards.nextUnreadableStreak(unreadableTreeSteps, treeRead.rootAvailable)
    } else {
        0
    }

    // ---- 卡死：先交给云端判断，不替他停 ----
    //
    // 用户 2026-10-21：**云端模型拥有最终决定权**。端侧判定"你可能卡住了"
    // 只是**建议**：把事实（连续几步没变化 / 连续给同一批动作）告诉云端，
    // 让它自己决定是换做法、认输（failed）、还是继续。
    // 端侧只保留一条硬兜底：建议 STUCK_ADVICE_LIMIT 次仍然毫无进展就强制停
    // （否则就是明知道没用还在烧钱，还会让用户白等）。
    RunGuards.stuckAdvice(criteria.sameTree, criteria.repeatAction, STUCK_LIMIT)?.let { why ->
        if (stuckAdviceCount < STUCK_ADVICE_LIMIT) {
            stuckAdviceCount++
            stuckAdvice = why
            logger?.warn("端侧提醒模型可能卡住（第 $stuckAdviceCount 次）：$why", "卡死")
        } else {
            val msg = "卡住了：$why，端侧已经提醒过 $stuckAdviceCount 次仍无进展，先停下。" +
                "可能是这个界面点不动、或者需要你自己操作一下（比如输入密码）。"
            logger?.error(msg, "卡死")
            listener.onEvent(EventKind.ERROR, msg, "卡住")
            finish(false, msg)
            return ObserveResult.Stop
        }
    }
    // 有进展就把这条计数清零：别让"曾经卡过"一路记到任务结束
    if (criteria.sameTree == 0 && criteria.repeatAction == 0) stuckAdviceCount = 0

    // 连续读不到控件树 → 止损，而且要把原因说成"无障碍没工作"，
    // 而不是"界面点不动"（0.8.5 里就报错过）
    RunGuards.unreadableTreeStop(unreadableTreeSteps, UNREADABLE_TREE_LIMIT)?.let { msg ->
        logger?.error(msg, "无障碍")
        listener.onEvent(EventKind.ERROR, msg, "无障碍没生效")
        finish(false, msg)
        return ObserveResult.Stop
    }

    // 副屏且这一屏没有控件树：模型手里可能**既没元素列表也没图**
    // （副屏刚建好时还没有任何画面）。这时必须明确让它先 open_app。
    val blindOnVd = controller.isVirtualDisplay && tree == null

    // 做成 var：ask 循环里"防幻觉完成"被拦下时，要把提醒**当场**
    // 回灌给模型（同一步重新问一次），而不是等到下一步
    var interruptions = buildList {
        // 第一步 + 接着上一段历史：必须把"任务边界"说清楚。
        //
        // 0.8.5 那两次蓝牙任务就是没这一步：新任务是「打开蓝牙」，
        // 但历史里上一段是「关上蓝牙」且已 finished=true。模型在
        // 读不到任何界面元素的情况下直接复述了上一段的结论
        // （"已成功关闭蓝牙"），一步动作没做就结束了。
        if (step == 1 && seedHistory.isNotEmpty()) {
            add(
                "这是一段**新任务**的开始。上面的历史属于已经结束的旧任务，" +
                    "其中的任务内容和「已完成」结论**一律作废**。" +
                    "只以本次「用户的任务」为准：先在当前界面上确认实际状态，" +
                    "再决定动作；不要复述旧任务的结果。"
            )
        }
        // 纸盒自己在前台：比"界面没变化"更根本的问题，先说它
        if (foreground != null && foreground == selfPackage) {
            add(
                "现在前台是「纸盒」自己，不是用户要操作的应用。先用 open_app 打开目标应用" +
                    "（包名不确定就先调 list_apps 查真实包名），不要在纸盒的界面上点击。"
            )
        }
        // 读不到控件树：这是**环境**问题，必须说清楚，否则模型会
        // 以为"界面是自绘的"而一直等，或者瞎点坐标
        if (!treeRead.rootAvailable) {
            add(
                if (controller.isVirtualDisplay) {
                    // 副屏没有控件树是**设计如此**，不是什么坏了
                    "当前在**副屏**上操作，系统不给第三方读副屏的控件树 —— " +
                        "这里没有元素列表是正常的。看附图判断，动作一律用 x / y 坐标给。"
                } else {
                    "⚠️ 这一屏**读不到控件树**（无障碍服务没有真正生效，或当前窗口不给无障碍）。" +
                        "你只能靠附图判断，坐标点击不可靠。" +
                        "如果连着几步都这样，直接 failed 并让用户重新开启无障碍服务。"
                }
            )
        }
        if (blindOnVd) {
            add(
                "副屏现在**还没有画面**（连截图都抓不到）：多半是还没有应用切到副屏上。" +
                    "先用 open_app 打开目标任务的应用（包名不确定就先调 list_apps），" +
                    "等它出现在副屏上再截图确认。**不要**照抄历史里主屏那张界面的坐标。"
            )
        }
        // 端侧的弹窗建议（不点，只告诉模型）
        if (localAdvice != null) {
            add(localAdvice)
        }
        // 端侧认为"可能卡住了"：把事实说清，怎么做由云端决定
        if (stuckAdvice != null) {
            add(
                "⚠️ 端侧提醒：$stuckAdvice。这**只是提醒**，不是判决 —— " +
                    "换一个新的做法（换元素、换动作、先截图看看、或 open_app 重开），" +
                    "或者认为确实做不下去就 failed 说明原因。再原地重复就真的卡住了。"
            )
        }
        // 浏览器模式下不要因为"元素列表没变"就说动作没生效：
        // 网页点一下通常只改文字/弹层，可交互元素列表**本来就一样**。
        // 实测（2026-10-21 测试页）：点了按钮、页面明确显示"已点击按钮"，
        // 端侧却回灌"界面和上一步完全一样，说明上一批动作没生效"——
        // 会让云端以为失败并重试。这条判据只对原生控件树成立。
        if (criteria.sameTree >= 2 && !controller.isBrowser) {
            add(
                "界面元素和上一步完全一样，说明你上一批动作**没有生效**。" +
                    "不要再用同样的方式。换一个元素编号，或者换一种动作。"
            )
        }
        if (criteria.repeatAction >= 2) {
            add(
                "你已经连续 ${criteria.repeatAction} 次给出同一批动作了，它显然不起作用。" +
                    "这一次必须换一个完全不同的做法。"
            )
        }
    }.joinToString("\n")

    return ObserveResult.Ready(
        StepObservation(
            w = w,
            h = h,
            foreground = foreground,
            tree = tree,
            treeRead = treeRead,
            treeObservable = treeObservable,
            interruptions = interruptions,
        )
    )
}

