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

class Agent(
    private val controller: ChannelController, private val llm: LlmClient,
    private val settings: AppSettings, private val maxSteps: Int,
    private val selfPackage: String, private val logger: RunLogger?,
    private val listener: Listener,
) {
    interface Listener {
        fun onEvent(kind: EventKind, text: String, label: String?)
        fun onProgress(step: Int, maxSteps: Int)
        fun onFinished(success: Boolean, message: String)
        fun isStopRequested(): Boolean
    }
    enum class EventKind { THOUGHT, ACTION, RESULT, ERROR }

    @Volatile var stopRequested: Boolean = false
    private var promptTokens = 0; private var completionTokens = 0
    private var cacheHitTokens = 0; private var cacheMissTokens = 0

    suspend fun run(task: String) {
        val problem = controller.probe()
        if (problem != null) { fail(problem, "通道不可用"); return }
        logger?.line("通道就绪：${settings.mode.label}", "通道")
        val size = controller.screenSize()
        val w = size?.first ?: 0; val h = size?.second ?: 0
        if (w <= 0 || h <= 0) { fail("拿不到屏幕分辨率，无法把模型给的坐标换算成真实位置。", "通道"); return }
        logger?.line("屏幕：${w} x ${h}", "通道")
        logger?.line(llm.describe(), "模型")
        logger?.line(if (OverlayBus.isShowing) "悬浮窗已就绪" else "悬浮窗未启动（缺权限或未开）", "悬浮")
        ensureNotSelfForeground()
        val system = AgentPrompt.system(w, h)
        val history = mutableListOf<ChatTurn>()
        var lastHash = 0; var sameFrame = 0; var lastActionSig = ""; var repeatAction = 0; var lastResult: String? = null

        for (step in 1..maxSteps) {
            if (isStopped()) { finish(false, "你停止了任务（第 $step 步）"); return }
            listener.onProgress(step, maxSteps)
            logger?.section("第 $step / $maxSteps 步")
            OverlayBus.hide(); delay(OVERLAY_SETTLE_MS)
            val tree = withContext(Dispatchers.IO) { controller.readUiTree() }
            val nodeCount = tree?.lines()?.count { it.isNotBlank() } ?: 0
            logger?.line("控件树：$nodeCount 个元素", "UI")
            val shot = withContext(Dispatchers.IO) { controller.captureFrame() }
            OverlayBus.show()
            if (shot == null || shot.isEmpty()) {
                logger?.error("截图失败，跳过本轮", "截图")
                listener.onEvent(EventKind.ERROR, "截图失败，跳过本轮", "截图")
                delay(1500); continue
            }
            logger?.line("截图：${w}x$h，${shot.size} 字节", "截图")
            logger?.saveScreenshot(step, shot)
            val hash = md5(shot)
            if (hash == lastHash) sameFrame++ else sameFrame = 0
            lastHash = hash
            val interruptions = buildList {
                if (sameFrame >= 2) add("画面已经连续 $sameFrame 步没有任何变化，说明你上一个动作**没有生效**。不要再用同样的方式。换一个元素编号，或者换一种动作。")
                if (repeatAction >= 2) add("你已经连续 $repeatAction 次输出同一个动作了，它显然不work。这一步必须换一个完全不同的动作。")
            }.joinToString("\n")
            val userText = AgentPrompt.stepMessage(step = step, maxSteps = maxSteps, task = task,
                uiTree = tree, interruption = interruptions.ifBlank { null }, lastResult = lastResult)
            history.add(ChatTurn(ChatTurn.USER, userText))
            listener.onEvent(EventKind.THOUGHT, "正在请求模型 ...", "第 $step 步")
            val callStart = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { llm.chat(system, history, shot) }
            val elapsed = System.currentTimeMillis() - callStart
            when (result) {
                is LlmResult.Fail -> { logger?.error("模型调用失败：${result.message}", "模型"); fail(result.message, "模型出错"); return }
                is LlmResult.Ok -> {
                    promptTokens += result.promptTokens; completionTokens += result.completionTokens
                    cacheHitTokens += result.cacheHitTokens; cacheMissTokens += result.cacheMissTokens
                    if (result.promptTokens >= CONTEXT_STOP_TOKENS) {
                        val msg = "上下文已经 ${result.promptTokens} token，接近模型上限，为避免请求被拒先停下。开个新任务继续吧。"
                        logger?.error(msg, "上下文"); listener.onEvent(EventKind.ERROR, msg, "上下文过长"); finish(false, msg); return
                    }
                    if (result.promptTokens >= CONTEXT_WARN_TOKENS) logger?.warn("上下文已到 ${result.promptTokens} token，快满了", "上下文")
                    logger?.line("模型返回 ${elapsed}ms，token：输入 ${result.promptTokens} / 输出 ${result.completionTokens}（缓存命中 ${result.cacheHitTokens} / 未命中 ${result.cacheMissTokens}）", "模型")
                    logger?.section("模型原始输出"); result.text.lines().forEach { logger?.line("  $it") }
                    val parsed = ActionParser.parse(result.text, w, h)
                    parsed.thought?.let { logger?.line("思考：$it", "模型"); listener.onEvent(EventKind.THOUGHT, it, "第 $step 步 · 思考") }
                    if (parsed.finished) {
                        val summary = parsed.summary.ifBlank { "模型判断任务已完成" }
                        logger?.line("任务结束：$summary", "任务"); listener.onEvent(EventKind.RESULT, summary, "完成"); finish(true, summary); return
                    }
                    val action = parsed.action
                    if (action == null) {
                        val warning = parsed.warning ?: "没能解析出动作。"
                        logger?.warn("解析失败：$warning", "解析"); listener.onEvent(EventKind.ERROR, warning, "解析失败")
                        history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                        lastResult = "上一个输出有问题：$warning 请重新输出一个只包含 JSON 对象的动作，不要任何其他文字。"
                        continue
                    }
                    val sig = actionSignature(action.kind, action.targetIndex, action.x, action.y)
                    if (sig == lastActionSig) repeatAction++ else repeatAction = 0
                    lastActionSig = sig
                    val desc = AgentPrompt.describeAction(kind = action.kind, index = action.targetIndex,
                        x = action.x, y = action.y, x2 = action.x2, y2 = action.y2,
                        direction = action.direction, text = action.text, pkg = action.packageName, durationMs = action.durationMs)
                    logger?.line("动作：$desc", "执行"); listener.onEvent(EventKind.ACTION, desc, "第 $step 步 · 动作")
                    if (parsed.nextHint.isNotBlank()) logger?.line("下一步预告：${parsed.nextHint}", "模型")
                    OverlayBus.update(step, maxSteps, desc, parsed.nextHint)
                    if (isStopped()) { finish(false, "你停止了任务（执行第 $step 步动作之前）"); return }
                    OverlayBus.hide(); delay(OVERLAY_SETTLE_MS)
                    val execResult = withContext(Dispatchers.IO) { controller.execute(action) }
                    OverlayBus.show()
                    val ok = execResult == null; val resultText = if (ok) "已执行" else execResult!!
                    if (ok) { logger?.line("结果：$resultText", "执行"); listener.onEvent(EventKind.RESULT, resultText, "第 $step 步 · 结果") }
                    else { logger?.error("执行失败：$resultText", "执行"); listener.onEvent(EventKind.ERROR, resultText, "第 $step 步 · 失败") }
                    logger?.recordStep(step = step, thought = parsed.thought, action = desc, result = resultText, rawModelOutput = result.text, shot = null)
                    history.add(ChatTurn(ChatTurn.ASSISTANT, result.text))
                    lastResult = resultText
                    delay(waitAfter(action.kind))
                }
            }
        }
        finish(false, "到了最大步数 $maxSteps 还没做完。可以加大步数，或者把任务拆小一点。")
    }

    private suspend fun ensureNotSelfForeground() {
        val pkg = withContext(Dispatchers.IO) { controller.currentPackage() } ?: return
        if (pkg != selfPackage) return
        logger?.warn("控制应用自己在前台，先把界面让出来（回桌面）", "通道")
        withContext(Dispatchers.IO) { controller.execute(TAP_HOME) }
        delay(800)
    }

    private fun fail(message: String, label: String) { logger?.error(message, label); listener.onEvent(EventKind.ERROR, message, label); finish(false, message) }

    private fun finish(success: Boolean, message: String) {
        val total = promptTokens + completionTokens
        val cacheTotal = cacheHitTokens + cacheMissTokens
        val hitRate = if (cacheTotal > 0) "%.0f%%".format(cacheHitTokens * 100.0 / cacheTotal) else "无数据"
        logger?.line("本次共用 token：输入 $promptTokens / 输出 $completionTokens / 合计 $total", "统计")
        logger?.line("上下文缓存：命中 $cacheHitTokens / 未命中 $cacheMissTokens（命中率 $hitRate）", "统计")
        logger?.line("结束：$message", "任务"); logger?.close(); listener.onFinished(success, message)
    }

    private fun isStopped(): Boolean = stopRequested || listener.isStopRequested() || OverlayBus.stopRequested
    private fun actionSignature(kind: TouchKind, index: Int, x: Int, y: Int): String = "$kind|$index|$x|$y"

    private fun waitAfter(kind: TouchKind): Long = when (kind) {
        TouchKind.WAIT -> 0L; TouchKind.OPEN_APP -> 2500L
        TouchKind.KEY_HOME, TouchKind.KEY_BACK, TouchKind.KEY_RECENTS -> 900L
        TouchKind.SCROLL, TouchKind.SWIPE, TouchKind.FLICK -> 1200L
        TouchKind.INPUT_TEXT -> 900L; else -> 900L
    }

    private fun md5(bytes: ByteArray): Int = MessageDigest.getInstance("MD5").digest(bytes).contentHashCode()

    private companion object {
        val TAP_HOME = com.aiphone.assistant.touch.TouchAction(kind = TouchKind.KEY_HOME)
        const val OVERLAY_SETTLE_MS = 100L
        const val CONTEXT_WARN_TOKENS = 100_000
        const val CONTEXT_STOP_TOKENS = 120_000
    }
}
