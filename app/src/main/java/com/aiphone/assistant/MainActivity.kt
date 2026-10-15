package com.aiphone.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import com.aiphone.assistant.agent.AgentPrompt
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.CarriedContext
import com.aiphone.assistant.data.ContextPolicy
import com.aiphone.assistant.data.ContextStore
import com.aiphone.assistant.display.MirrorActivity
import com.aiphone.assistant.display.VirtualDisplayManager
import com.aiphone.assistant.data.SettingsStore
import com.aiphone.assistant.data.ThinkingMode
import com.aiphone.assistant.data.stepsLabel
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmConfig
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.log.AutoCapture
import com.aiphone.assistant.log.LogExporter
import com.aiphone.assistant.memory.Conversation
import com.aiphone.assistant.memory.MemoryStore
import com.aiphone.assistant.memory.MemoryWriter
import com.aiphone.assistant.memory.Turn
import com.aiphone.assistant.record.MacroLearner
import com.aiphone.assistant.record.MacroStore
import com.aiphone.assistant.record.Recorder
import com.aiphone.assistant.record.RecordingShortcut
import com.aiphone.assistant.schedule.ScheduleReceiver
import com.aiphone.assistant.schedule.Schedule
import com.aiphone.assistant.schedule.ScheduleStore
import com.aiphone.assistant.schedule.Scheduler
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.overlay.OverlayService
import com.aiphone.assistant.shell.ShizukuBridge
import com.aiphone.assistant.skill.SkillContext
import com.aiphone.assistant.skill.SkillRegistry
import com.aiphone.assistant.ui.AboutScreen
import com.aiphone.assistant.ui.AutoReturnOnForegroundEffect
import com.aiphone.assistant.ui.LogEntry
import com.aiphone.assistant.ui.LogKind
import com.aiphone.assistant.ui.MainScreen
import com.aiphone.assistant.ui.MacroSummary
import com.aiphone.assistant.ui.MainUiState
import com.aiphone.assistant.ui.PermissionsScreen
import com.aiphone.assistant.ui.RecordingScreen
import com.aiphone.assistant.ui.ScheduleScreen
import com.aiphone.assistant.ui.Screen
import com.aiphone.assistant.ui.SettingsScreen
import com.aiphone.assistant.ui.theme.AiPhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var controller: ChannelController
    private lateinit var store: SettingsStore
    private val pendingTask = mutableStateOf<String?>(null)
    private val pendingUseVirtualDisplay = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controller = ChannelController(applicationContext)
        store = SettingsStore(this)
        AppLog.attach(this)
        readScheduledIntent(intent)
        runCatching { Scheduler.rescheduleAll(applicationContext) }
        setContent {
            AiPhoneTheme {
                AppRoot(
                    controller = controller,
                    store = store,
                    appVersion = appVersion(),
                    pendingTask = pendingTask.value,
                    pendingUseVirtualDisplay = pendingUseVirtualDisplay.value,
                    onPendingTaskHandled = {
                        pendingTask.value = null
                        pendingUseVirtualDisplay.value = false
                    },
                    onRequestExactAlarm = { requestExactAlarm() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readScheduledIntent(intent)
    }

    private fun readScheduledIntent(intent: Intent?) {
        val task = intent?.getStringExtra(EXTRA_RUN_TASK)?.trim().orEmpty()
        if (task.isBlank()) return
        val id = intent?.getStringExtra(EXTRA_SCHEDULE_ID)
        pendingTask.value = task
        pendingUseVirtualDisplay.value =
            intent?.getBooleanExtra(EXTRA_SCHEDULE_VIRTUAL_DISPLAY, false) == true
        id?.let { ScheduleReceiver.cancelNotification(this, it) }
    }

    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        }
    }

    companion object {
        const val EXTRA_RUN_TASK = "run_task"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_SCHEDULE_VIRTUAL_DISPLAY = "schedule_virtual_display"
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
    pendingTask: String?,
    pendingUseVirtualDisplay: Boolean,
    onPendingTaskHandled: () -> Unit,
    onRequestExactAlarm: () -> Unit,
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
    var exporting by remember { mutableStateOf(false) }
    var deletingLogs by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(AutoService.isConnected) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var logStats by remember { mutableStateOf("") }
    var memoryStats by remember { mutableStateOf("") }
    var autoCapStats by remember { mutableStateOf("") }
    var shizukuState by remember { mutableStateOf("") }
    var taskTurnStart by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    var recordingActive by remember { mutableStateOf(Recorder.isRecording) }
    var recordedSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    var macros by remember { mutableStateOf(loadMacroSummaries(context)) }
    var learning by remember { mutableStateOf(false) }

    var schedules by remember { mutableStateOf(ScheduleStore.loadAll(context)) }
    var exactAlarmGranted by remember { mutableStateOf(Scheduler.canScheduleExact(context)) }

    val currentCatalog = remember {
        SkillRegistry(
            ctx = SkillContext(context.applicationContext, controller),
            extra = MacroStore.loadAll(context),
        ).catalog()
    }

    val diskContext = remember { ContextStore.load(context) }
    val savedContext = remember(diskContext) {
        diskContext?.takeIf {
            it.promptVersion == AgentPrompt.VERSION &&
                it.modelName == settings.modelName &&
                it.skillCatalog == currentCatalog
        }
    }
    val logs = remember {
        mutableStateListOf<LogEntry>().apply {
            addAll(savedContext?.entries ?: emptyList())
            if (savedContext == null && diskContext?.entries?.isNotEmpty() == true) {
                add(
                    LogEntry(
                        id = "prompt_reset_${System.currentTimeMillis()}",
                        kind = LogKind.SYSTEM,
                        text = "提示词版本 / 模型 / 技能目录变化，已开新上下文",
                        label = "上下文",
                    )
                )
            }
        }
    }
    val conversation = remember {
        Conversation().apply {
            restore(
                savedTurns = savedContext?.turns ?: emptyList(),
                savedActivityAt = savedContext?.lastActivityAt ?: 0L,
            )
        }
    }

    var runOnVirtualDisplay by remember { mutableStateOf(false) }
    var modelHistory by remember { mutableStateOf(savedContext?.history ?: emptyList()) }
    var modelFingerprints by remember { mutableStateOf(savedContext?.fingerprints ?: emptyList()) }
    var contextMemory by remember { mutableStateOf(savedContext?.memorySnapshot ?: "") }
    var contextSkillCatalog by remember {
        mutableStateOf(savedContext?.skillCatalog ?: currentCatalog)
    }
    var contextModelName by remember {
        mutableStateOf(savedContext?.modelName ?: settings.modelName)
    }

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

    LaunchedEffect(diskContext, savedContext) {
        if (savedContext == null && diskContext?.entries?.isNotEmpty() == true &&
            settings.saveLogs
        ) {
            AppLog.w("提示词版本 / 模型 / 技能目录变化，旧上下文作废、已开新上下文", "上下文")
        }
    }

    LaunchedEffect(logs.size, conversation.size, modelHistory, modelFingerprints, contextMemory, contextSkillCatalog, contextModelName) {
        if (logs.isEmpty() && conversation.size == 0 && modelHistory.isEmpty()) return@LaunchedEffect
        delay(600)
        withContext(Dispatchers.IO) {
            ContextStore.save(
                context = context,
                entries = logs.toList(),
                turns = conversation.snapshot(),
                lastActivityAt = conversation.lastActivityAt,
                history = modelHistory,
                fingerprints = modelFingerprints,
                memorySnapshot = contextMemory,
                promptVersion = AgentPrompt.VERSION,
                modelName = contextModelName,
                skillCatalog = contextSkillCatalog,
            )
        }
    }

    fun refreshStats() {
        macros = loadMacroSummaries(context)
        schedules = ScheduleStore.loadAll(context)
        exactAlarmGranted = Scheduler.canScheduleExact(context)
        shizukuState = ShizukuBridge.state(context).label
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val runs = AppLog.listRuns(context)
                val total = runs.sumOf { run ->
                    run.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
                val log = context.getString(
                    R.string.settings_log_stats, runs.size, formatBytes(total)
                )
                val (memCount, memChars) = MemoryStore.stats(context)
                val mem = if (memCount > 0) {
                    context.getString(
                        R.string.settings_memory_stats, memCount,
                        formatBytes(memChars.toLong())
                    )
                } else ""
                val capCount = AutoCapture.count(context)
                val cap = if (capCount > 0) {
                    context.getString(
                        R.string.log_autocap_stats, capCount,
                        formatBytes(AutoCapture.totalBytes(context)),
                    )
                } else ""
                Triple(log, mem, cap)
            }
            logStats = snapshot.first
            memoryStats = snapshot.second
            autoCapStats = snapshot.third
        }
    }

    fun clearContextWithMarker(reason: String) {
        val had = conversation.size
        conversation.clear()
        logs.clear()
        modelHistory = emptyList()
        modelFingerprints = emptyList()
        contextMemory = ""
        contextSkillCatalog = SkillRegistry(
            ctx = SkillContext(context.applicationContext, controller),
            extra = MacroStore.loadAll(context),
        ).catalog()
        contextModelName = settings.modelName
        ContextStore.clear(context)
        addLog(LogKind.SYSTEM, reason, context.getString(R.string.context_label))
        if (settings.saveLogs) AppLog.i("清空上下文（原有 $had 轮）：$reason", "上下文")
        refreshStats()
    }

    fun stopRecording() {
        if (!Recorder.isRecording) return
        val rec = Recorder.stop()
        OverlayBus.exitRecording()
        recordingActive = false
        recordedSteps = Recorder.labels()
        toast = context.getString(R.string.recording_toast_stopped, rec.steps.size)
        if (settings.saveLogs) AppLog.i("录制结束，共 ${rec.steps.size} 步", "操作记录")
    }

    fun startRecording() {
        Recorder.start()
        OverlayBus.enterRecording()
        recordingActive = true
        recordedSteps = emptyList()
        toast = context.getString(R.string.recording_toast_started)
        scope.launch {
            while (Recorder.isRecording) {
                delay(400)
                recordedSteps = Recorder.labels()
                OverlayBus.updateRecording(Recorder.count)
                if (OverlayBus.recordingStopRequested) {
                    stopRecording()
                    break
                }
            }
        }
    }

    fun discardRecording() {
        Recorder.clear()
        recordedSteps = emptyList()
        OverlayBus.exitRecording()
        recordingActive = false
    }

    fun learnRecording(nameHint: String) {
        val rec = Recorder.snapshot()
        if (rec.isEmpty) return
        if (settings.apiKey.isBlank()) {
            toast = context.getString(R.string.recording_need_api_key)
            return
        }
        learning = true
        scope.launch {
            val llm = LlmClient(
                LlmConfig(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.modelName,
                    useFreeModel = settings.useFreeModel,
                    thinking = settings.thinking,
                )
            )
            val macro = runCatching { MacroLearner(llm).learn(rec, nameHint) }.getOrNull()
            if (macro != null) {
                MacroStore.save(context, macro)
                macros = loadMacroSummaries(context)
                Recorder.clear()
                recordedSteps = emptyList()
                toast = context.getString(R.string.recording_toast_learned, macro.title)
                if (settings.saveLogs) AppLog.i("学会技能：${macro.title}（${macro.steps.size} 步）", "操作记录")
            } else {
                toast = context.getString(R.string.recording_toast_learn_failed, "模型没返回可用的结果")
            }
            learning = false
        }
    }

    fun addSchedule(task: String, hour: Int, minute: Int, daily: Boolean, onVirtualDisplay: Boolean = false) {
        if (task.isBlank()) return
        val s = Schedule(
            id = ScheduleStore.newId(),
            task = task,
            hour = hour,
            minute = minute,
            repeatDaily = daily,
            useVirtualDisplay = onVirtualDisplay,
        )
        schedules = ScheduleStore.upsert(context, s)
        Scheduler.schedule(context, s)
        if (!Scheduler.canScheduleExact(context)) {
            toast = context.getString(R.string.schedule_toast_no_exact)
        } else {
            toast = context.getString(R.string.schedule_toast_added, s.timeLabel())
        }
    }

    fun toggleSchedule(schedule: Schedule, enabled: Boolean) {
        val next = schedule.copy(enabled = enabled)
        schedules = ScheduleStore.upsert(context, next)
        if (enabled) Scheduler.schedule(context, next) else Scheduler.cancel(context, next.id)
    }

    fun deleteSchedule(id: String) {
        Scheduler.cancel(context, id)
        ScheduleReceiver.cancelNotification(context, id)
        schedules = ScheduleStore.delete(context, id)
        toast = context.getString(R.string.schedule_toast_deleted)
    }

    fun deleteMacro(id: String) {
        MacroStore.delete(context, id)
        macros = loadMacroSummaries(context)
        toast = context.getString(R.string.recording_toast_deleted)
    }

    val hostActivity = LocalContext.current as? ComponentActivity
    var resumeTick by remember { mutableIntStateOf(0) }
    AutoReturnOnForegroundEffect()

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
        notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        refreshStats()
        if (conversation.size > 0 &&
            settings.contextPolicy == ContextPolicy.H24 &&
            conversation.isIdleBeyond(settings.contextPolicy.idleMinutes)
        ) {
            clearContextWithMarker(context.getString(R.string.context_cleared_on_open))
        }
    }

    suspend fun runTask(task: String, carried: CarriedContext = CarriedContext()) {
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
                    extra = listOf(
                        "API Key ：${settings.maskedApiKey}（打码）",
                        "上下文  ：${settings.contextPolicy.label}",
                        "最大步数：${if (settings.maxSteps <= 0) "不限" else settings.maxSteps.toString()}",
                        "开启记忆：${settings.memoryEnabled}",
                        "保存截图：${settings.saveScreenshots}",
                        "自动截图：每 ${AutoCapture.INTERVAL_MS / 1000} 秒（不隐藏 UI）",
                        "记忆规模：${MemoryStore.stats(context).let { "${it.first} 条 / ${it.second} 字符" }}",
                        "技能    ：${MacroStore.loadAll(context).size} 个录制技能",
                    ),
                ),
            )
        } else null

        logger?.line("任务：$task", tag = "任务")
        logger?.line(
            if (settings.maxSteps <= 0) "最大步数：不限" else "最大步数：${settings.maxSteps}",
            tag = "任务",
        )
        addLog(LogKind.ACTION, task, "指令")

        if (!settings.useFreeModel && settings.apiKey.isBlank()) {
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
                useFreeModel = settings.useFreeModel,
                thinking = settings.thinking,
            )
        )
        if (carried.fingerprints.isNotEmpty()) llm.seedFingerprints(carried.fingerprints)
        if (carried.history.isNotEmpty()) {
            AppLog.i("接着上一段上下文：复原 ${carried.history.size} 条模型历史", "上下文")
        }

        val agent = Agent(
            controller = controller,
            llm = llm,
            settings = settings,
            maxSteps = settings.maxSteps,
            selfPackage = context.packageName,
            memorySnapshot = carried.memorySnapshot.takeIf { it.isNotBlank() },
            seedHistory = carried.history,
            skills = SkillRegistry(
                ctx = SkillContext(context.applicationContext, controller),
                extra = MacroStore.loadAll(context),
            ),
            skillCatalogSnapshot = carried.skillCatalog.ifBlank { currentCatalog },
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
                        text, label,
                    )
                }
                override fun onProgress(step: Int, maxSteps: Int) {
                    progress = "AI 正在执行 · " + stepsLabel(step, maxSteps)
                }
                override fun onFinished(success: Boolean, message: String) {
                    conversation.add(Turn(if (success) Turn.Role.AI else Turn.Role.ACTION, message))
                    progress = ""
                    memorizeAfterTask(
                        scope = scope,
                        context = context.applicationContext,
                        settings = settings,
                        conversation = conversation,
                        sinceTurn = taskTurnStart,
                        llm = llm,
                        onDone = { outcome ->
                            if (outcome is MemoryWriter.Outcome.Written) {
                                toast = "已新增记忆：${outcome.title}"
                            }
                            refreshStats()
                        },
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

        val finalHistory = try {
            agent.run(task)
        } finally {
            logger?.close()
        }
        modelHistory = finalHistory
        modelFingerprints = llm.fingerprintChain()
    }

    fun submit() {
        val task = input.trim()
        if (task.isBlank() || isRunning) return
        if (deletingLogs) {
            toast = context.getString(R.string.log_delete_in_progress)
            return
        }
        if (!runOnVirtualDisplay && !AutoService.isConnected) {
            addLog(LogKind.ERROR, "还没开启无障碍服务。到「权限」页里开一下再发任务。", "无障碍未开启")
            toast = context.getString(R.string.a11y_needed_toast)
            return
        }

        input = ""
        stopRequested = false
        OverlayBus.clearStop()
        OverlayBus.clearAutoReturnSuppression()

        if (RecordingShortcut.matches(task)) {
            screen = Screen.RECORDING
            toast = context.getString(R.string.recording_shortcut_opened)
            return
        }

        val policy = settings.contextPolicy
        val expired = when (policy) {
            ContextPolicy.RESET_EACH_TIME -> conversation.size > 0
            ContextPolicy.H24 -> conversation.isIdleBeyond(24 * 60)
            ContextPolicy.UNLIMITED -> false
        }
        if (expired) {
            clearContextWithMarker(
                context.getString(
                    if (policy == ContextPolicy.RESET_EACH_TIME) R.string.context_cleared_policy
                    else R.string.context_cleared_idle
                )
            )
        }

        if (contextModelName != settings.modelName) {
            if (conversation.size > 0) {
                clearContextWithMarker("模型换成了 ${settings.modelName}，上下文已重开")
            } else {
                contextModelName = settings.modelName
            }
        }

        val freshContext = conversation.size == 0
        val memory = if (freshContext) MemoryStore.readForPrompt(context) else contextMemory
        if (freshContext) contextMemory = memory

        val carried = if (freshContext) {
            modelHistory = emptyList()
            modelFingerprints = emptyList()
            CarriedContext(memorySnapshot = memory, skillCatalog = contextSkillCatalog)
        } else {
            CarriedContext(
                history = modelHistory,
                fingerprints = modelFingerprints,
                memorySnapshot = memory,
                skillCatalog = contextSkillCatalog,
            )
        }

        taskTurnStart = conversation.size
        conversation.add(Turn(Turn.Role.USER, task))

        scope.launch {
            isRunning = true
            OverlayBus.taskRunning = true

            if (Settings.canDrawOverlays(context)) {
                OverlayService.start(context)
            } else {
                addLog(LogKind.ERROR, "没有悬浮窗权限，这次看不到进度面板和急停按钮。到「权限」页里开一下。", "悬浮窗不可用")
            }

            var vdCreated = false
            fun bringAppToFront() {
                val intent = android.content.Intent(context, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                context.startActivity(intent)
            }

            if (runOnVirtualDisplay) {
                addLog(LogKind.SYSTEM, "正在创建副屏 ...", "副屏")
                val st = runCatching { withContext(Dispatchers.IO) { VirtualDisplayManager.create(context) } }
                    .getOrElse { VirtualDisplayManager.State(message = "创建失败：${it.message}") }
                val id = st.displayId
                if (id == null) {
                    addLog(LogKind.ERROR, "副屏没能创建：${st.message}", "副屏")
                    isRunning = false
                    OverlayBus.taskRunning = false
                    progress = ""
                    bringAppToFront()
                    return@launch
                }
                vdCreated = true
                val displayManager = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                    as android.hardware.display.DisplayManager
                val mainDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val realSize = mainDisplay.mode.physicalWidth to mainDisplay.mode.physicalHeight
                controller.enterVirtualDisplay(displayId = id, size = realSize)
                addLog(LogKind.SYSTEM, "副屏已就绪（id=$id），AI 将在这块屏上操作", "副屏")
                runCatching { context.startActivity(MirrorActivity.intent(context, id)) }
            }

            val capJob = if (settings.saveLogs) {
                launch {
                    if (settings.saveLogs) AppLog.i("自动截图已开启：每 ${AutoCapture.INTERVAL_MS / 1000} 秒一张（不隐藏任何 UI）", "截图")
                    while (isActive) {
                        runCatching {
                            controller.captureFrame()?.let { bytes ->
                                AutoCapture.save(context, bytes)?.let {
                                    AppLog.i("自动截图：${it.name}", "截图")
                                }
                            }
                        }
                        delay(AutoCapture.INTERVAL_MS)
                    }
                }
            } else null

            try {
                runTask(task, carried)
            } catch (t: Throwable) {
                addLog(LogKind.ERROR, "执行出错：${t.message}", "错误")
                if (settings.saveLogs) AppLog.e("执行出错：$t", "任务")
            } finally {
                capJob?.cancel()
                if (vdCreated) {
                    controller.exitVirtualDisplay()
                    runCatching { withContext(Dispatchers.IO) { VirtualDisplayManager.remove(context) } }
                }
                runOnVirtualDisplay = false
                isRunning = false
                OverlayBus.taskRunning = false
                progress = ""
                bringAppToFront()
                OverlayService.stop(context)
                refreshStats()
            }
        }
    }

    fun clearContext() {
        clearContextWithMarker(context.getString(R.string.context_cleared_manual))
        toast = context.getString(R.string.settings_clear_context_done)
    }

    fun lookAtVirtualDisplay() {
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { VirtualDisplayManager.refresh(context).displayId }.getOrNull()
            }
            if (id == null) {
                toast = context.getString(R.string.vd_mirror_none)
                return@launch
            }
            runCatching { context.startActivity(MirrorActivity.intent(context, id)) }
                .onFailure { toast = context.getString(R.string.vd_mirror_failed, it.message ?: "") }
        }
    }

    fun exportLogs() {
        if (exporting) return
        if (deletingLogs) {
            toast = context.getString(R.string.log_delete_in_progress)
            return
        }
        exporting = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    LogExporter.exportEverything(context, buildDeviceSummary(context, settings))
                }
                r.onSuccess { f ->
                    val why = LogExporter.share(context, f, "纸盒日志")
                    if (why != null) toast = context.getString(R.string.settings_export_share_failed, why)
                }.onFailure { t ->
                    toast = context.getString(R.string.settings_export_failed, t.message ?: t.javaClass.simpleName)
                }
            } finally {
                exporting = false
            }
        }
    }

    fun deleteLogs() {
        if (deletingLogs) { toast = context.getString(R.string.log_delete_in_progress); return }
        if (isRunning || exporting) { toast = context.getString(R.string.log_delete_busy); return }
        deletingLogs = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { LogExporter.deleteAllLogs(context) }
                clearContextWithMarker(context.getString(R.string.log_delete_context_marker))
                toast = context.getString(R.string.log_delete_done)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                toast = context.getString(R.string.log_delete_failed, t.message ?: t.javaClass.simpleName)
            } finally {
                deletingLogs = false
            }
        }
    }

    LaunchedEffect(pendingTask) {
        val task = pendingTask ?: return@LaunchedEffect
        if (task.isBlank()) return@LaunchedEffect
        addLog(LogKind.ACTION, task, "定时任务")
        input = task
        runOnVirtualDisplay = pendingUseVirtualDisplay
        submit()
        onPendingTaskHandled()
    }

    BackHandler(enabled = screen != Screen.CONTROL) {
        screen = Screen.CONTROL
        toast = null
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
                memoryStats = memoryStats,
                autoCapStats = autoCapStats,
                appVersion = appVersion,
                progress = progress,
                toast = toast,
                recordingActive = recordingActive,
                recordedSteps = recordedSteps,
                macros = macros,
                learning = learning,
                schedules = schedules,
                exactAlarmGranted = exactAlarmGranted,
            ),
            onSettingsClick = { screen = Screen.SETTINGS },
            onPermissionsClick = { screen = Screen.PERMISSIONS },
            onAboutClick = { screen = Screen.ABOUT },
            onInputChange = { input = it },
            onSubmit = { submit() },
            onStop = {
                stopRequested = true
                addLog(LogKind.ERROR, "已请求停止，等当前这一步走完", "停止")
            },
            onControlPhone = { screen = Screen.CONTROL },
            onRecording = { screen = Screen.RECORDING },
            onSchedules = { screen = Screen.SCHEDULES },
            onNewConversation = { clearContext() },
        )

        Screen.RECORDING -> RecordingScreen(
            state = MainUiState(
                toast = toast,
                recordingActive = recordingActive,
                recordedSteps = recordedSteps,
                macros = macros,
                learning = learning,
            ),
            onBack = { screen = Screen.CONTROL },
            onStart = { startRecording() },
            onStop = { stopRecording() },
            onDiscard = { discardRecording() },
            onLearn = { nameHint -> learnRecording(nameHint) },
            onDeleteMacro = { id -> deleteMacro(id) },
        )

        Screen.SCHEDULES -> ScheduleScreen(
            state = MainUiState(
                toast = toast,
                schedules = schedules,
                exactAlarmGranted = exactAlarmGranted,
            ),
            onBack = { screen = Screen.CONTROL },
            onAdd = { task, h, m, daily, vd -> addSchedule(task, h, m, daily, vd) },
            onToggle = { s, on -> toggleSchedule(s, on) },
            onDelete = { id -> deleteSchedule(id) },
        )

        Screen.PERMISSIONS -> PermissionsScreen(
            state = MainUiState(
                settings = settings,
                authorized = authorized,
                overlayGranted = overlayGranted,
                notifGranted = notifGranted,
                shizukuState = shizukuState,
                exactAlarmGranted = exactAlarmGranted,
                toast = toast,
            ),
            onBack = { screen = Screen.CONTROL; toast = null },
            onSettingsChange = { next -> settings = next; store.save(next) },
            onGotoAccessibility = onOpenAccessibilitySettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
            onRequestNotification = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onRequestExactAlarm = onRequestExactAlarm,
            onShizukuClick = {
                when (ShizukuBridge.state(context)) {
                    ShizukuBridge.State.NO_PERMISSION -> ShizukuBridge.requestPermission()
                    ShizukuBridge.State.NOT_INSTALLED -> toast = "请先安装 Shizuku 应用"
                    ShizukuBridge.State.NOT_RUNNING -> toast = "请先打开 Shizuku、启动服务"
                    ShizukuBridge.State.READY ->
                        if (isRunning) {
                            OverlayBus.requestMoveToVirtualDisplay()
                            toast = "已请求切到副屏"
                        } else {
                            toast = "启动任务后回桌面即自动切副屏"
                        }
                }
            },
        )

        Screen.SETTINGS -> SettingsScreen(
            state = MainUiState(
                settings = settings,
                authorized = authorized,
                overlayGranted = overlayGranted,
                logStats = logStats,
                memoryStats = memoryStats,
                autoCapStats = autoCapStats,
                appVersion = appVersion,
                shizukuState = shizukuState,
                exporting = exporting,
                toast = toast,
            ),
            onSettingsChange = { next ->
                settings = next
                store.save(next)
                if (next.saveLogs) {
                    AppLog.i(
                        "设置变更：通道=${next.mode.label} 模型=${next.modelName} 最大步数=${next.maxSteps} 记忆=${next.memoryEnabled} 上下文=${next.contextPolicy.label}",
                        "设置",
                    )
                }
            },
            onBack = { screen = Screen.CONTROL; toast = null },
            onProbeVirtualDisplay = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        AutoService.get()?.probeWindowsOnAllDisplays() ?: "无障碍服务未连接"
                    }
                    toast = result.take(80) + if (result.length > 80) "…" else ""
                }
            },
            onClearContext = { clearContext() },
            onLookAtVd = { lookAtVirtualDisplay() },
            onExportLogs = { exportLogs() },
            onDeleteLogs = { deleteLogs() },
        )

        Screen.ABOUT -> AboutScreen(
            appVersion = appVersion,
            onBack = { screen = Screen.CONTROL },
        )
    }
}

private fun memorizeAfterTask(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    settings: AppSettings,
    conversation: Conversation,
    sinceTurn: Int,
    llm: LlmClient,
    onDone: (MemoryWriter.Outcome) -> Unit,
) {
    if (!settings.memoryEnabled) {
        onDone(MemoryWriter.Outcome.NotWritten("记忆开关是关的"))
        return
    }
    val turns = conversation.snapshot().drop(sinceTurn.coerceAtLeast(0))
    if (turns.isEmpty()) {
        onDone(MemoryWriter.Outcome.NotWritten("这段任务没有可归纳的轮次"))
        return
    }
    scope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching { MemoryWriter.write(context, llm, turns) }
                .getOrElse { e ->
                    MemoryWriter.Outcome.NotWritten(
                        "归纳调用抛异常：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
        }
        val line = when (outcome) {
            is MemoryWriter.Outcome.Written -> "记忆已更新：${outcome.title}"
            is MemoryWriter.Outcome.NotWritten -> "记忆未写入：${outcome.reason}"
        }
        AppLog.afterTask(line, "记忆", if (outcome is MemoryWriter.Outcome.NotWritten) "W" else "I")
        onDone(outcome)
    }
}

private fun buildDeviceSummary(context: android.content.Context, settings: AppSettings): String {
    val macros = MacroStore.loadAll(context)
    val (memCount, memChars) = MemoryStore.stats(context)
    val capCount = AutoCapture.count(context)
    val runs = AppLog.listRuns(context)
    val exact = Scheduler.canScheduleExact(context)

    return buildString {
        appendLine("== 设备 ==")
        appendLine("机型    ：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("系统    ：Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
        appendLine()
        appendLine("== 应用 ==")
        appendLine("版本    ：${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"}")
        appendLine("日志次数：${runs.size}")
        appendLine("自动截图：$capCount 张")
        appendLine()
        appendLine("== 模型 ==")
        appendLine("接口地址：${settings.baseUrl}")
        appendLine("模型    ：${settings.modelName}")
        appendLine("思考模式：${settings.thinking.label}")
        appendLine("API Key ：${settings.maskedApiKey}（打码，完整值不会出现在导出包里）")
        appendLine()
        appendLine("== 运行 ==")
        appendLine("操作通道：${settings.mode.label}")
        appendLine("上下文  ：${settings.contextPolicy.label}")
        appendLine("最大步数：${if (settings.maxSteps <= 0) "不限" else settings.maxSteps.toString()}")
        appendLine("开启记忆：${settings.memoryEnabled}")
        appendLine("保存日志：${settings.saveLogs}")
        appendLine("保存截图：${settings.saveScreenshots}")
        appendLine()
        appendLine("== 记忆 ==")
        appendLine("条数    ：$memCount（$memChars 字符）")
        appendLine()
        appendLine("== 技能 ==")
        appendLine("内置    ：list_apps、recall_memory、list_skills")
        if (macros.isEmpty()) {
            appendLine("录制    ：无")
        } else {
            macros.forEach { appendLine("录制    ：${it.title}（${it.steps.size} 步，id=${it.id}）") }
        }
        appendLine()
        appendLine("== 定时任务 ==")
        appendLine("精确闹钟：${if (exact) "已允许" else "未允许（触发可能晚几分钟）"}")
        val schedules = ScheduleStore.loadAll(context)
        if (schedules.isEmpty()) {
            appendLine("任务    ：无")
        } else {
            schedules.forEach { appendLine("任务    ：${it.timeLabel()} · ${it.task}（启用=${it.enabled}）") }
        }
    }
}

private fun loadMacroSummaries(context: android.content.Context): List<MacroSummary> =
    MacroStore.loadAll(context).map { MacroSummary(it.id, it.title, it.steps.size) }

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
