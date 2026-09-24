package com.aiphone.assistant.ui

import com.aiphone.assistant.data.AppSettings

/**
 * 界面状态与数据模型。
 *
 * 这一层刻意和平台能力解耦：底层通道（无障碍 / 将来的 ADB）怎么实现，
 * 界面都不关心，只消费这里的状态。
 */

/** 一条日志的类型，决定它在界面上用什么底色。 */
enum class LogKind {
    /** AI 的思考过程 */
    THOUGHT,

    /** AI 决定执行的动作 */
    ACTION,

    /** 动作执行结果 */
    RESULT,

    /** 出错了 */
    ERROR,

    /**
     * 系统消息：不是 AI 说的，也不是动作结果，而是"发生了什么" ——
     * 比如"上下文已清空，从这里开始是一段新对话"。
     *
     * 单独一类是为了让它**看起来就不一样**：用户得能一眼看出
     * "消息消失"是有人管的，而不是界面出错了。
     */
    SYSTEM,
}

/** 界面上显示的一条记录。 */
data class LogEntry(
    val id: String,
    val kind: LogKind,
    val text: String,
    val label: String? = null,
)

/** 主界面的完整状态。 */
data class MainUiState(
    /** 当前页面 */
    val screen: Screen = Screen.CONTROL,

    /** 底部输入框的内容 */
    val input: String = "",

    /** 是否正在执行任务（决定底部按钮是"发送"还是"停止"） */
    val isRunning: Boolean = false,

    /** 执行中的进度文字，例如 "AI 正在执行 · 第 3 / 30 步" */
    val progress: String = "",

    /** 中间那块显示的日志（AI 的思考 / 动作 / 结果）。空的时候显示 logo */
    val logs: List<LogEntry> = emptyList(),

    /** 全部设置 */
    val settings: AppSettings = AppSettings(),

    /** 无障碍服务当前是否真的连着（不是"设置里开着"就算） */
    val authorized: Boolean = false,

    /** 悬浮窗权限（SYSTEM_ALERT_WINDOW）。没有它就没有进度面板和急停按钮 */
    val overlayGranted: Boolean = false,

    /** Shizuku 当前状态（给设置页「副屏」状态行用），例如「已就绪」 */
    val shizukuState: String = "",

    /** 设置页显示的日志统计，例如 "共 3 次记录 · 1.2 MB" */
    val logStats: String = "",

    /**
     * 记忆的规模，例如「记忆：7 条 · 4.1 KB」。
     *
     * 显示成一行文本而不是两个数字：界面不需要分别用它们做判断，
     * 拼好再传反而少一处格式化的地方。
     */
    val memoryStats: String = "",

    /** 自动截图的规模，例如「自动截图 37 张 · 2.8 MB」 */
    val autoCapStats: String = "",

    /** 应用版本号，给设置页的「关于」显示 */
    val appVersion: String = "",

    // ---------- 操作记录 ----------

    /** 正在录制 */
    val recordingActive: Boolean = false,

    /** 已录到的步骤（一行一步，给人看的） */
    val recordedSteps: List<String> = emptyList(),

    /** 已经学会的技能 */
    val macros: List<MacroSummary> = emptyList(),

    /** 正在让 AI 学习（学习要调模型，可能几秒） */
    val learning: Boolean = false,

    // ---------- 定时任务 ----------

    /** 已有的定时任务 */
    val schedules: List<com.aiphone.assistant.schedule.Schedule> = emptyList(),

    /** 系统是否允许精确闹钟。false 时只能不精确触发，可能晚几分钟 */
    val exactAlarmGranted: Boolean = true,

    /** 一次性提示（导出结果之类），显示完由界面清掉 */
    val toast: String? = null,
)
