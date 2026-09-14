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

    /** 中间那块显示的日志（AI 的思考 / 动作 / 结果）。空的时候显示 logo */
    val logs: List<LogEntry> = emptyList(),

    /** 全部设置 */
    val settings: AppSettings = AppSettings(),

    /** 无障碍服务当前是否真的连着（不是"设置里开着"就算） */
    val authorized: Boolean = false,

    /** 设置页显示的日志统计，例如 "共 3 次记录 · 1.2 MB" */
    val logStats: String = "",

    /** 已沉淀的用户洞察份数 */
    val insightCount: Int = 0,

    /** 一次性提示（导出结果之类），显示完由界面清掉 */
    val toast: String? = null,
)
