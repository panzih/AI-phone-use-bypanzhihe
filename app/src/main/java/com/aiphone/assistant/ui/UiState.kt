package com.aiphone.assistant.ui

import com.aiphone.assistant.data.AppSettings

enum class LogKind { THOUGHT, ACTION, RESULT, ERROR }

data class LogEntry(
    val id: String,
    val kind: LogKind,
    val text: String,
    val label: String? = null,
)

data class MainUiState(
    val screen: Screen = Screen.CONTROL,
    val input: String = "",
    val isRunning: Boolean = false,
    val progress: String = "",
    val logs: List<LogEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val authorized: Boolean = false,
    val overlayGranted: Boolean = false,
    val logStats: String = "",
    val insightCount: Int = 0,
    val toast: String? = null,
)
