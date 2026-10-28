package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.RunLogger
import com.aiphone.assistant.skill.SkillRegistry
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind

/**
 * AI 决策循环的对外门面。
 *
 * 真正的运行状态与分阶段实现见 [AgentSession]：
 * 观察界面、问模型、执行动作、收尾都不再堆在这一个类里。
 * 这个类只负责：
 *
 *  - 保留 `Listener` / `EventKind` / 常量等稳定 API；
 *  - 把构造参数交给 [AgentSession]；
 *  - 暴露 `run` / `review` / `finalHistory` / `stopRequested`。
 *
 * 这样 `MainActivity`、定时任务和测试都不需要知道内部拆成了几块。
 */
class Agent(
    controller: ChannelController,
    llm: LlmClient,
    settings: AppSettings,
    maxSteps: Int,
    selfPackage: String,
    skills: SkillRegistry,
    skillCatalogSnapshot: String? = null,
    memorySnapshot: String? = null,
    seedHistory: List<ChatTurn> = emptyList(),
    logger: RunLogger?,
    listener: Listener,
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

    private val session = AgentSession(
        controller = controller,
        llm = llm,
        settings = settings,
        maxSteps = maxSteps,
        selfPackage = selfPackage,
        skills = skills,
        skillCatalogSnapshot = skillCatalogSnapshot,
        memorySnapshot = memorySnapshot,
        seedHistory = seedHistory,
        logger = logger,
        listener = listener,
    )

    /** 用户中途叫停 */
    var stopRequested: Boolean
        get() = session.stopRequested
        set(value) {
            session.stopRequested = value
        }

    /** 这次任务结束时，模型那一侧攒下的完整历史。 */
    val finalHistory: List<ChatTurn>
        get() = session.finalHistory

    suspend fun run(task: String) = session.run(task)

    suspend fun review(instruction: String = ""): LlmResult = session.review(instruction)

    fun openMirrorPage() = session.openMirrorPage()

    companion object {
        val TAP_HOME: TouchAction
            get() = AgentLimits.TAP_HOME

        /** 纸盒回前台后等这么久再自动回迁，避免 ON_RESUME 抖动误触发（批 3） */
        const val AUTO_RETURN_DEBOUNCE_MS = AgentLimits.AUTO_RETURN_DEBOUNCE_MS

        internal fun hasSideEffect(action: TouchAction, beforeNodes: List<UiNode>): Boolean =
            AgentLimits.hasSideEffect(action, beforeNodes)

        internal fun labelHasSideEffect(node: UiNode): Boolean =
            AgentLimits.labelHasSideEffect(node)

        internal fun switchMustYieldToOpenApp(actionKind: TouchKind, targetPkg: String?): Boolean =
            AgentLimits.switchMustYieldToOpenApp(actionKind, targetPkg)
    }
}
