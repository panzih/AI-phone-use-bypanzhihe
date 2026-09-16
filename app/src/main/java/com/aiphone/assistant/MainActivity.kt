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
import com.aiphone.assistant.data.stepsLabel
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmConfig
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.log.LogExporter
import com.aiphone.assistant.memory.Conversation
import com.aiphone.assistant.memory.InsightStore
import com.aiphone.assistant.memory.LlmDistiller
import com.aiphone.assistant.memory.RawDistiller
import com.aiphone.assistant.memory.Turn
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.overlay.OverlayService
import com.aiphone.assistant.skill.SkillContext
import com.aiphone.assistant.skill.SkillRegistry
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
        // 边到边显示：内容铺到状态栏和导航栏下面，
        // 再靠 WindowInsets 给内容留出安全区。
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

    /**
     * 跳到系统的无障碍设置页。
     *
     * 无障碍服务**不能通过代码申请**，必须引导用户手动去系统设置里开。
     * 这是系统设计，没有绕过的方法。
     */
    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 跳到悬浮窗授权页。
     *
     * SYSTEM_ALERT_WINDOW 是特殊权限，没有 requestPermissions 那条路，
     * 只能带着包名跳过去让用户自己开。
     *
     * 注意各家 ROM 的入口名字不同：原生叫"显示在其他应用上层"，
     * 小米叫"显示在其他应用上层"、ColorOS 还可能要先解开
     * "受限制的设置"才能看到这个开关（Roubao 的 issue 里踩过）。
     */
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

/**
 * 界面根节点。
 *
 * 用本地状态驱动。这里的逻辑已经不少（设置、日志、记忆、AI 循环），
 * 但还没到必须上 ViewModel 的规模；等要加后台服务时再换，各界面不用改。
 */
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

    // 通知权限（Android 13+）。前台服务的常驻通知里挂了「急停」按钮；
    // 用户拒绝也没关系 —— 悬浮窗上的急停照常能用，所以不阻塞流程。
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响任务能否跑 */ }

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

    // 每次回到前台重新判断授权状态，并处理"闲置超时自动清空上下文"
    // 任务开始后要把主界面退到后台 —— 否则 Agent 操作的是纸盒自己
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
        // 每次回到前台都重新问一次系统，而不是只在启动时查一次 ——
        // 用户去系统设置开完无障碍回来，正好走到这里。
        authorized = AutoService.isConnected
        // 用户可能刚从系统设置里开完悬浮窗回来，每次前台都重查
        overlayGranted = Settings.canDrawOverlays(context)
        refreshStats()

        if (conversation.isIdleBeyond(settings.autoClearMinutes)) {
            persistConversationIfNeeded(conversation, settings, context)
            conversation.clear()
            if (settings.saveLogs) AppLog.i("上下文闲置超时，已清空", "记忆")
        }
    }

    /**
     * 跑一次 AI 操作任务。
     *
     * 这里是"把模型接进来"的那一层：拼 LlmClient → 建 Agent →
     * 把 Agent 的回调接到界面日志和落盘日志上。
     *
     * 真正的循环逻辑在 [Agent] 里，包括防死循环、历史裁剪、
     * 自己在前台时让位这些防护。
     */
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
                    thinkingLabel = settings.thinking.label,
                ),
            )
        } else null

        logger?.line("任务：$task", tag = "任务")
        logger?.line(
            if (settings.maxSteps <= 0) "最大步数：不限" else "最大步数：${settings.maxSteps}",
            tag = "任务",
        )
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
                thinking = settings.thinking,
            )
        )

        val agent = Agent(
            controller = controller,
            llm = llm,
            settings = settings,
            maxSteps = settings.maxSteps,
            selfPackage = context.packageName,
            // 技能注册表：模型用 use_skill 主动要"屏幕上没有的信息"。
            // 传 applicationContext —— 它会活到任务结束，不能攥着 Activity
            skills = SkillRegistry(
                SkillContext(context.applicationContext, controller),
            ),
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
                    progress = "AI 正在执行 · " + stepsLabel(step, maxSteps)
                }

                override fun onFinished(success: Boolean, message: String) {
                    conversation.add(
                        Turn(if (success) Turn.Role.AI else Turn.Role.ACTION, message)
                    )
                    progress = ""

                    // 开了记忆就让 AI 把这一趟归纳成一条「用户洞察」。
                    // 放在这里而不是等上下文被清空 —— 任务刚结束时记录最新鲜，
                    // 而且用户可能几个月都不手动清一次上下文。
                    memorizeAfterTask(
                        scope = scope,
                        context = context.applicationContext,
                        settings = settings,
                        conversation = conversation,
                        llm = llm,
                        onDone = { refreshStats() },
                    )

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
            // 兜底：Agent 万一提前抛了，也要收口，否则 run.log 停在半截
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

            // 通知权限：拒绝也不拦流程，只是少了通知栏那个急停按钮
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            // 起悬浮窗。没权限时 OverlayService 会自己退出，
            // 任务照常跑，只是用户看不到进度和急停按钮。
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

            // 把主界面让开，否则 Agent 第一步要额外按一次 Home，
            // 而且中间那一下用户会看到纸盒自己的界面被当成操作对象。
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

    /** 清空上下文。开了"保存记忆"就先把内容沉淀成 md，再清 */
    fun clearContext() {
        val had = conversation.size
        // 沉淀是挂起操作（将来要调模型），所以必须进协程 ——
        // 直接在点击回调里调 suspend 函数是编译不过的。
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
                appVersion = appVersion,
                progress = progress,
                toast = toast,
            ),
            onSettingsClick = { screen = Screen.SETTINGS },
            onInputChange = { input = it },
            onSubmit = { submit() },
            onStop = {
                // 只是"请求停止"。Agent 会在每一步开始前和执行动作前检查，
                // 不会把正在发出的那一次注入砍断。
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
                appVersion = appVersion,
                toast = toast,
            ),
            onSettingsChange = { next ->
                settings = next
                store.save(next)
                if (next.saveLogs) {
                    AppLog.i(
                        "设置变更：通道=${next.mode.label} 模型=${next.modelName} " +
                            "最大步数=${next.maxSteps} " +
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

/**
 * 清空上下文之前，把内容沉淀成 md。
 *
 * **只在「开启记忆」关着的时候才走这条路。** 开着的时候内容在任务结束时
 * 就已经被 AI 归纳过了，这里再原样存一份只是重复的噪声。
 *
 * 两个开关的分工：
 *   开启记忆  任务结束后让 AI 归纳一次（真正会"学到东西"的那条路）
 *   保存记忆  没开记忆时，清空之前原样转存一份，免得内容直接丢掉
 */
private suspend fun persistConversationIfNeeded(
    conversation: Conversation,
    settings: AppSettings,
    context: android.content.Context,
) {
    if (!settings.keepMemory) return
    // 开记忆时任务结束已经归纳过了，不再重复
    if (settings.memoryEnabled) return
    val turns = conversation.snapshot()
    if (turns.isEmpty()) return
    runCatching {
        val insight = RawDistiller().distill(turns)
        if (insight != null) InsightStore.save(context, insight)
    }
}

/**
 * 任务结束时让 AI 归纳一次记忆。
 *
 * 为什么放在**任务结束**而不是等上下文被清空：这时候记录最新鲜，
 * 而且用户可能几个月都不手动清一次上下文 —— 那样就永远学不到东西。
 *
 * 成本是一次纯文字的模型调用（不带图）。所以只在「开启记忆」打开时才做。
 */
private fun memorizeAfterTask(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    settings: AppSettings,
    conversation: Conversation,
    llm: LlmClient,
    onDone: () -> Unit,
) {
    if (!settings.memoryEnabled) return
    val turns = conversation.snapshot()
    if (turns.isEmpty()) return
    scope.launch {
        runCatching {
            val insight = LlmDistiller(llm).distill(turns)
            if (insight != null) {
                InsightStore.save(context, insight)
                if (settings.saveLogs) AppLog.i("已沉淀记忆：${insight.title}", "记忆")
            }
        }
        onDone()
    }
}

/** 字节数转可读文本 */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
