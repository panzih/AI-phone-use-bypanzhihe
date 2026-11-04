package com.aiphone.assistant.agent

/**
 * 入口层的两条纯判据（ROADMAP §1「测试兜底」，2026-11）。
 *
 * 抽出来的背景：0.10.6 的「点发送就崩」死在入口层的提前 return 上 ——
 * 模型配置检查放到了 startForegroundService 之后，runTask 提前 return 时
 * finally 立刻 stopService，悬浮窗服务还没 startForeground 就被停，
 * 系统抛 ForegroundServiceDidNotStartInTimeException。而当时 237 例单测
 * 全绿，因为这条路径根本没人测。
 *
 * 结构性约定（这条只能靠代码评审保住，单测测不了 Compose 层）：
 * **入口里任何提前 return 的检查，都必须放在 startForegroundService 之前**。
 * 现在的顺序是：空输入 → 忙碌判断（[busyInput]） → 正在删日志 →
 * 模型配置（[com.aiphone.assistant.llm.LlmConfig.missingApiKey]，与网络层共用）
 * → 无障碍 / Shizuku → 才轮到起悬浮窗。
 */
internal object EntryGuard {

    /** 任务运行中用户又发了一条输入时该怎么办 */
    enum class BusyInput {
        /** 暂停中：并进下一轮（OverlayBus.pushUserNote） */
        MERGE_AS_NOTE,

        /** 运行中：提示但不吞 —— 输入框原样保留，绝不静默丢弃（ROADMAP §3） */
        PROMPT_BUSY,
    }

    /**
     * 忙碌时收到一条输入的处置。
     *
     * @return null = 不忙，走正常的新任务流程
     */
    fun busyInput(isRunning: Boolean, isPaused: Boolean): BusyInput? = when {
        !isRunning -> null
        isPaused -> BusyInput.MERGE_AS_NOTE
        else -> BusyInput.PROMPT_BUSY
    }
}
