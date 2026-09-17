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
import com.aiphone.assistant.data.CarriedContext
import com.aiphone.assistant.data.ContextPolicy
import com.aiphone.assistant.data.ContextStore
import com.aiphone.assistant.display.MirrorActivity
import com.aiphone.assistant.display.VirtualDisplayManager
import com.aiphone.assistant.data.SettingsStore
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
import com.aiphone.assistant.ui.VirtualDisplayScreen
import com.aiphone.assistant.ui.Screen
import com.aiphone.assistant.ui.SettingsScreen
import com.aiphone.assistant.ui.theme.AiPhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    /**
     * 这条定时任务要不要走副屏。和 [pendingTask] 同生共死：
     * 都由同一个 intent 带进来、由同一个 LaunchedEffect 消费。
     */
    private val pendingUseVirtualDisplay = mutableStateOf(false)

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
        // 应用已经开着的时候闹钟响了走这里
        setIntent(intent)
        readScheduledIntent(intent)
    }

    private fun readScheduledIntent(intent: Intent?) {
        val task = intent?.getStringExtra(EXTRA_RUN_TASK)?.trim().orEmpty()
        if (task.isBlank()) return
        val id = intent?.getStringExtra(EXTRA_SCHEDULE_ID)
        pendingTask.value = task
        // 副屏是逐条定时任务的选项；手动输入的任务不会走这里
        pendingUseVirtualDisplay.value =
            intent?.getBooleanExtra(EXTRA_SCHEDULE_VIRTUAL_DISPLAY, false) == true
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

        /** 这条定时任务要不要在副屏上跑（见 Schedule.useVirtualDisplay） */
        const val EXTRA_SCHEDULE_VIRTUAL_DISPLAY = "schedule_virtual_display"
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
    pendingUseVirtualDisplay: Boolean,
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
    var autoCapStats by remember { mutableStateOf("") }

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

    // 对话从磁盘恢复：应用被系统回收、或者用户划掉重开之后，
    // 只要上下文没被清掉，消息就应该还在 —— 否则用户看到的
    // 和设置里选的对不上（选了"不限"却一开就空）
    val savedContext = remember { ContextStore.load(context) }
    val logs = remember {
        mutableStateListOf<LogEntry>().apply {
            addAll(savedContext?.entries ?: emptyList())
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

    /**
     * 定时任务放在哪块屏上跑。
     *
     * 由定时任务自己带进来（见 `Schedule.useVirtualDisplay`），
     * 手动输入的任务永远是 false。所以它不能放进 AppSettings ——
     * 那是个全局开关，没法逐条任务区分。
     */
    var runOnVirtualDisplay by remember { mutableStateOf(false) }

    /**
     * **模型那一侧的历史**，和 [logs] 的作用域一样长。
     *
     * 它只在两处变：任务结束（接住 Agent 攒下的）、清空上下文（归零）。
     * 落盘和 [logs] 走同一个 `ContextStore.save` —— 分成两次写的话，
     * 那个防抖保存会用旧值把新历史盖掉。
     */
    var modelHistory by remember { mutableStateOf(savedContext?.history ?: emptyList()) }

    /**
     * 上一个请求的指纹链，跨任务维持"前缀复用"自查。
     *
     * 和 [modelHistory] 同生共死：少了它，这次任务的第一次请求就没有
     * "上一次"可比，日志里永远是 0/0，前缀有没有被打断看不出来。
     */
    var modelFingerprints by remember { mutableStateOf(savedContext?.fingerprints ?: emptyList()) }

    /**
     * 这段上下文**开头**注入进系统提示词的那份记忆。
     *
     * 系统提示词是前缀的第 0 个 token，它一旦变化，整段上下文的缓存全废。
     * 所以记忆文件即使后来变长了，这段上下文里也只能继续用当初那一份 ——
     * 想用新的，就清空上下文重开一段。
     */
    var contextMemory by remember { mutableStateOf(savedContext?.memorySnapshot ?: "") }

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

    // 对话变化之后落盘。600ms 防抖：一次任务会连续加十几条消息，
    // 每条都写一次文件没必要（而且这文件在"不限"档下会越来越大）
    //
    // 模型历史一起存：它和界面消息必须是**同一次**写入，否则两边会不一致
    // （见 modelHistory 的说明）
    LaunchedEffect(logs.size, conversation.size, modelHistory, modelFingerprints, contextMemory) {
        if (logs.isEmpty() && conversation.size == 0 && modelHistory.isEmpty()) return@LaunchedEffect
        delay(600)
        ContextStore.save(
            context = context,
            entries = logs.toList(),
            turns = conversation.snapshot(),
            lastActivityAt = conversation.lastActivityAt,
            history = modelHistory,
            fingerprints = modelFingerprints,
            memorySnapshot = contextMemory,
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
        val capCount = AutoCapture.count(context)
        autoCapStats = if (capCount > 0) {
            context.getString(
                R.string.log_autocap_stats,
                capCount,
                formatBytes(AutoCapture.totalBytes(context)),
            )
        } else {
            ""
        }
        macros = loadMacroSummaries(context)
        schedules = ScheduleStore.loadAll(context)
        exactAlarmGranted = Scheduler.canScheduleExact(context)
    }

    /**
     * 清空上下文，并**在对话里留一条说明**。
     *
     * 不留说明的话，用户只会看到消息凭空消失 —— 那和"界面出 bug 了"
     * 没法区分。这条系统消息就是告诉他"是我清的，因为什么"。
     */
    fun clearContextWithMarker(reason: String) {
        val had = conversation.size
        conversation.clear()
        logs.clear()
        // 模型那一侧也要归零，否则"新开一段上下文"只是界面上新开了
        modelHistory = emptyList()
        modelFingerprints = emptyList()
        contextMemory = ""
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

    fun addSchedule(
        task: String,
        hour: Int,
        minute: Int,
        daily: Boolean,
        onVirtualDisplay: Boolean = false,
    ) {
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

        // 打开应用 / 回到前台时先按策略判断这段上下文还算不算数。
        // 只在**确实过期**时清，而且会在对话里留一条说明
        if (conversation.size > 0 &&
            settings.contextPolicy == ContextPolicy.H24 &&
            conversation.isIdleBeyond(settings.contextPolicy.idleMinutes)
        ) {
            clearContextWithMarker(context.getString(R.string.context_cleared_on_open))
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
        // 接着上一段上下文时，把上一个任务的指纹链也接上 ——
        // 否则这次任务的第一次请求没有可比的"上一次"，前缀复用率永远是 0/0
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
                    // 记忆总结也当成对话的一部分显示出来 ——
                    // 它确实是"助手在做的一件事"，藏起来用户只会觉得
                    // 软件莫名其妙多花了钱
                    if (settings.memoryEnabled) {
                        addLog(LogKind.THOUGHT, "正在整理这次任务的记忆 ...", "助手")
                    }
                    memorizeAfterTask(
                        scope = scope,
                        context = context.applicationContext,
                        settings = settings,
                        conversation = conversation,
                        sinceTurn = taskTurnStart,
                        llm = llm,
                        onDone = { title ->
                            addLog(
                                if (title != null) LogKind.RESULT else LogKind.SYSTEM,
                                if (title != null) {
                                    "已记入记忆：$title"
                                } else {
                                    // 走到这里只有两种可能：记忆开关刚被关掉，
                                    // 或者归纳那次模型调用失败（失败会写进日志）。
                                    // 正常情况下每轮任务都会留下一条记忆
                                    "这次没写入记忆（归纳调用失败，详见日志）"
                                },
                                "助手",
                            )
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
            // 把模型这一侧的历史接住，随对话一起落盘 —— 下一次任务
            // 就是靠这一份才"记得"上一轮说过什么。
            // 带图的消息在图被剥掉后无法逐字复原，所以只留文本
            agent.finalHistory
        } finally {
            // 兜底：Agent 万一提前抛了，也要收口，否则 run.log 停在半截
            logger?.close()
        }
        // 写回共享状态：**不能只在这里存一次** —— 上面那个 600ms 防抖的
        // 自动保存读的也是这个变量，不同步的话它会把旧值（空的）盖回来
        modelHistory = finalHistory
        modelFingerprints = llm.fingerprintChain()
        addLog(
            LogKind.SYSTEM,
            if (finalHistory.isEmpty()) {
                "这段上下文是空的（任务没走到需要记住的步骤）"
            } else {
                "这段上下文已保存 ${finalHistory.size} 条模型历史，下一次任务会接着它继续。"
            },
            "上下文",
        )
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
            clearContextWithMarker(
                context.getString(
                    if (policy == ContextPolicy.RESET_EACH_TIME) {
                        R.string.context_cleared_policy
                    } else {
                        R.string.context_cleared_idle
                    }
                )
            )
        }

        // 这一段是不是"新开的"：刚清过、或者本来就是空的
        val freshContext = conversation.size == 0

        // 系统提示词里的记忆快照。
        //   - 新开一段上下文：从记忆文件里读**当前**内容，并把它固定下来
        //   - 接着上一段：沿用当初那一份，**不重新读**
        // 理由见 contextMemory 的注释：系统提示词是前缀的开头，它必须逐字不变
        val memory = if (freshContext) MemoryStore.readForPrompt(context) else contextMemory
        if (freshContext) contextMemory = memory

        // 模型那一侧要和界面那一侧同步：记忆换了，前面攒的历史也就没意义了
        // （历史本身就是用旧记忆那段对话攒出来的）
        // 整段上下文一起接着走，或者整段一起重开 —— 不存在只换一半
        val carried = if (freshContext) {
            modelHistory = emptyList()
            modelFingerprints = emptyList()
            CarriedContext(memorySnapshot = memory)
        } else {
            CarriedContext(
                history = modelHistory,
                fingerprints = modelFingerprints,
                memorySnapshot = memory,
            )
        }

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

            // ---- 副屏模式：先建屏、切通道 ----
            // 只有**定时任务**才可能走副屏（逐条任务自己的选项，见 Schedule）。
            // 手动输入的任务一律主屏 —— 主屏有控件树，定位比副屏的"看截图猜坐标"准得多。
            var vdCreated = false
            if (runOnVirtualDisplay) {                addLog(LogKind.SYSTEM, "正在创建副屏 ...", "副屏")
                val st = runCatching { VirtualDisplayManager.create(context) }
                    .getOrElse {
                        VirtualDisplayManager.State(message = "创建失败：${it.message}")
                    }
                val id = st.displayId
                if (id == null) {
                    // 明确中止而不是偷偷退回主屏 —— 用户选的是副屏模式，
                    // 悄悄在主屏上操作会让他以为 AI 在别处动手
                    addLog(
                        LogKind.ERROR,
                        "副屏没能创建：${st.message}",
                        "副屏",
                    )
                    isRunning = false
                    progress = ""
                    OverlayService.stop(context)
                    return@launch
                }
                vdCreated = true
                controller.enterVirtualDisplay(
                    displayId = id,
                    size = VirtualDisplayManager.DEFAULT_WIDTH to VirtualDisplayManager.DEFAULT_HEIGHT,
                )
                addLog(LogKind.SYSTEM, "副屏已就绪（id=$id），AI 将在这块屏上操作", "副屏")

                // 自动把镜像窗口弹出来，让用户看着 AI 在副屏里干活。
                // 注意：这里是 Composable 作用域，够不到 Activity 的成员，
                // 必须走 context（本项目已经在这里踩过三次了）
                runCatching { context.startActivity(MirrorActivity.intent(context, id)) }
            } else {
                // 主屏模式：把界面让开，否则 Agent 第一步要额外按一次 Home，
                // 而且中间那一下用户会看到纸盒自己的界面被当成操作对象。
                hostActivity?.moveTaskToBack(true)
            }

            // 自动截图：任务期间每 5 秒一张，**故意不隐藏任何 UI** ——
            // 这张图是给人排查用的，要的就是所见即所得（悬浮窗、状态栏、
            // 别家应用的弹窗全留在画面里）。Agent 自己那张图是给模型看的，
            // 才会先藏悬浮窗，两者目的不同。
            val capJob = if (settings.saveLogs) {
                launch {
                    if (settings.saveLogs) AppLog.i(
                        "自动截图已开启：每 ${AutoCapture.INTERVAL_MS / 1000} 秒一张（不隐藏任何 UI）",
                        "截图",
                    )
                    while (isActive) {
                        runCatching {
                            controller.captureFrame()?.let { AutoCapture.save(context, it) }
                        }
                        delay(AutoCapture.INTERVAL_MS)
                    }
                }
            } else {
                null
            }

            try {
                runTask(task, carried)
            } catch (t: Throwable) {
                addLog(LogKind.ERROR, "执行出错：${t.message}", "错误")
                if (settings.saveLogs) AppLog.e("执行出错：$t", "任务")
            } finally {
                capJob?.cancel()
                // 副屏是我们建的，收尾时撤掉 —— 不然会一直挂在系统里，
                // 用户下次打开"开发者选项 → 模拟副屏"会看到一块莫名其妙的屏
                if (vdCreated) {
                    controller.exitVirtualDisplay()
                    runCatching { VirtualDisplayManager.remove(context) }
                }
                // 副屏是**逐条任务**的选项，跑完就复位，
                // 免得下一条手动输入的任务莫名其妙跑到副屏上
                runOnVirtualDisplay = false
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
        clearContextWithMarker(context.getString(R.string.context_cleared_manual))
        toast = context.getString(R.string.settings_clear_context_done)
    }

    /** 导出：把所有 debug 相关的东西打成一个 zip，走系统分享面板 */
    fun exportLogs() {
        val f = LogExporter.exportEverything(context, buildDeviceSummary(context, settings))
        if (f == null) {
            toast = context.getString(R.string.settings_export_none)
            return
        }
        toast = context.getString(R.string.settings_export_done, f.name)
        LogExporter.share(context, f, "纸盒日志")
    }

    /** 删除日志：只清运行痕迹，不动记忆 / 技能 / 定时任务 */
    fun deleteLogs() {
        val n = LogExporter.deleteAllLogs(context)
        refreshStats()
        toast = context.getString(R.string.log_delete_done)
        if (n == 0) toast = context.getString(R.string.settings_export_none)
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
        // 这条任务的副屏选项要在 submit() 之前放好 —— submit 里会读它
        runOnVirtualDisplay = pendingUseVirtualDisplay
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
            onVirtualDisplay = { screen = Screen.VIRTUAL_DISPLAY },
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

        Screen.VIRTUAL_DISPLAY -> VirtualDisplayScreen(
            onBack = { screen = Screen.CONTROL },
            onOpenMirror = { displayId ->
                // 副屏画面单独开一个窗口显示（用户要的"独立小窗"效果）。
                // 这里必须用 context.startActivity —— Composable 里够不到
                // Activity 的成员方法（和之前 requestExactAlarm 踩的是同一个坑）
                runCatching { context.startActivity(MirrorActivity.intent(context, displayId)) }
            },
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
            onRequestExactAlarm = onRequestExactAlarm,
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
            onExportLogs = { exportLogs() },
            onDeleteLogs = { deleteLogs() },
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
    onDone: (String?) -> Unit,
) {
    if (!settings.memoryEnabled) return
    // 只归纳这一段任务自己产生的轮次。把整段上下文都喂进去的话，
    // 之前任务的内容会被反复重新归纳 —— 既贵，又会让同一条认知
    // 在记忆文件里越滚越多份
    val turns = conversation.snapshot().drop(sinceTurn.coerceAtLeast(0))
    if (turns.isEmpty()) {
        onDone(null)
        return
    }
    scope.launch {
        val title = runCatching { MemoryWriter.write(context, llm, turns) }.getOrNull()
        if (title != null && settings.saveLogs) AppLog.i("记忆已更新：$title", "记忆")
        onDone(title)
    }
}

/**
 * 导出包里 device.txt 的内容。
 *
 * 这份东西是"排查时第一批要问的问题"的答案 —— 版本、机型、模型、
 * 思考档位、上下文策略、装了什么技能、记忆多大。写进包里，
 * 用户就不用再来回回答这些问题了。
 *
 * ⚠️ **绝不能带 API Key**：这里只写打码后的形式。
 */
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

/** 已学会技能的摘要，给界面用（界面不需要知道步骤细节） */
private fun loadMacroSummaries(context: android.content.Context): List<MacroSummary> =
    MacroStore.loadAll(context).map { MacroSummary(it.id, it.title, it.steps.size) }

/** 字节数转可读文本 */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
