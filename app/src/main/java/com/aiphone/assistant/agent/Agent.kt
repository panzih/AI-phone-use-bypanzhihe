package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.RunLogger
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

        // ---- 2. 别拍到自己 ----
        ensureNotSelfForeground()

        // ---- 3. 开跑 ----
        val system = AgentPrompt.system(w, h)
        val history = mutableListOf<ChatTurn>()

        var lastHash = 0
        var sameFrame = 0
        var lastActionSig = ""
        var repeatAction = 0

        for (step in 1..maxSteps) {
            if (stopRequested || listener.isStopRequested()) {
                finish(false, "你停止了任务（第 $step 步）")
                return
            }

            listener.onProgress(step, maxSteps)
            logger?.section("第 $step / $maxSteps 步")

            // ---- 看一眼 ----
            val tree = withContext(Dispatchers.IO) { controller.readUiTree() }
            val nodeCount = tree?.lines()?.count { it.isNotBlank() } ?: 0
            logger?.line("控件树：$nodeCount 个元素", "UI")

            val shot = withContext(Dispatchers.IO) { controller.captureFrame() }
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
            )

            // ---- 问模型 ----
            listener.onEvent(EventKind.THOUGHT, "正在请求模型 ...", "第 $step 步")
            val callStart = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                llm.chat(system, history, userText, shot)
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
                    logger?.line(
                        "模型返回 ${elapsed}ms，token：输入 ${result.promptTokens} / " +
                            "输出 ${result.completionTokens}",
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
                        // 把失败原因回灌给模型，让它重出 —— 比直接终止有用得多
                        history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                        history.add(
                            ChatTurn(
                                ChatTurn.USER,
                                "你上一个输出有问题：$warning\n" +
                                    "请重新输出一个**只包含 JSON 对象**的动作，不要任何其他文字。",
                            )
                        )
                        trimHistory(history)
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

                    // 执行前再检查一次停止 —— 把急停的响应窗口从"一步"
                    // 缩短到"一次模型调用"
                    if (stopRequested || listener.isStopRequested()) {
                        finish(false, "你停止了任务（执行第 $step 步动作之前）")
                        return
                    }

                    val execResult = withContext(Dispatchers.IO) { controller.execute(action) }
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
                    history.add(
                        ChatTurn(
                            ChatTurn.USER,
                            "第 $step 步执行结果：$resultText",
                        )
                    )
                    trimHistory(history)

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
        logger?.line(
            "本次共用 token：输入 $promptTokens / 输出 $completionTokens / 合计 $total",
            "统计",
        )
        logger?.line("结束：$message", "任务")
        logger?.close()
        listener.onFinished(success, message)
    }

    /** 动作签名，用来识别"反复做同一件事" */
    private fun actionSignature(kind: TouchKind, index: Int, x: Int, y: Int): String =
        "$kind|$index|$x|$y"

    /**
     * 历史裁剪。
     *
     * 保留最近若干轮。上下文无限涨的话，token 成本是平方级增长的，
     * 而且模型对很早的内容本来也不敏感。
     */
    private fun trimHistory(history: MutableList<ChatTurn>, keepRounds: Int = 12) {
        val max = keepRounds * 2
        while (history.size > max) {
            history.removeAt(0)
        }
    }

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
    }
}
