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
import com.aiphone.assistant.data.ContextPolicy
import com.aiphone.assistant.data.SettingsStore
import com.aiphone.assistant.data.stepsLabel
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmConfig
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.log.LogExporter
import com.aiphone.assistant.memory.Conversation
import com.aiphone.assistant.memory.MemoryStore
import com.aiphone.assistant.memory.MemoryWriter
import com.aiphone.assistant.memory.Turn
import com.aiphone.assistant.record.MacroLearner
import com.aiphone.assistant.record.MacroStore
import com.aiphone.assistant.record.Recorder
import com.aiphone.assistant.schedule.ScheduleReceiver
import com.aiphone.assistant.schedule.Schedule
import com.aiphone.assistant.schedule.ScheduleStore
import com.aiphone.assistant.schedule.Scheduler
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.overlay.OverlayService
import com.aiphone.assistant.skill.SkillContext
import com.aiphone.assistant.skill.SkillRegistry
import com.aiphone.assistant.ui.LogEntry
import com.aiphone.assistant.ui.LogKind
import com.aiphone.assistant.ui.MainScreen
import com.aiphone.assistant.ui.MacroSummary
import com.aiphone.assistant.ui.MainUiState
import com.aiphone.assistant.ui.RecordingScreen
import com.aiphone.assistant.ui.ScheduleScreen
import com.aiphone.assistant.ui.Screen
import com.aiphone.assistant.ui.SettingsScreen
import com.aiphone.assistant.ui.theme.AiPhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var controller: ChannelController
    private lateinit var store: SettingsStore

    /**
     * 定时任务到点时带进来的任务。
     *
     * 用 Compose 的 state 而不是普通字段：闹钟可能在应用已经开着的时候响，
     * 那时走的是 onNewIntent，界面需要跟着重新触发一次。
     */
    private val pendingTask = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边显示：内容铺到状态栏和导航栏下面，
        // 再靠 WindowInsets 给内容留出安全区。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        controller = ChannelController(applicationContext)
        store = SettingsStore(this)

        // 冷启动时把定时任务取出来
        readScheduledIntent(intent)

        // 顺手重排一次定时任务。闹钟除了重启会丢，应用被"强行停止"过
        // 也会被系统清掉（而强行停止后开机广播也不会再发给本应用）——
        // 用户重新打开一次应用就自动恢复，比让他去设置里找一遍强
        runCatching { Scheduler.rescheduleAll(applicationContext) }


        setContent {
            AiPhoneTheme {
                AppRoot(
                    controller = controller,
                    store = store,
                    appVersion = appVersion(),
                    pendingTask = pendingTask.value,
                    onPendingTaskHandled = { pendingTask.value = null },
                    onRequestExactAlarm = { requestExactAlarm() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 应用已经开着的时候闹钟响了走这里
        setIntent(intent)
        readScheduledIntent(intent)
    }

    private fun readScheduledIntent(intent: Intent?) {
        val task = intent?.getStringExtra(EXTRA_RUN_TASK)?.trim().orEmpty()
        if (task.isBlank()) return
        val id = intent?.getStringExtra(EXTRA_SCHEDULE_ID)
        pendingTask.value = task
        // 界面已经在用户眼前了，那条提醒就多余了。放在这一层是因为
        // 只有 Activity 拿得到 scheduleId（Compose 那一层只拿到任务文本）
        id?.let { ScheduleReceiver.cancelNotification(this, it) }
    }

    /**
     * 跳到系统的「闹钟与提醒」授权页。
     *
     * Android 14 起精确闹钟默认拒绝，必须用户手动允许；不给这个入口，
     * 用户只会看到"设了 9 点却 9 点 07 才动"，而且查不出原因。
     */
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
        /** 定时任务到点时带进来的任务内容 */
        const val EXTRA_RUN_TASK = "run_task"

        /** 哪条定时任务触发的（用来撤掉它的通知） */
        const val EXTRA_SCHEDULE_ID = "schedule_id"
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
    pendingTask: String?,
    onPendingTaskHandled: () -> Unit,
    onRequestExactAlarm: () -> Unit,
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
    var memoryStats by remember { mutableStateOf("") }

    /**
     * 本次任务是从上下文里的第几轮开始的。
     *
     * 写记忆时只归纳**这一段任务自己产生**的轮次。不记这个的话，
     * "不限"档下一条长对话会被反复整体归纳 —— 又贵，又会把前面任务的
     * 内容重新总结一遍塞进记忆里。
     */
    var taskTurnStart by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    // ---- 操作记录 ----
    var recordingActive by remember { mutableStateOf(Recorder.isRecording) }
    var recordedSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    var macros by remember { mutableStateOf(loadMacroSummaries(context)) }
    var learning by remember { mutableStateOf(false) }

    // ---- 定时任务 ----
    var schedules by remember { mutableStateOf(ScheduleStore.loadAll(context)) }
    var exactAlarmGranted by remember { mutableStateOf(Scheduler.canScheduleExact(context)) }

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
        val (memCount, memChars) = MemoryStore.stats(context)
        memoryStats = if (memCount > 0) {
            context.getString(R.string.settings_memory_stats, memCount, formatBytes(memChars.toLong()))
        } else {
            ""
        }
        macros = loadMacroSummaries(context)
        schedules = ScheduleStore.loadAll(context)
        exactAlarmGranted = Scheduler.canScheduleExact(context)
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

    /**
     * 开始录制。
     *
     * 录制期间用户会切到别的应用，这个 Activity 会进入 stopped 状态 ——
     * 所以进度**不能靠界面回调推**，而是起一个协程轮询 Recorder。
     * 它同时也负责读悬浮窗上的"停止"按钮（录制时没有 Agent 在跑，
     * 那个按钮的请求没人处理）。
     *
     * 注意 [stopRecording] 必须声明在上面：Kotlin 的局部函数不能先用后定义。
     */
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

    /** 交给 AI 学成技能 */
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

    fun addSchedule(task: String, hour: Int, minute: Int, daily: Boolean) {
        if (task.isBlank()) return
        val s = Schedule(
            id = ScheduleStore.newId(),
            task = task,
            hour = hour,
            minute = minute,
            repeatDaily = daily,
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
    suspend fun runTask(task: String, memorySnapshot: String? = null) {
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
            memorySnapshot = memorySnapshot,
            // 技能注册表：模型用 use_skill 主动要"屏幕上没有的信息"。
            // 传 applicationContext —— 它会活到任务结束，不能攥着 Activity
            skills = SkillRegistry(
                ctx = SkillContext(context.applicationContext, controller),
                // 每次任务重新从文件加载：用户可能刚在「操作记录」里学会一个
                extra = MacroStore.loadAll(context),
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
                        sinceTurn = taskTurnStart,
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

        // ---- 上下文策略 ----
        // 判断"这一段上下文还算不算数"：按策略决定是接着用还是重开。
        // 重开的时机很关键 —— 记忆**只在这个时机**注入提示词，
        // 因为往正在进行的一段对话中间插东西会把缓存前缀打断。
        val policy = settings.contextPolicy
        val expired = when (policy) {
            ContextPolicy.RESET_EACH_TIME -> conversation.size > 0
            ContextPolicy.H24 -> conversation.isIdleBeyond(24 * 60)
            ContextPolicy.UNLIMITED -> false
        }
        if (expired) {
            if (settings.saveLogs) {
                AppLog.i("按上下文策略重开（原有 ${conversation.size} 轮，策略=${policy.label}）", "上下文")
            }
            conversation.clear()
        }

        // 这一段是不是"新开的"：刚清过、或者本来就是空的
        val freshContext = conversation.size == 0
        val memory = if (freshContext) MemoryStore.readForPrompt(context) else null

        // 记住起点，任务结束时只归纳从这里往后的轮次
        taskTurnStart = conversation.size
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
                runTask(task, memory)
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

    /**
     * 手动清空上下文。
     *
     * 这里**不需要先沉淀** —— 记忆在每次任务结束时就写过了，
     * 上下文里剩下的只是这一次的界面往返记录，丢了不可惜。
     */
    fun clearContext() {
        val had = conversation.size
        conversation.clear()
        if (settings.saveLogs) AppLog.i("手动清空上下文（原有 $had 轮）", "记忆")
        toast = context.getString(R.string.settings_clear_context_done)
        refreshStats()
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

    /**
     * 定时任务到点自动开跑。
     *
     * 必须放在 submit() 后面 —— Kotlin 的局部函数不能先用后定义，
     * 放前面是编译错误（这一步实际踩到了）。
     */
    LaunchedEffect(pendingTask) {
        val task = pendingTask ?: return@LaunchedEffect
        if (task.isBlank()) return@LaunchedEffect
        addLog(LogKind.ACTION, task, "定时任务")
        input = task
        submit()
        onPendingTaskHandled()
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
            onInputChange = { input = it },
            onSubmit = { submit() },
            onStop = {
                // 只是"请求停止"。Agent 会在每一步开始前和执行动作前检查，
                // 不会把正在发出的那一次注入砍断。
                stopRequested = true
                addLog(LogKind.ERROR, "已请求停止，等当前这一步走完", "停止")
            },
            onControlPhone = { screen = Screen.CONTROL },
            onRecording = { screen = Screen.RECORDING },
            onSchedules = { screen = Screen.SCHEDULES },
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
            onAdd = { task, h, m, daily -> addSchedule(task, h, m, daily) },
            onToggle = { s, on -> toggleSchedule(s, on) },
            onDelete = { id -> deleteSchedule(id) },
            onRequestExactAlarm = onRequestExactAlarm,
        )

        Screen.SETTINGS -> SettingsScreen(
            state = MainUiState(
                settings = settings,
                authorized = authorized,
                overlayGranted = overlayGranted,
                logStats = logStats,
                memoryStats = memoryStats,
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
                            "记忆=${next.memoryEnabled} 上下文=${next.contextPolicy.label}",
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
 * 任务结束时写一条记忆。
 *
 * ## 为什么放在任务结束，而不是等上下文被清空
 *
 * 这时候记录最新鲜（界面细节、用户的原话都还在）。而且上下文什么时候
 * 重开由策略决定 —— "不限"那一档可能几天都不清一次，等它就永远学不到东西。
 *
 * ## 为什么要排队
 *
 * 写记忆要调一次模型，几秒钟。这期间用户完全可能又发了一条任务，
 * 那条结束时也会写 —— 两个写入同时进行会让**两条记录交错插进同一个
 * 文件**，markdown 结构就乱了。所以交给 [MemoryWriter] 排队。
 *
 * 成本是一次纯文字调用（不带图）。只在「开启记忆」打开时才做。
 */
private fun memorizeAfterTask(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    settings: AppSettings,
    conversation: Conversation,
    sinceTurn: Int,
    llm: LlmClient,
    onDone: () -> Unit,
) {
    if (!settings.memoryEnabled) return
    // 只归纳这一段任务自己产生的轮次。把整段上下文都喂进去的话，
    // 之前任务的内容会被反复重新归纳 —— 既贵，又会让同一条认知
    // 在记忆文件里越滚越多份
    val turns = conversation.snapshot().drop(sinceTurn.coerceAtLeast(0))
    if (turns.isEmpty()) return
    scope.launch {
        runCatching {
            val title = MemoryWriter.write(context, llm, turns)
            if (title != null && settings.saveLogs) AppLog.i("记忆已更新：$title", "记忆")
        }
        onDone()
    }
}

/** 已学会技能的摘要，给界面用（界面不需要知道步骤细节） */
private fun loadMacroSummaries(context: android.content.Context): List<MacroSummary> =
    MacroStore.loadAll(context).map { MacroSummary(it.id, it.title, it.steps.size) }

/** 字节数转可读文本 */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
