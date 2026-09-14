package com.aiphone.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiphone.assistant.agent.Agent
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.SettingsStore
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmConfig
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.log.LogExporter
import com.aiphone.assistant.memory.Conversation
import com.aiphone.assistant.memory.InsightStore
import com.aiphone.assistant.memory.RawDistiller
import com.aiphone.assistant.memory.Turn
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.overlay.OverlayService
import com.aiphone.assistant.ui.LogEntry
import com.aiphone.assistant.ui.LogKind
import com.aiphone.assistant.ui.MainScreen
import com.aiphone.assistant.ui.MainUiState
import com.aiphone.assistant.ui.Screen
import com.aiphone.assistant.ui.SettingsScreen
import com.aiphone.assistant.ui.theme.AiPhoneTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var controller: ChannelController
    private lateinit var store: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        controller = ChannelController(applicationContext)
        store = SettingsStore(this)


        setContent {
            AiPhoneTheme {
                AppRoot(
                    controller = controller,
                    store = store,
                    appVersion = appVersion(),
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                )
            }
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun AppRoot(
    controller: ChannelController,
    store: SettingsStore,
    appVersion: String,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var screen by remember { mutableStateOf(Screen.CONTROL) }
    var input by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(store.load()) }
    var isRunning by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(AutoService.isConnected) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var logStats by remember { mutableStateOf("") }
    var insightCount by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    val logs = remember { mutableStateListOf<LogEntry>() }
    val conversation = remember { Conversation() }

    fun addLog(kind: LogKind, text: String, label: String? = null) {
        logs.add(
            LogEntry(
                id = "l${logs.size}_${System.currentTimeMillis()}",
                kind = kind,
                text = text,
                label = label,
            )
        )
    }

    fun refreshStats() {
        val runs = AppLog.listRuns(context)
        val total = runs.sumOf { run -> run.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        logStats = context.getString(R.string.settings_log_stats, runs.size, formatBytes(total))
        insightCount = InsightStore.list(context).size
    }

    val hostActivity = LocalContext.current as? ComponentActivity

    var resumeTick by remember { mutableIntStateOf(0) }
    val hostLifecycle = hostActivity?.lifecycle
    DisposableEffect(hostLifecycle) {
        val observer = hostLifecycle?.let {
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeTick++
            }
        }
        if (hostLifecycle != null && observer != null) hostLifecycle.addObserver(observer)
        onDispose {
            if (hostLifecycle != null && observer != null) hostLifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(resumeTick) {
        authorized = AutoService.isConnected
        overlayGranted = Settings.canDrawOverlays(context)
        refreshStats()

        if (conversation.isIdleBeyond(settings.autoClearMinutes)) {
            persistConversationIfNeeded(conversation, settings, context)
            conversation.clear()
            if (settings.saveLogs) AppLog.i("上下文闲置超时，已清空", "记忆")
        }
    }

    suspend fun runTask(task: String) {
        val logger = if (settings.saveLogs) {
            AppLog.start(
                context = context,
                task = task,
                env = AppLog.environment(
                    context = context,
                    appVersion = appVersion,
                    channelLabel = settings.mode.label,
                    modelName = settings.modelName,
                    baseUrl = settings.baseUrl,
                    detail = settings.detail,
                ),
            )
        } else null

        logger?.line("任务：$task", tag = "任务")
        logger?.line("最大步数：${settings.maxSteps}", tag = "任务")
        addLog(LogKind.ACTION, task, "指令")

        if (settings.apiKey.isBlank()) {
            val msg = "还没填 API Key。到「设置 → 模型」里填一个再试。"
            logger?.error(msg, "模型")
            addLog(LogKind.ERROR, msg, "模型未配置")
            logger?.close()
            return
        }

        val llm = LlmClient(
            LlmConfig(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.modelName,
                detail = settings.detail,
            )
        )

        val agent = Agent(
            controller = controller,
            llm = llm,
            settings = settings,
            maxSteps = settings.maxSteps,
            selfPackage = context.packageName,
            logger = logger,
            listener = object : Agent.Listener {
                override fun onEvent(kind: Agent.EventKind, text: String, label: String?) {
                    addLog(
                        when (kind) {
                            Agent.EventKind.THOUGHT -> LogKind.THOUGHT
                            Agent.EventKind.ACTION -> LogKind.ACTION
                            Agent.EventKind.RESULT -> LogKind.RESULT
                            Agent.EventKind.ERROR -> LogKind.ERROR
                        },
                        text,
                        label,
                    )
                }

                override fun onProgress(step: Int, maxSteps: Int) {
                    progress = "AI 正在执行 · 第 $step / $maxSteps 步"
                }

                override fun onFinished(success: Boolean, message: String) {
                    conversation.add(
                        Turn(if (success) Turn.Role.AI else Turn.Role.ACTION, message)
                    )
                    progress = ""
                    addLog(
                        if (success) LogKind.RESULT else LogKind.ERROR,
                        message,
                        if (success) "任务完成" else "任务结束",
                    )
                }

                override fun isStopRequested(): Boolean = stopRequested
            },
        )

        try {
            agent.run(task)
        } finally {
            logger?.close()
        }
    }

    fun submit() {
        val task = input.trim()
        if (task.isBlank() || isRunning) return
        input = ""
        stopRequested = false
        OverlayBus.clearStop()
        conversation.add(Turn(Turn.Role.USER, task))

        scope.launch {
            isRunning = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (Settings.canDrawOverlays(context)) {
                OverlayService.start(context)
            } else {
                addLog(
                    LogKind.ERROR,
                    "没有悬浮窗权限，这次看不到进度面板和急停按钮。" +
                        "到「设置 → 操作授权 → 悬浮窗」里开一下。",
                    "悬浮窗不可用",
                )
            }

            hostActivity?.moveTaskToBack(true)

            try {
                runTask(task)
            } catch (t: Throwable) {
                addLog(LogKind.ERROR, "执行出错：${t.message}", "错误")
                if (settings.saveLogs) AppLog.e("执行出错：$t", "任务")
            } finally {
                isRunning = false
                progress = ""
                OverlayService.stop(context)
                refreshStats()
            }
        }
    }

    fun clearContext() {
        val had = conversation.size
        scope.launch {
            persistConversationIfNeeded(conversation, settings, context)
            conversation.clear()
            if (settings.saveLogs) AppLog.i("手动清空上下文（原有 $had 轮）", "记忆")
            refreshStats()
        }
        toast = context.getString(R.string.settings_clear_context_done)
    }

    fun exportLatest() {
        val f = LogExporter.exportLatest(context)
        if (f == null) {
            toast = context.getString(R.string.settings_export_none)
            return
        }
        toast = context.getString(R.string.settings_export_done, f.name)
        LogExporter.share(context, f, "纸盒日志")
    }

    fun exportAll() {
        val f = LogExporter.exportAll(context)
        if (f == null) {
            toast = context.getString(R.string.settings_export_none)
            return
        }
        toast = context.getString(R.string.settings_export_done, f.name)
        LogExporter.share(context, f, "纸盒日志")
    }

    when (screen) {
        Screen.CONTROL -> MainScreen(
            state = MainUiState(
                screen = Screen.CONTROL,
                input = input,
                isRunning = isRunning,
                logs = logs,
                settings = settings,
                authorized = authorized,
                overlayGranted = overlayGranted,
                logStats = logStats,
                insightCount = insightCount,
                progress = progress,
                toast = toast,
            ),
            onSettingsClick = { screen = Screen.SETTINGS },
            onInputChange = { input = it },
            onSubmit = { submit() },
            onStop = {
                stopRequested = true
                addLog(LogKind.ERROR, "已请求停止，等当前这一步走完", "停止")
            },
            onControlPhone = { screen = Screen.CONTROL },
        )

        Screen.SETTINGS -> SettingsScreen(
            state = MainUiState(
                settings = settings,
                authorized = authorized,
                overlayGranted = overlayGranted,
                logStats = logStats,
                insightCount = insightCount,
                toast = toast,
            ),
            onSettingsChange = { next ->
                settings = next
                store.save(next)
                if (next.saveLogs) {
                    AppLog.i(
                        "设置变更：通道=${next.mode.label} 模型=${next.modelName} " +
                            "精度=${next.detail} 最大步数=${next.maxSteps} " +
                            "记忆=${next.memoryEnabled} 自动清空=${next.autoClear.label}",
                        "设置",
                    )
                }
            },
            onBack = { screen = Screen.CONTROL; toast = null },
            onGotoAuth = { onOpenAccessibilitySettings() },
            onOpenOverlaySettings = onOpenOverlaySettings,
            onClearContext = { clearContext() },
            onExportLatest = { exportLatest() },
            onExportAll = { exportAll() },
        )
    }
}

private suspend fun persistConversationIfNeeded(
    conversation: Conversation,
    settings: AppSettings,
    context: android.content.Context,
) {
    if (!settings.keepMemory) return
    val turns = conversation.snapshot()
    if (turns.isEmpty()) return
    runCatching {
        val insight = RawDistiller().distill(turns)
        if (insight != null) InsightStore.save(context, insight)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
