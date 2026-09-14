package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.RunLogger
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * AI 决策循环。
 *
 * ```
 *   截图 + 控件树 ──→ 模型 ──→ 解析动作 ──→ 执行 ──→ 等一会儿 ──┐
 *        ↑                                                      │
 *        └──────────────────────────────────────────────────────┘
 * ```
 *
 * ## 三个必须做的防护
 *
 * **1. 画面没变 = 上一步没用。**
 * 纯视觉方案最典型的失败是"反复点同一个点"。这里对每张截图算哈希，
 * 连续几步画面完全一样就直接在下一轮的提示里点破它，
 * 逼模型换策略。光靠 max_steps 拦不住这种情况 —— 30 步全点在同一个
 * 没反应的位置，钱花了、事没办。
 *
 * **2. 同一个动作重复 = 死循环。**
 * 画面哈希会被动画干扰（时钟、加载圈），所以再加一道动作签名比对。
 *
 * **3. 历史不带图。**
 * 只有当前这一轮的截图会发给模型，历史全是文本摘要。
 * 30 步每步带一张 1080×2400 的截图，token 量会翻好几倍 ——
 * Roubao 的教训是三角色 + 每步双图，一章游戏关卡花了 $7。
 *
 * ## 关于"自己拍自己"
 *
 * 无障碍截图抓的是整块物理屏。控制应用自己在前台时，
 * 截到的就是纸盒自己的界面，模型会对着它操作 —— 必然出错。
 * 所以循环开始前会检查前台包名，是我们就先按一下回桌面。
 */
class Agent(
    private val controller: ChannelController,
    private val llm: LlmClient,
    private val settings: AppSettings,
    private val maxSteps: Int,
    /** 本应用的包名，用来识别"自己在前台" */
    private val selfPackage: String,
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
     * 这个数字值得盯着：本轮对话是**严格追加**的，所以从第 2 步开始，
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

        val size = controller.screenSize()
        val w = size?.first ?: 0
        val h = size?.second ?: 0
        if (w <= 0 || h <= 0) {
            fail("拿不到屏幕分辨率，无法把模型给的坐标换算成真实位置。", "通道")
            return
        }
        logger?.line("屏幕：${w} x ${h}", "通道")
        logger?.line(llm.describe(), "模型")
        logger?.line(if (OverlayBus.isShowing) "悬浮窗已就绪" else "悬浮窗未启动（缺权限或未开）", "悬浮")

        // ---- 2. 别拍到自己 ----
        ensureNotSelfForeground()

        // ---- 3. 开跑 ----
        val system = AgentPrompt.system(w, h)
        val history = mutableListOf<ChatTurn>()

        var lastHash = 0
        var sameFrame = 0
        var lastActionSig = ""
        var repeatAction = 0

        // 上一步的执行结果。它会拼进**下一条** user 消息里，
        // 而不是单独发一条 —— 见下面 history.add 处的说明。
        var lastResult: String? = null

        for (step in 1..maxSteps) {
            if (isStopped()) {
                finish(false, "你停止了任务（第 $step 步）")
                return
            }

            listener.onProgress(step, maxSteps)
            logger?.section("第 $step / $maxSteps 步")

            // ---- 看一眼 ----
            // 先把悬浮窗藏起来再截屏。无障碍截图抓的是整块物理屏，
            // 悬浮窗不藏的话会出现在图里 —— 模型会把"急停按钮"
            // 当成界面元素去点它。
            OverlayBus.hide()
            delay(OVERLAY_SETTLE_MS)

            val tree = withContext(Dispatchers.IO) { controller.readUiTree() }
            val nodeCount = tree?.lines()?.count { it.isNotBlank() } ?: 0
            logger?.line("控件树：$nodeCount 个元素", "UI")

            val shot = withContext(Dispatchers.IO) { controller.captureFrame() }
            OverlayBus.show()
            if (shot == null || shot.isEmpty()) {
                // 截图失败不该直接终止 —— 但这一轮没有画面，模型没法判断。
                // 记下来，继续下一轮试试。
                logger?.error("截图失败，跳过本轮", "截图")
                listener.onEvent(EventKind.ERROR, "截图失败，跳过本轮", "截图")
                delay(1500)
                continue
            }
            logger?.line("截图：${w}x$h，${shot.size} 字节", "截图")
            logger?.saveScreenshot(step, shot)

            // ---- 画面变了没有 ----
            val hash = md5(shot)
            if (hash == lastHash) sameFrame++ else sameFrame = 0
            lastHash = hash

            // ---- 拼提示 ----
            val interruptions = buildList {
                if (sameFrame >= 2) {
                    add(
                        "画面已经连续 $sameFrame 步没有任何变化，说明你上一个动作**没有生效**。" +
                            "不要再用同样的方式。换一个元素编号，或者换一种动作。"
                    )
                }
                if (repeatAction >= 2) {
                    add(
                        "你已经连续 $repeatAction 次输出同一个动作了，它显然不work。" +
                            "这一步必须换一个完全不同的动作。"
                    )
                }
            }.joinToString("\n")

            val userText = AgentPrompt.stepMessage(
                step = step,
                maxSteps = maxSteps,
                task = task,
                uiTree = tree,
                interruption = interruptions.ifBlank { null },
                lastResult = lastResult,
            )

            // ⚠️ 必须把**实际发出去的这条文本**原样追加进 history。
            //
            // 之前的写法是：发出去的 user 消息（含控件树）从不入历史，
            // 只在事后补一条 "第 N 步执行结果：已执行"。
            // 后果是对话从系统提示词之后就开始分叉 ——
            // 下一次请求的第一条 user 消息和上一次的完全不同，
            // **前缀匹配直接断掉，缓存命中率是 0**。
            //
            // 改成原样追加之后，整条对话变成严格递增：
            //     [system][user1][assistant1][user2][assistant2]...
            // 前缀永不变动，缓存才能一直命中。
            history.add(ChatTurn(ChatTurn.USER, userText))

            // ---- 问模型 ----
            listener.onEvent(EventKind.THOUGHT, "正在请求模型 ...", "第 $step 步")
            val callStart = System.currentTimeMillis()
            // 注意：userText 在这之前已经追加进 history 了，
            // 这里只多传一张图 —— history 就是实际发出去的内容。
            val result = withContext(Dispatchers.IO) {
                llm.chat(system, history, shot)
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
                    // 上下文不裁剪了，所以得防着撑爆模型窗口。
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
                        logger?.warn(
                            "上下文已到 ${result.promptTokens} token，快满了",
                            "上下文",
                        )
                    }
                    logger?.line(
                        "模型返回 ${elapsed}ms，token：输入 ${result.promptTokens} / " +
                            "输出 ${result.completionTokens}" +
                            "（缓存命中 ${result.cacheHitTokens} / " +
                            "未命中 ${result.cacheMissTokens}）",
                        "模型",
                    )
                    logger?.section("模型原始输出")
                    result.text.lines().forEach { logger?.line("  $it") }

                    val parsed = ActionParser.parse(result.text, w, h)
                    parsed.thought?.let {
                        logger?.line("思考：$it", "模型")
                        listener.onEvent(EventKind.THOUGHT, it, "第 $step 步 · 思考")
                    }

                    // ---- 任务完成？ ----
                    if (parsed.finished) {
                        val summary = parsed.summary.ifBlank { "模型判断任务已完成" }
                        logger?.line("任务结束：$summary", "任务")
                        listener.onEvent(EventKind.RESULT, summary, "完成")
                        finish(true, summary)
                        return
                    }

                    // ---- 没解析出动作 ----
                    val action = parsed.action
                    if (action == null) {
                        val warning = parsed.warning ?: "没能解析出动作。"
                        logger?.warn("解析失败：$warning", "解析")
                        listener.onEvent(EventKind.ERROR, warning, "解析失败")
                        // 失败原因回灌给模型，让它重出 —— 但**不单独发一条消息**，
                        // 而是记进 lastResult，拼到下一条 user 消息里。
                        // 单独发消息会让对话结构每轮都不一样，把缓存前缀打断。
                        history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                        lastResult = "上一个输出有问题：$warning" +
                            "请重新输出一个只包含 JSON 对象的动作，不要任何其他文字。"
                        continue
                    }

                    // ---- 防死循环：动作签名 ----
                    val sig = actionSignature(action.kind, action.targetIndex, action.x, action.y)
                    if (sig == lastActionSig) repeatAction++ else repeatAction = 0
                    lastActionSig = sig

                    // ---- 执行 ----
                    val desc = AgentPrompt.describeAction(
                        kind = action.kind,
                        index = action.targetIndex,
                        x = action.x, y = action.y, x2 = action.x2, y2 = action.y2,
                        direction = action.direction,
                        text = action.text,
                        pkg = action.packageName,
                        durationMs = action.durationMs,
                    )
                    logger?.line("动作：$desc", "执行")
                    listener.onEvent(EventKind.ACTION, desc, "第 $step 步 · 动作")

                    // 关键的一步：把"我正要做什么"和"我打算接着做什么"
                    // 显示在悬浮窗上。用户看到下一步不对，可以立刻按急停 ——
                    // 这正是"人在环路"的意义。
                    if (parsed.nextHint.isNotBlank()) {
                        logger?.line("下一步预告：${parsed.nextHint}", "模型")
                    }
                    OverlayBus.update(step, maxSteps, desc, parsed.nextHint)

                    // 执行前再检查一次停止 —— 把急停的响应窗口从"一步"
                    // 缩短到"一次模型调用"
                    if (isStopped()) {
                        finish(false, "你停止了任务（执行第 $step 步动作之前）")
                        return
                    }

                    // 注入前也要藏：底部那个急停按钮是可触摸窗口，
                    // 模型给的坐标万一正好落在它上面，点击会被它吃掉 ——
                    // 甚至点到"急停"把自己的任务停掉。
                    OverlayBus.hide()
                    delay(OVERLAY_SETTLE_MS)
                    val execResult = withContext(Dispatchers.IO) { controller.execute(action) }
                    OverlayBus.show()
                    val ok = execResult == null
                    val resultText = if (ok) "已执行" else execResult!!

                    if (ok) {
                        logger?.line("结果：$resultText", "执行")
                        listener.onEvent(EventKind.RESULT, resultText, "第 $step 步 · 结果")
                    } else {
                        logger?.error("执行失败：$resultText", "执行")
                        listener.onEvent(EventKind.ERROR, resultText, "第 $step 步 · 失败")
                    }

                    logger?.recordStep(
                        step = step,
                        thought = parsed.thought,
                        action = desc,
                        result = resultText,
                        rawModelOutput = result.text,
                        shot = null,
                    )

                    // ---- 回灌给模型 ----
                    history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                    lastResult = resultText

                    // 动作后等页面反应。滚动/点击后通常要一点时间，
                    // 太快截下一张会拍到过渡动画。
                    delay(waitAfter(action.kind))
                }
            }
        }

        finish(false, "到了最大步数 $maxSteps 还没做完。可以加大步数，或者把任务拆小一点。")
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 让开前台。
     *
     * 无障碍截图抓的是整块物理屏，如果纸盒自己在前台，
     * 模型看到的就是纸盒的界面 —— 它会开始点自己的按钮。
     * 这在真机上必然发生，不是理论问题。
     */
    private suspend fun ensureNotSelfForeground() {
        val pkg = withContext(Dispatchers.IO) { controller.currentPackage() } ?: return
        if (pkg != selfPackage) return
        logger?.warn("控制应用自己在前台，先把界面让出来（回桌面）", "通道")
        withContext(Dispatchers.IO) { controller.execute(TAP_HOME) }
        delay(800)
    }

    private fun fail(message: String, label: String) {
        logger?.error(message, label)
        listener.onEvent(EventKind.ERROR, message, label)
        finish(false, message)
    }

    private fun finish(success: Boolean, message: String) {
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

    /** 三个停止来源：Agent 自己的标志、宿主界面的、悬浮窗按钮的 */
    private fun isStopped(): Boolean =
        stopRequested || listener.isStopRequested() || OverlayBus.stopRequested

    /** 动作签名，用来识别"反复做同一件事" */
    private fun actionSignature(kind: TouchKind, index: Int, x: Int, y: Int): String =
        "$kind|$index|$x|$y"


    /**
     * 动作后等多久。
     *
     * 不是固定值：点击通常几百毫秒界面就更新了，打开应用要更久，
     * 等待动作本身已经等过了就不再等。
     */
    private fun waitAfter(kind: TouchKind): Long = when (kind) {
        TouchKind.WAIT -> 0L
        TouchKind.OPEN_APP -> 2500L
        TouchKind.KEY_HOME, TouchKind.KEY_BACK, TouchKind.KEY_RECENTS -> 900L
        TouchKind.SCROLL, TouchKind.SWIPE, TouchKind.FLICK -> 1200L
        TouchKind.INPUT_TEXT -> 900L
        else -> 900L
    }

    private fun md5(bytes: ByteArray): Int =
        MessageDigest.getInstance("MD5").digest(bytes).contentHashCode()

    private companion object {
        val TAP_HOME = com.aiphone.assistant.touch.TouchAction(
            kind = TouchKind.KEY_HOME,
        )

        /**
         * 藏完悬浮窗后等一小会儿再截图/注入。
         *
         * 隐藏是异步的（窗口变更要经过 WindowManager 和 SurfaceFlinger
         * 才真正生效），立刻截图有可能拍到还没消失的那一帧。
         * 100ms 对 60Hz 来说是 6 帧，足够。
         */
        const val OVERLAY_SETTLE_MS = 100L

        /**
         * 上下文预算。
         *
         * 上下文**不做裁剪**（缓存按前缀匹配，一裁前缀就断，反而更贵），
         * 所以这里给个上限兜底，避免请求被服务端直接拒掉。
         *
         * 这两个数是保守值，按所用模型的窗口大小调整：
         * 窗口比它大就调大，比它小就调小。
         */
        const val CONTEXT_WARN_TOKENS = 100_000
        const val CONTEXT_STOP_TOKENS = 120_000
    }
}
