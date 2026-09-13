package com.aiphone.assistant.ui

/**
 * 界面状态与数据模型。
 *
 * 这一层刻意和平台能力解耦：底层通道（ADB / 无障碍）怎么实现，
 * 界面都不关心，只消费这里的状态。
 */

import android.graphics.Bitmap

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

/** 模型配置，对应设置页第一行。 */
data class ModelConfig(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val modelName: String = "deepseek-flash",
    /** 图片精度：original 保留原图，low 压到 512x512 */
    val detail: String = "original",
)

/** 底层通道的连接状态。 */
enum class ChannelStatus {
    /** 还没检查 */
    UNKNOWN,

    /** 正在连接 */
    CONNECTING,

    /** 可用 */
    READY,

    /** 不可用，具体原因在 message 里 */
    UNAVAILABLE,
}

/**
 * ADB 预览区的状态。
 *
 * 对应需求："中间那个区域是留给 ADB 的一个预览通道"。
 * 选 ADB 时显示画面，选无障碍时不显示（直接跳目标 App）。
 */
data class PreviewState(
    val status: ChannelStatus = ChannelStatus.UNKNOWN,
    /** 不可用时的原因，或连接中的提示 */
    val message: String? = null,
    /** 当前这一帧画面 */
    val frame: Bitmap? = null,
    /** 屏幕分辨率，坐标换算和界面标注要用 */
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    /** 画面几乎全黑，通常意味着这个页面有 DRM 保护 */
    val isProbablySecure: Boolean = false,
) {
    /** 预览区该显示画面还是显示占位说明 */
    val hasFrame: Boolean get() = frame != null
}

/** 主界面的完整状态。 */
data class MainUiState(
    /** 当前页面 */
    val screen: Screen = Screen.CONTROL,
    /** 底部输入框的内容 */
    val input: String = "",
    /** 是否正在执行任务（决定底部按钮是"发送"还是"停止"） */
    val isRunning: Boolean = false,
    /** 中间那块显示的日志（AI 的思考 / 动作 / 结果） */
    val logs: List<LogEntry> = emptyList(),
    /** 预览区状态 */
    val preview: PreviewState = PreviewState(),
    val model: ModelConfig = ModelConfig(),
)
