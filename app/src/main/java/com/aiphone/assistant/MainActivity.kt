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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiphone.assistant.agent.Agent
import com.aiphone.assistant.agent.AgentPrompt
import com.aiphone.assistant.agent.ReviewShortcut
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
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.log.AutoCapture
import com.aiphone.assistant.log.CrashGuard
import com.aiphone.assistant.log.LogExporter
import com.aiphone.assistant.memory.Conversation
import com.aiphone.assistant.memory.MemoryStore
import com.aiphone.assistant.memory.MemoryWriter
import com.aiphone.assistant.memory.Turn
import com.aiphone.assistant.record.MacroLearner
import com.aiphone.assistant.record.MacroStore
import com.aiphone.assistant.record.Recorder
import com.aiphone.assistant.record.RecordingShortcut
import com.aiphone.assistant.schedule.PendingRunTask
import com.aiphone.assistant.schedule.ScheduleReceiver
import com.aiphone.assistant.schedule.Schedule
import com.aiphone.assistant.schedule.ScheduleStore
import com.aiphone.assistant.schedule.Scheduler
import com.aiphone.assistant.task.CRASH_NOTICE_ID_PREFIX
import com.aiphone.assistant.task.TASK_INTERRUPTED_ID_PREFIX
import com.aiphone.assistant.task.TaskHost
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.overlay.OverlayService
import com.aiphone.assistant.shell.ShizukuBridge
import com.aiphone.assistant.skill.SkillContext
import com.aiphone.assistant.skill.SkillRegistry
import com.aiphone.assistant.ui.AboutScreen
import com.aiphone.assistant.ui.AutoReturnOnForegroundEffect
import com.aiphone.assistant.ui.LABEL_COMMAND
import com.aiphone.assistant.ui.RECORDING_ENTRY_ENABLED
import com.aiphone.assistant.ui.LABEL_SCHEDULE
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
import com.aiphone.assistant.ui.UpdateDialog
import com.aiphone.assistant.ui.design.AppAlertDialog
import com.aiphone.assistant.update.ApkInstaller
import com.aiphone.assistant.update.UpdateChecker
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
        // 日志系统已经在 PaperboxApp.onCreate 里 attach 过了（那里更早，
        // 服务/广播先起来时也能写盘）。这里再调一次是幂等的兜底，
        // 防止有人把 Application 换掉之后日志静默失效。
        AppLog.attach(this)

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
        // 任务内容不经过 Intent —— 只认本进程发出的、一次性令牌匹配的交接。
        // MainActivity 是导出的（桌面启动器要能拉起），如果这里直接读
        // `run_task`，任何第三方 App 都能伪造一条任务让 AI 执行。
        val task = PendingRunTask.consume(
            intent?.getStringExtra(PendingRunTask.EXTRA_HANDOFF_TOKEN)
        ) ?: return
        pendingTask.value = task.text
        // 副屏是逐条定时任务的选项；手动输入的任务不会走这里
        pendingUseVirtualDisplay.value = task.useVirtualDisplay
        // 界面已经在用户眼前了，那条提醒就多余了。放在这一层是因为
        // 只有 Activity 拿得到 scheduleId（Compose 那一层只拿到任务文本）
        task.scheduleId?.let { ScheduleReceiver.cancelNotification(this, it) }
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

    // 定时任务的交接常量在 PendingRunTask / ScheduleReceiver 里。
    // MainActivity 不再从 Intent 读取任务内容 —— 见 readScheduledIntent()。

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
    // 任务状态与协程都挂在 Application 上：Activity 因旋转/分屏重建时，
    // 任务不会跟着 rememberCoroutineScope 一起被取消（见 TaskHost）。
    val host = (context.applicationContext as PaperboxApp).taskHost
    val scope = host.scope

    // 通知权限（Android 13+）。前台服务的常驻通知里挂了「急停」按钮；
    // 用户拒绝也没关系 —— 悬浮窗上的急停照常能用，所以不阻塞流程。
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响任务能否跑 */ }

    var screen by remember { mutableStateOf(Screen.CONTROL) }
    var input by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(store.load()) }
    var isRunning by host.isRunningState
    var stopRequested by host.stopRequestedState

    /**
     * 上一趟任务的 Agent。复评要用它手里的完整历史 + 系统提示词 ——
     * 复用同一份才能吃到缓存（见 Agent.review 的说明）。
     */
    var lastAgent by host.lastAgentState
    var reviewing by host.reviewingState
    // 「真的停下来了」以 Agent 为准：它在等待点广播，界面只照它显示。
    // 不用本地开关的原因很简单 —— 点了暂停到它真停下之间有几十毫秒，
    // 那段时间界面已经说"已暂停"、而 AI 还在动，是最让人心慌的错位。
    val isPaused by OverlayBus.paused.collectAsState()
    // 悬浮窗上按了「补充」→ 把纸盒拉到前台并把输入框亮起来，等用户打字。
    // 没有这个的话，按了「补充」用户还得自己切回纸盒找输入框 ——
    // 「补充」这个按钮就白按了。
    val supplementTick by OverlayBus.supplementSignal.collectAsState()
    // 正在打包日志。日志大的时候打包是分钟级的活，界面必须有个交代，
    // 否则用户连点几下都只看到"没反应"，只能认为按钮坏了
    var exporting by remember { mutableStateOf(false) }
    var deletingLogs by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(AutoService.isConnected) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // 通知权限：Android 13 以下默认有。**只有「权限」页读它** ——
    // 以前这个权限是发任务时静默弹的、状态从不回读，用户拒绝后
    // 通知栏的急停入口凭空消失却查不出原因。
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

    /** Shizuku 当前状态（设置页「副屏」状态行用） */
    var shizukuState by remember { mutableStateOf("") }

    /** Shizuku 是不是真的可用（「操作方式」里 ADB / Shizuku 能不能选，看它） */
    var shizukuReady by remember { mutableStateOf(false) }

    /**
     * 本次任务是从上下文里的第几轮开始的。
     *
     * 写记忆时只归纳**这一段任务自己产生**的轮次。不记这个的话，
     * "不限"档下一条长对话会被反复整体归纳 —— 又贵，又会把前面任务的
     * 内容重新总结一遍塞进记忆里。
     */
    var taskTurnStart by host.taskTurnStartState
    var progress by host.progressState
    var toast by remember { mutableStateOf<String?>(null) }

    // ---- 操作记录 ----
    var recordingActive by remember { mutableStateOf(Recorder.isRecording) }
    var recordedSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    var macros by remember { mutableStateOf(loadMacroSummaries(context)) }
    var learning by remember { mutableStateOf(false) }

    // ---- 定时任务 ----
    var schedules by remember { mutableStateOf(ScheduleStore.loadAll(context)) }
    var exactAlarmGranted by remember { mutableStateOf(Scheduler.canScheduleExact(context)) }

    // 当前技能目录（系统提示词的一部分）：用于和磁盘快照比对、以及新开上下文时固定
    val currentCatalog = remember {
        SkillRegistry(
            ctx = SkillContext(context.applicationContext, controller),
            extra = MacroStore.loadAll(context),
        ).catalog()
    }

    // 对话从磁盘恢复。提示词版本 / 模型名 / 技能目录任一对不上就按新开处理
    // （旧前缀已失效，接着用只会静默失缓存）。技能目录和记忆一样
    // 是「冻结快照」：存下来、续接时沿用当初那一份，学了新技能
    // 不会把旧上下文清掉（想用新技能就手动清空重开）。
    //
    // 恢复只在第一次进主界面时做（TaskHost 内部有 initialized 守卫），
    // Activity 重建时直接用内存里那份 —— 重新读盘会把任务刚产生的
    // 日志和历史盖掉。
    host.initialize(settings, currentCatalog)
    val diskContext = host.diskContext
    val savedContext = host.savedContext
    val logs = host.logs
    val conversation = host.conversation

    /**
     * 定时任务放在哪块屏上跑。
     *
     * 由定时任务自己带进来（见 `Schedule.useVirtualDisplay`），
     * 手动输入的任务永远是 false。所以它不能放进 AppSettings ——
     * 那是个全局开关，没法逐条任务区分。
     */
    var runOnVirtualDisplay by host.runOnVirtualDisplayState

    /**
     * **模型那一侧的历史**，和 [logs] 的作用域一样长。
     *
     * 它只在两处变：任务结束（接住 Agent 攒下的）、清空上下文（归零）。
     * 落盘和 [logs] 走同一个 `ContextStore.save` —— 分成两次写的话，
     * 那个防抖保存会用旧值把新历史盖掉。
     */
    var modelHistory by host.modelHistoryState

    /**
     * 上一个请求的指纹链，跨任务维持"前缀复用"自查。
     *
     * 和 [modelHistory] 同生共死：少了它，这次任务的第一次请求就没有
     * "上一次"可比，日志里永远是 0/0，前缀有没有被打断看不出来。
     */
    var modelFingerprints by host.modelFingerprintsState

    /**
     * 这段上下文**开头**注入进系统提示词的那份记忆。
     *
     * 系统提示词是前缀的第 0 个 token，它一旦变化，整段上下文的缓存全废。
     * 所以记忆文件即使后来变长了，这段上下文里也只能继续用当初那一份 ——
     * 想用新的，就清空上下文重开一段。
     */
    var contextMemory by host.contextMemoryState

    /**
     * 这段上下文**系统提示词里用的那份技能目录快照**。
     *
     * 和 [contextMemory] 同理：系统提示词是前缀的第 0 个 token，它一变
     * 整段上下文的缓存就全废。用户学会/删掉一个录制技能都会改实时目录，
     * 所以这里固定住当初那一份 —— 技能**执行**仍走实时注册表（新技能立刻
     * 可用），想知道提示词里也有新技能，就清空上下文重开一段。
     */
    var contextSkillCatalog by host.contextSkillCatalogState

    /**
     * 这条上下文**建起来时用的模型名**。
     *
     * 和 [contextSkillCatalog] 是同一类东西：模型名进系统提示词的第一句，
     * 也就是缓存前缀的第 0 个 token，换了就必须重开一段，否则从这一步起
     * 整段历史全按未命中计价（只烧钱、不报错）。
     *
     * 但**判断的时机**不能放在设置页。模型名输入框每敲一个字都会走一次
     * onSettingsChange，在那儿清上下文 = 用户打 "deepseek-reasoner" 就被
     * 连清 17 次，每次还都落盘 + 遍历日志目录，对话里刷一屏"上下文已重开"。
     * 所以这里冻结快照，真正比对放到 [submit] —— 那一刻才是它唯一有意义的时刻。
     */
    var contextModelName by host.contextModelNameState

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

    // 因提示词版本变化作废旧上下文时，往 run.log 也记一笔
    LaunchedEffect(diskContext, savedContext) {
        if (savedContext == null && diskContext?.entries?.isNotEmpty() == true &&
            settings.saveLogs
        ) {
            AppLog.w("提示词版本 / 模型 / 技能目录变化，旧上下文作废、已开新上下文", "上下文")
        }
    }

    // 对话变化之后落盘。600ms 防抖：一次任务会连续加十几条消息，
    // 每条都写一次文件没必要（而且这文件在"不限"档下会越来越大）
    //
    // 模型历史一起存：它和界面消息必须是**同一次**写入，否则两边会不一致
    // （见 modelHistory 的说明）
    LaunchedEffect(logs.size, conversation.size, modelHistory, modelFingerprints, contextMemory, contextSkillCatalog, contextModelName) {
        if (logs.isEmpty() && conversation.size == 0 && modelHistory.isEmpty()) return@LaunchedEffect
        delay(600)
        // 落盘放 IO："不限"档下 conversation.json 能有几百 KB（历史留到 40 万字符），
        // 主线程写它会在每次任务结束时顿一下
        withContext(Dispatchers.IO) {
            ContextStore.save(
                context = context,
                // 崩溃提示不落盘：它是"这一次启动发现的情况"，不是对话内容。
                // 存进去的后果是它会跟着历史一直留着，用户以后每次打开都看到
                // 一条早就不成立的"上次崩溃了"，导出日志时也会混在对话里。
                // 要不要提示每次启动重新判定（见上面 logs 的初始化）。
                entries = logs.filterNot {
                    it.id.startsWith(CRASH_NOTICE_ID_PREFIX) ||
                        it.id.startsWith(TASK_INTERRUPTED_ID_PREFIX)
                },
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

    /**
     * 刷新设置页底部那几行统计。
     *
     * ⚠️ **重活必须离开主线程。** 这个方法挂在「每次回到前台」的
     * LaunchedEffect 上，也在 clearContextWithMarker 里被调 —— 也就是说
     * 每次切回纸盒、每次清上下文都会跑一遍。而它里面有几处是**递归遍历目录**：
     *
     *   AppLog.listRuns + walkTopDown()   每次运行一个目录，逐个文件取 length
     *   AutoCapture.count / totalBytes    同上
     *   MemoryStore.stats                 读记忆文件
     *
     * 开了「保存截图」之后，一个跑了十几步的任务就是十几个文件；历史攒多了
     * 这里是几百次 stat 调用。放在主线程上就是每次回前台顿一下。
     *
     * 所以拆成两半：便宜且需要立刻反映的（定时任务、Shizuku 状态、宏列表）
     * 同步做完；要遍历目录的扔 IO 线程，算完再回主线程更新 state。
     * 代价是那三行统计会晚十几毫秒才刷新 —— 值得。
     */
    fun refreshStats() {
        macros = loadMacroSummaries(context)
        schedules = ScheduleStore.loadAll(context)
        exactAlarmGranted = Scheduler.canScheduleExact(context)
        val sz = ShizukuBridge.state(context)
        shizukuState = sz.label
        shizukuReady = sz == ShizukuBridge.State.READY

        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val runs = AppLog.listRuns(context)
                val total = runs.sumOf { run ->
                    run.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
                val crashCount = CrashGuard.listAll(context).size
                val log = buildString {
                    append(
                        context.getString(
                            R.string.settings_log_stats, runs.size, formatBytes(total)
                        )
                    )
                    // 崩溃记录不是"某次运行"，单独一块统计里也标一下 ——
                    // 否则用户在设置页看不出日志里还有这类东西
                    if (crashCount > 0) {
                        append(context.getString(R.string.settings_log_crash, crashCount))
                    }
                }

                val (memCount, memChars) = MemoryStore.stats(context)
                val mem = if (memCount > 0) {
                    context.getString(
                        R.string.settings_memory_stats, memCount,
                        formatBytes(memChars.toLong())
                    )
                } else {
                    ""
                }

                val capCount = AutoCapture.count(context)
                val cap = if (capCount > 0) {
                    context.getString(
                        R.string.log_autocap_stats,
                        capCount,
                        formatBytes(AutoCapture.totalBytes(context)),
                    )
                } else {
                    ""
                }
                Triple(log, mem, cap)
            }
            logStats = snapshot.first
            memoryStats = snapshot.second
            autoCapStats = snapshot.third
        }
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
        // 新开一段就用**当前**技能目录（学会技能后清空一次即可让提示词跟上）
        contextSkillCatalog = SkillRegistry(
            ctx = SkillContext(context.applicationContext, controller),
            extra = MacroStore.loadAll(context),
        ).catalog()
        // 模型名同理：新的一段按当前设置算，不然下一次任务会立刻又判定"换过模型"
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
        if (LlmConfig.missingApiKey(settings.useFreeModel, settings.apiKey)) {
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
                    customEffort = settings.customEffort,
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
    val hostActivity = LocalContext.current as? ComponentActivity

    var resumeTick by remember { mutableIntStateOf(0) }

    // 批 3（需求 F）：纸盒被带回前台 → 自动从副屏回迁。
    // 主界面挂一份，镜像页（MirrorActivity）里也挂了一份 —— 实测用户点纸盒图标
    // 落在的是镜像页，只挂这里的话需求 F 的核心场景根本不触发。
    // 详见 ui/AutoReturnEffect.kt 的注释。
    AutoReturnOnForegroundEffect()

    val hostLifecycle = hostActivity?.lifecycle
    DisposableEffect(hostLifecycle) {
        val observer = hostLifecycle?.let {
            LifecycleEventObserver { _, event ->
                // 只维护「刷新授权状态」这条；自动回迁的检测在 AutoReturnOnForegroundEffect
                if (event == Lifecycle.Event.ON_RESUME) resumeTick++
            }
        }
        if (hostLifecycle != null && observer != null) hostLifecycle.addObserver(observer)
        onDispose {
            if (hostLifecycle != null && observer != null) hostLifecycle.removeObserver(observer)
        }
    }

    /**
     * 悬浮窗按了「补充」：把输入框亮起来。
     *
     * AI 那边已经停住了（[OverlayBus.requestPause]），这边只管让用户
     * **能立刻打字**。用 [supplementTick] 当 key —— 每次按「补充」时间戳
     * 都会变，所以同一个值重复按也能再触发一次。
     */
    LaunchedEffect(supplementTick) {
        if (supplementTick == 0L) return@LaunchedEffect
        // 纸盒不在前台的话先拉回来 —— 用户多半正盯着被 AI 操作的那个应用。
        // REORDER_TO_FRONT 不会重建 Activity，只是把它挪到前面（状态都还在）。
        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching { context.startActivity(intent) }
        toast = "AI 已停下，补充一句就会接着走"
        // 屏上已经接过手了，把一次性信号清回 0 —— 否则下次进主界面
        // 会拿这个旧时间戳再亮一次输入框（用户没按「补充」却弹出键盘）
        OverlayBus.clearSupplement()
    }

    LaunchedEffect(resumeTick) {
        // 每次回到前台都重新问一次系统，而不是只在启动时查一次 ——
        // 用户去系统设置开完无障碍回来，正好走到这里。
        authorized = AutoService.isConnected
        // 用户可能刚从系统设置里开完悬浮窗 / 通知回来，每次前台都重查
        overlayGranted = Settings.canDrawOverlays(context)
        notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
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
        addLog(LogKind.ACTION, task, LABEL_COMMAND)

        if (LlmConfig.missingApiKey(settings.useFreeModel, settings.apiKey)) {
            val msg = "还没填 API Key。到「设置 → 模型」里填一个，或切到「免费」模式再试。"
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
                    customEffort = settings.customEffort,
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
            // 系统提示词吃的是**这段上下文开头的目录快照**，不是上面这份实时目录 ——
            // 实时目录会随"学会/删掉技能"变化，那会让整段上下文的前缀缓存静默失效
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
                    // 记忆归纳是后台动作：不再插「正在整理记忆」卡片，
                    // 结果用轻 toast 提示（见 onDone）
                    memorizeAfterTask(
                        scope = scope,
                        context = context.applicationContext,
                        settings = settings,
                        conversation = conversation,
                        sinceTurn = taskTurnStart,
                        llm = llm,
                        onDone = { outcome ->
                            // 新增记忆给轻 toast、不占对话卡片；没写入是常态
                            // （没什么可记），保持安静，原因仍在 run.log 可查
                            if (outcome is MemoryWriter.Outcome.Written) {
                                toast = "已新增记忆：${outcome.title}"
                            }
                            refreshStats()
                        },
                    )

                    // 收尾卡只出现一次。
                    //
                    // Agent 在结束之前，往往已经用更具体的标签（「做不了」/「完成」/
                    // 「模型出错」…）把同一句话说过一遍了；这里再原样发一张，
                    // 用户看到的是**两段完全相同的文字**，像界面出了错。
                    // 所以就地把那条升级成收尾卡 —— 每次任务结尾仍然恰好一张。
                    val last = logs.lastOrNull()
                    if (last != null && last.text == message) {
                        logs[logs.lastIndex] = last.copy(
                            kind = if (success) LogKind.RESULT else LogKind.ERROR,
                            label = if (success) "任务完成" else "任务结束",
                        )
                    } else {
                        addLog(
                            if (success) LogKind.RESULT else LogKind.ERROR,
                            message,
                            if (success) "任务完成" else "任务结束",
                        )
                    }
                }

                override fun isStopRequested(): Boolean = stopRequested
            },
        )

        // 复评要用的那趟引用：run 之前就记下，任务失败也能复盘
        lastAgent = agent

        val finalHistory = try {
            agent.run(task)
            // 把模型这一侧的历史接住，随对话一起落盘 —— 下一次任务
            // 就是靠这一份才"记得"上一轮说过什么。
            // 带图消息的原始字节也一起保留，ContextStore 会按内容标识落盘；
            // 跨任务续接时必须原样读回，否则缓存前缀会从第一张图开始失配。
            agent.finalHistory
        } finally {
            // 兜底：Agent 万一提前抛了，也要收口，否则 run.log 停在半截
            logger?.close()
        }
        // 写回共享状态：**不能只在这里存一次** —— 上面那个 600ms 防抖的
        // 自动保存读的也是这个变量，不同步的话它会把旧值（空的）盖回来
        modelHistory = finalHistory
        modelFingerprints = llm.fingerprintChain()
        // 上下文已保存是内部状态，不再作为一条日志显示给用户
    }

    /**
     * 复评：拿上一趟的历史问"哪一步错了"。
     *
     * 和正常任务共用 `isRunning` 那套「忙」状态，但**不**起悬浮窗、**不**碰无障碍 ——
     * 因为它一个动作都不会执行（[Agent.review] 的返回值只往对话里写）。
     */
    fun reviewLast(target: Agent, instruction: String) {
        if (isRunning) return
        reviewing = true
        isRunning = true
        addLog(LogKind.THOUGHT, "正在复盘这一趟（不会动手机）…", "复盘")
        scope.launch {
            val r = target.review(
                instruction.takeIf { it !in setOf("复盘", "复评", "回顾") }.orEmpty()
            )
            reviewing = false
            isRunning = false
            when (r) {
                is LlmResult.Ok -> {
                    r.text.trim().lines().filter { it.isNotBlank() }.forEach {
                        addLog(LogKind.THOUGHT, it, "复盘")
                    }
                }
                is LlmResult.Fail -> addLog(LogKind.ERROR, r.message, "复盘失败")
            }
        }
    }

    fun submit() {
        val task = input.trim()
        if (task.isBlank()) return
        // 暂停期间发出去的话不是新任务，而是"补一句" —— 并进下一轮。
        // 独立于 submit 的主流程：那边一进来就会清输入框、判权限、起悬浮窗，
        // 全部是为"开一趟新任务"准备的，对一句话来说全是副作用。
        if (isRunning) {
            if (isPaused) {
                OverlayBus.pushUserNote(task)
                input = ""
                addLog(LogKind.ACTION, task, LABEL_COMMAND)
                toast = "已并进下一轮"
            }
            return
        }
        if (deletingLogs) {
            toast = context.getString(R.string.log_delete_in_progress)
            return
        }

        // 模型配置检查必须和网络层共用同一份判据，而且必须在**起悬浮窗之前**
        // （2026-10-25 线上问题）：
        //   - 免费模式本来就不需要 Key，只认 `apiKey.isBlank()` 会把新用户拦死；
        //   - 拦截放在 runTask 里时，悬浮窗服务已经 startForegroundService 了，
        //     runTask 立刻 return → finally 马上 stopService → 服务还没
        //     startForeground 就被停，系统抛 ForegroundServiceDidNotStartInTimeException，
        //     用户看到的是「点发送就崩溃」，而不是一句可读的提示。
        if (LlmConfig.missingApiKey(settings.useFreeModel, settings.apiKey)) {
            val msg = "还没填 API Key。到「设置 → 模型」里填一个，或在同页切到「免费」模式再试。"
            addLog(LogKind.ERROR, msg, "模型未配置")
            toast = msg
            return
        }

        // 主屏模式必须先连好无障碍服务。
        //
        // ⚠️ 这个检查必须放在**任何状态改动之前**，两个理由：
        //
        // 1. 放在后面时，没开无障碍就点发送会：先 `input = ""` 把用户打的字
        //    清掉（他得重打一遍），再 `conversation.add()` 往历史里塞一条
        //    任务消息。等他去开完无障碍再发一次，同一句话就进了上下文两遍。
        //    真机上抓到的请求体确实是 [user 任务X][user 任务X][assistant …]。
        // 2. 更不能挪回协程里 —— 先 startForegroundService 起悬浮窗、再在
        //    拦截分支里 stop，服务来不及 startForeground 就被停，系统抛
        //    ForegroundServiceDidNotStartInTimeException 把整个 app 搞崩。
        //
        // 返回时不清空输入框：用户刚打的字还在，开完无障碍直接再点发送即可。
        //
        // 操作方式是 ADB / Shizuku 时不看无障碍：那条路的"能不能用"问的是
        // Shizuku（动作和截图都靠它）。无障碍这时是**可选**的 —— 开着读控件树，
        // 没开就退化成"看截图用坐标"，所以不在这里拦。
        val adbMode = settings.mode == com.aiphone.assistant.data.OperationMode.ADB
        controller.preferAdbMode(adbMode)
        if (adbMode) {
            if (ShizukuBridge.state(context) != ShizukuBridge.State.READY) {
                val why = ShizukuBridge.state(context).label
                addLog(
                    LogKind.ERROR,
                    "操作方式是「ADB / Shizuku」，但 Shizuku 现在不可用（$why）。" +
                        ShizukuBridge.startHint(context) + "。启动后到「权限」页授权一次；" +
                        "或者把「操作方式」切回无障碍。",
                    "Shizuku 不可用",
                )
                toast = "Shizuku 不可用（$why）"
                return
            }
        } else if (!runOnVirtualDisplay && !com.aiphone.assistant.a11y.AutoService.isConnected) {
            addLog(
                LogKind.ERROR,
                "还没开启无障碍服务。到「权限」页里开一下再发任务。",
                "无障碍未开启",
            )
            toast = context.getString(R.string.a11y_needed_toast)
            return
        }

        input = ""
        stopRequested = false
        OverlayBus.clearStop()
        // 批 3：上一轮任务留下的「自动回迁冷却」会压住这一轮，开新任务时清掉。
        // （冷却只在同一次任务内用来防乒乓，跨任务没有意义）
        OverlayBus.clearAutoReturnSuppression()

        // 「语音测试」= T40 的探针：发一段 1 秒静音，看这个通道收不收音频。
        // 不录音、不上传任何用户声音 —— 这一趟只为拿一个状态码。
        if (task == "语音测试" || task.equals("audio test", ignoreCase = true)) {
            input = ""
            addLog(LogKind.THOUGHT, "正在测语音输入（发一段 1 秒静音，不涉及你的录音）…", "语音")
            scope.launch {
                // 探针用一份临时客户端：submit() 这一层没有任务级的 llm
                val probe = LlmClient(
                    LlmConfig(
                        baseUrl = settings.baseUrl,
                        apiKey = settings.apiKey,
                        model = settings.modelName,
                        useFreeModel = settings.useFreeModel,
                        thinking = settings.thinking,
                    customEffort = settings.customEffort,
                    )
                )
                val r = withContext(Dispatchers.IO) { probe.probeAudio() }
                addLog(
                    if (r.ok) LogKind.RESULT else LogKind.ERROR,
                    com.aiphone.assistant.llm.AudioProbe.describe(r),
                    "语音",
                )
            }
            return
        }

        // 「复盘」= 回看上一趟、只出结论不下动作，不碰手机
        if (ReviewShortcut.matches(task)) {
            input = ""
            val target = lastAgent
            if (target == null) {
                addLog(LogKind.SYSTEM, "还没有可复盘的任务：先跑一趟再来。", "复盘")
            } else {
                reviewLast(target, task)
            }
            return
        }

        // 本地快捷指令：在发送框直接说「操作记录 / 开始录制」等，进录制页，不发给模型。
        // 录制入口已下架，这条短路也要一起关 —— 否则从输入框还能绕进那个页面
        if (RECORDING_ENTRY_ENABLED && RecordingShortcut.matches(task)) {
            screen = Screen.RECORDING
            toast = context.getString(R.string.recording_shortcut_opened)
            return
        }

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

        // 换过模型也要重开一段 —— 模型名是系统提示词的第一句，也就是缓存前缀的
        // 第 0 个 token，换了之后整段历史都会按未命中计价（只烧钱、不报错，
        // 日志里只看到命中率突然掉下去）。
        //
        // 判断放在这里而不是设置页，是因为设置页每敲一个字都会触发保存（见
        // [contextModelName] 的注释）；而且只有"真的要发任务"这一刻才有意义 ——
        // 用户改完又改回来，上下文就不用白丢。
        if (contextModelName != settings.modelName) {
            if (conversation.size > 0) {
                clearContextWithMarker("模型换成了 ${settings.modelName}，上下文已重开")
            } else {
                // 还没有上下文可清，把快照跟上就行（不然会一直判定"换过模型"）
                contextModelName = settings.modelName
            }
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
            CarriedContext(memorySnapshot = memory, skillCatalog = contextSkillCatalog)
        } else {
            CarriedContext(
                history = modelHistory,
                fingerprints = modelFingerprints,
                memorySnapshot = memory,
                skillCatalog = contextSkillCatalog,
            )
        }

        // 记住起点，任务结束时只归纳从这里往后的轮次
        taskTurnStart = conversation.size
        conversation.add(Turn(Turn.Role.USER, task))

        // 进程死亡标记：正常结束会在 finally 里删掉；没删掉就说明
        // 这次任务被系统连进程一起回收了，下次启动明确报告（见 TaskHost）
        host.markTaskStarted(task)

        scope.launch {
            isRunning = true
            // 镜像页要靠它判断"有没有任务在跑"（OverlayBus.taskRunning 的注释里说明了原因）
            OverlayBus.taskRunning = true

            // 无障碍的连接检查已经提到 submit() 最前面了（见那里的注释：
            // 放这儿会把用户输入清掉、还往上下文里多塞一条任务消息）。

            // 起前台服务。没悬浮窗权限时 OverlayService 不画窗口，
            // 但仍保持前台服务与通知栏的「急停」—— 任务照常跑，
            // 只是看不到屏幕上的进度面板。
            // 悬浮条的背景模糊是**设置项**（默认关）：在起服务之前把它同步过去，
            // 服务里建窗口时才读得到
            OverlayBus.overlayBlurEnabled = settings.overlayBlur
            if (Settings.canDrawOverlays(context)) {
                OverlayService.start(context.applicationContext)
            } else {
                addLog(
                    LogKind.ERROR,
                    "没有悬浮窗权限，这次看不到屏幕上的进度面板；" +
                        "下拉通知栏仍可用「急停」。到「权限」页里开一下可显示悬浮窗。",
                    "悬浮窗不可用",
                )
            }

            // ---- 副屏模式：先建屏、切通道 ----
            // 只有**定时任务**才可能走副屏（逐条任务自己的选项，见 Schedule）。
            // 手动输入的任务一律主屏 —— 主屏有控件树，定位比副屏的"看截图猜坐标"准得多。
            var vdCreated = false

            // 把纸盒拉回前台的局部函数
            fun bringAppToFront() {
                val intent = android.content.Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
                // 任务可能比 Activity 活得久（旋转/分屏重建），必须用
                // applicationContext + NEW_TASK，不能攥着已经销毁的 Activity
                context.applicationContext.startActivity(intent)
            }
            if (runOnVirtualDisplay) {
                addLog(LogKind.SYSTEM, "正在创建副屏 ...", "副屏")
                // 必须挪到 IO 线程：createDisplay 走的是 Shizuku 的 Binder、**同步阻塞**。
                // 留在主线程上会连卡好几秒，后果有两个：
                //   1. 上面刚起的悬浮窗前台服务来不及执行 onCreate → startForeground，
                //      系统判定"前台服务没在时限内起来"，直接抛
                //      ForegroundServiceDidNotStartInTimeException 把进程打崩
                //      （2026-09-26 在蓝叠上 5/5 必崩，真机同样跑 user 构建，风险一样）
                //   2. 建屏这几秒整个界面是冻结的
                val st = runCatching { withContext(Dispatchers.IO) { VirtualDisplayManager.create(context) } }
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
                    OverlayBus.taskRunning = false
                    progress = ""
                    // 任务结束，把纸盒界面拉回前台
                    bringAppToFront()
                    // **这里不能停悬浮窗服务**（2026-09-26 修的崩溃）：
                    // 本行就在上面 startForegroundService 之后、服务真正 startForeground
                    // 之前 —— 此刻调 stop，系统会「把还在等 startForeground 的服务拆掉」，
                    // 随后服务的 onCreate 才跑到，startForeground 已经没人认账，
                    // 系统抛 ForegroundServiceDidNotStartInTimeException 把整个 App 打崩。
                    // 复现条件很普通：Shizuku 没开（建副屏失败）→ 必崩。
                    // 悬浮窗留着无害（它就是个进度条和急停按钮），下次任务结束会一起收。
                    return@launch
                }
                vdCreated = true
                // 副屏在 shell 端照抄主屏 Display.Mode 而建，这里用同一口径取
                // 主屏物理尺寸；写死尺寸会让 screenSize() 报错值、坐标整体偏掉
                val displayManager = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                    as android.hardware.display.DisplayManager
                val mainDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val realSize = mainDisplay.mode.physicalWidth to mainDisplay.mode.physicalHeight
                controller.enterVirtualDisplay(
                    displayId = id,
                    size = realSize,
                )
                addLog(LogKind.SYSTEM, "副屏已就绪（id=$id），AI 将在这块屏上操作", "副屏")

                // 自动把镜像窗口弹出来，让用户看着 AI 在副屏里干活。
                // 注意：这里是 Composable 作用域，够不到 Activity 的成员，
                // 必须走 context（本项目已经在这里踩过三次了）
                runCatching { context.startActivity(MirrorActivity.intent(context, id)) }
            } else {
                // 主屏模式不再一启动就把纸盒退到后台（那样用户会先看到桌面）。
                // "把界面让开"改由 Agent 在真正要截图 / 操作别的 app 前按需做；
                // 第一批动作若是 open_app，则完全不弹桌面、由打开动作自己切走。
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
                            controller.captureFrame()?.let { bytes ->
                                // 文件名写进本次 run.log：这样复盘时能拿
                                // "自动截图：cap_142739_433" 这一行把画面
                                // 对齐到具体是第几步的现场
                                AutoCapture.save(context, bytes)?.let {
                                    AppLog.i("自动截图：${it.name}", "截图")
                                }
                            }
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
                    // 和建屏同理：remove 也是 Shizuku 的同步 Binder 调用，不能留在主线程
                    runCatching { withContext(Dispatchers.IO) { VirtualDisplayManager.remove(context) } }
                }
                // 副屏是**逐条任务**的选项，跑完就复位，
                // 免得下一条手动输入的任务莫名其妙跑到副屏上
                runOnVirtualDisplay = false
                isRunning = false
                OverlayBus.taskRunning = false
                progress = ""
                // 任务结束，无条件把纸盒界面拉回前台（无论中途有没有让开）
                bringAppToFront()
                OverlayService.stop(context.applicationContext)
                refreshStats()
            }
        }.invokeOnCompletion {
            // 正常结束、异常退出、副屏建屏失败提前 return —— 都算这次任务收尾了。
            // 不放在上面的 finally 里：建屏失败是在 try 之前 return 的。
            host.markTaskFinished()
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

    /**
     * 看副屏画面。
     *
     * 手动切到副屏之后主屏会回桌面，用户就看不到 AI 在那块屏上干什么了 ——
     * 副屏是虚拟的，画面没有任何天然的出口，只能靠镜像页。
     *
     * 这里只负责"把镜像页拉起来"，不建屏：没有副屏就直说，别静默失败
     * （这个项目在"点了没反应"上已经栽过一次了，见 log/LogExporter 的注释）。
     */
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
                .onFailure {
                    toast = context.getString(R.string.vd_mirror_failed, it.message ?: "")
                }
        }
    }

    /**
     * 打包日志并弹分享面板。
     *
     * ⚠️ **必须在 IO 线程做。** 打包要遍历所有运行目录、压缩全部截图，
     * 截图多的用户这里是**秒级**的活；放主线程就是一次实打实的 ANR 风险
     * （界面卡住、系统弹"应用无响应"）。打完再回主线程弹分享面板。
     */
    fun exportLogs() {
        // 防重入：连点会并发打好几份 zip（尤其是日志大的时候，
        // 第一份还没打完第二份又开跑），白烧 IO 还可能撞同一个文件名
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
                    // 打包成功**不弹提示**：系统分享面板本身就是反馈，
                    // 而且紧接着 startActivity 会把界面切走 ——
                    // 这时候塞一条 Snackbar 用户根本看不见。
                    // 只有"面板没拉起来"才需要说话。
                    val why = LogExporter.share(context, f, "纸盒日志")
                    if (why != null) {
                        toast = context.getString(R.string.settings_export_share_failed, why)
                    }
                }.onFailure { t ->
                    // 失败说清楚是哪一步、为什么。原来这里只会显示
                    // "还没有日志可导出" —— 明明有日志却说没有，
                    // 用户拿着这句话没法做任何判断
                    toast = context.getString(
                        R.string.settings_export_failed,
                        t.message ?: t.javaClass.simpleName,
                    )
                }
            } finally {
                exporting = false
            }
        }
    }

    /** 删除日志与对话历史；内存状态也要同步清掉，避免下一次防抖保存把旧内容写回磁盘。 */
    fun deleteLogs() {
        if (deletingLogs) {
            toast = context.getString(R.string.log_delete_in_progress)
            return
        }
        if (isRunning || exporting) {
            toast = context.getString(R.string.log_delete_busy)
            return
        }
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

    /**
     * 定时任务到点自动开跑。
     *
     * 必须放在 submit() 后面 —— Kotlin 的局部函数不能先用后定义，
     * 放前面是编译错误（这一步实际踩到了）。
     */
    LaunchedEffect(pendingTask) {
        val task = pendingTask ?: return@LaunchedEffect
        if (task.isBlank()) return@LaunchedEffect
        addLog(LogKind.ACTION, task, LABEL_SCHEDULE)
        input = task
        // 这条任务的副屏选项要在 submit() 之前放好 —— submit 里会读它
        runOnVirtualDisplay = pendingUseVirtualDisplay
        submit()
        onPendingTaskHandled()
    }

    // ---- 系统返回键 / 返回手势 ----
    //
    // 之前完全没接这一层：Compose 里的 [screen] 只是一个普通状态，系统的返回
    // 不认识它，于是"进设置页按一下返回"会直接 finish() 掉 Activity、退回桌面 ——
    // 用户以为自己在二级菜单里按返回，结果整个应用都没了。
    //
    // 现在按屏幕层级退回主界面；只有**已经在主界面**时才把返回让给系统，
    // 这样"连按两次返回退出应用"的直觉仍然成立。
    //
    // 弹窗（设置页的确认框等）走 Compose Dialog，它自己会先消费返回事件，
    // 不会误触发这里的翻页。
    // ---- 自动更新 ----
    //
    // 检查放在冷启动一次（用户可以在「关于」里关掉或手动触发）。
    // 三条路径共用一个弹窗：启动时静默查到、关于页手动点、下载中/失败。
    var updateInfo by remember { mutableStateOf<UpdateChecker.Info?>(null) }
    var updateProgress by remember { mutableStateOf<Int?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateCheckHint by remember { mutableStateOf("") }

    fun startUpdate() {
        val info = updateInfo ?: return
        // Android 8+ 装 APK 要用户先允许「安装未知应用」，没开就直接拉安装器
        // 只会得到一句「应用未安装」，所以先查再把人带过去
        if (!ApkInstaller.canInstall(context)) {
            ApkInstaller.requestInstallPermission(context)
            toast = "请允许纸盒安装应用，回来再点一次「立即更新」"
            return
        }
        updating = true
        updateError = null
        updateProgress = null
        scope.launch {
            try {
                val file = UpdateChecker.download(context, info) { pct ->
                    // 进度回调在 IO 线程，切回主线程改状态
                    scope.launch { updateProgress = pct }
                }
                updating = false
                updateInfo = null
                ApkInstaller.install(context, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updating = false
                updateProgress = null
                updateError = e.message ?: "下载失败，请重试"
            }
        }
    }

    fun skipUpdate() {
        val info = updateInfo ?: return
        // 只跳过这一版：记下 versionCode，下一版仍然会提示
        settings = settings.copy(skippedUpdateCode = info.versionCode)
        store.save(settings)
        updateInfo = null
        updateError = null
        toast = "已跳过 ${info.versionName}，下个版本还会提示"
    }

    fun checkUpdateManually() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateCheckHint = "正在检查…"
        scope.launch {
            val result = UpdateChecker.check(context)
            checkingUpdate = false
            result.fold(
                onSuccess = { info ->
                    if (info == null) {
                        updateCheckHint = "已经是最新版本"
                    } else {
                        // 手动点的就不看「跳过」了 —— 用户自己要查，就该看到结果
                        updateCheckHint = ""
                        updateInfo = info
                    }
                },
                onFailure = { e ->
                    updateCheckHint = "检查失败：${e.message ?: "网络不通"}"
                },
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!settings.autoCheckUpdate) return@LaunchedEffect
        val info = UpdateChecker.check(context).getOrNull() ?: return@LaunchedEffect
        if (info.versionCode == settings.skippedUpdateCode) return@LaunchedEffect
        updateInfo = info
    }

    BackHandler(enabled = screen != Screen.CONTROL) {
        screen = Screen.CONTROL
        // 上一屏留下的 toast 不该跟着回到主界面
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
                // 真急停：单向结束这一趟。Agent 在每个动作前、每次请求前都查，
                // 正在等的那次请求由它自己的看门狗掀掉连接。
                stopRequested = true
                OverlayBus.requestStop()
                OverlayBus.clearPause()
                addLog(LogKind.ERROR, "已停止，正在收尾", "停止")
            },
            onPauseToggle = {
                OverlayBus.togglePause()
                addLog(
                    LogKind.SYSTEM,
                    if (OverlayBus.pauseRequested) {
                        "已请求暂停，等它走到动作间隙。暂停时可以直接在这里打字补充指令。"
                    } else {
                        "已请求继续"
                    },
                    if (OverlayBus.pauseRequested) "暂停" else "继续",
                )
            },
            isPaused = isPaused,
            supplementTick = supplementTick,
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
                shizukuReady = shizukuReady,
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
            // 副屏状态行：按 Shizuku 当前状态处理（未授权才弹授权框，
            // 另外三个分支只是提示怎么装/怎么启动 —— 整段一起搬过来，
            // 只搬 requestPermission() 会丢掉那些提示）
            onShizukuClick = {
                when (ShizukuBridge.state(context)) {
                    ShizukuBridge.State.NO_PERMISSION -> ShizukuBridge.requestPermission()
                    ShizukuBridge.State.NOT_INSTALLED ->
                        toast = "请先安装 Shizuku 应用"
                    ShizukuBridge.State.NOT_RUNNING -> {
                        // 模拟器（BlueStacks/AVD）没有无线调试，只能靠电脑 adb 拉服务。
                        // 把**这条机器上真实有效的路径**复制给用户，比一句"请先启动服务"有用得多。
                        val cmd = ShizukuBridge.starterCommand(context)
                        if (cmd != null) {
                            runCatching {
                                context.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("启动 Shizuku", cmd))
                            }
                            toast = "已复制启动命令（在电脑上执行）：$cmd"
                        } else {
                            toast = "请先打开 Shizuku、启动服务"
                        }
                    }
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
                // 「换模型要清上下文」这件事**不在这里做** —— 这个回调每敲一个字
                // 都会触发一次（输入框的 onValueChange 直接调它），在这儿清会
                // 连清十几次（每次都落盘 + 遍历日志目录），对话里刷一屏"上下文已重开"。
                // 改到 submit() 里比对 [contextModelName]，只在真正要发任务时清一次。
                // 顺带一个好处：用户只是进设置点开看看再改回来，上下文不会白丢。
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
            onProbeVirtualDisplay = {
                // 探针要逐个窗口读 root（跨进程同步调用），不能压在主线程上 ——
                // 按钮点下去会直接卡住界面。扔 IO 线程，回来了再弹 toast。
                // 完整结果同时会写进 run.log（见 AutoService.probeWindowsOnAllDisplays）。
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        com.aiphone.assistant.a11y.AutoService.get()
                            ?.probeWindowsOnAllDisplays()
                            ?: "无障碍服务未连接"
                    }
                    // toast 只显示摘要，完整结果在 run.log 里
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
            autoCheck = settings.autoCheckUpdate,
            checking = checkingUpdate,
            checkHint = updateCheckHint,
            onAutoCheckChange = { on ->
                settings = settings.copy(autoCheckUpdate = on)
                store.save(settings)
            },
            onCheckUpdate = { checkUpdateManually() },
            onBack = { screen = Screen.CONTROL },
        )
    }

    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            progress = updateProgress,
            downloading = updating,
            error = updateError,
            onUpdate = { startUpdate() },
            onLater = {
                updateInfo = null
                updateError = null
            },
            onSkip = { skipUpdate() },
        )
    }

    // 老版本用户可能已经开着免费通道，但没有确认过风险说明。
    // 启动时补一次：确认才继续用免费通道，否则切回自定义（不会静默继续发送）。
    if (settings.useFreeModel && !settings.freeLaneNoticeAccepted) {
        AppAlertDialog(
            title = stringResource(R.string.settings_model_free_notice_title),
            body = stringResource(R.string.settings_model_free_notice_body),
            confirmText = stringResource(R.string.settings_model_free_notice_accept),
            onConfirm = {
                settings = settings.copy(freeLaneNoticeAccepted = true)
                store.save(settings)
            },
            dismissText = stringResource(R.string.settings_model_free_notice_decline),
            onDismiss = {
                settings = settings.copy(useFreeModel = false, freeLaneNoticeAccepted = true)
                store.save(settings)
            },
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
    onDone: (MemoryWriter.Outcome) -> Unit,
) {
    if (!settings.memoryEnabled) {
        onDone(MemoryWriter.Outcome.NotWritten("记忆开关是关的"))
        return
    }
    // 只归纳这一段任务自己产生的轮次。把整段上下文都喂进去的话，
    // 之前任务的内容会被反复重新归纳 —— 既贵，又会让同一条认知
    // 在记忆文件里越滚越多份
    val turns = conversation.snapshot().drop(sinceTurn.coerceAtLeast(0))
    if (turns.isEmpty()) {
        onDone(MemoryWriter.Outcome.NotWritten("这段任务没有可归纳的轮次"))
        return
    }
    scope.launch {
        // ⚠️ **必须切到 IO 线程。**
        //
        // LlmClient.chat 是同步阻塞的 HTTP 调用。这里原来直接在
        // `scope.launch`（主线程）里调用它，结果是 Android 抛
        // NetworkOnMainThreadException —— 被下面的 runCatching 吞成 null，
        // 表现就是"记忆永远写不进去，而且日志里查不出原因"。
        //
        // Agent 里那次模型调用一直有 withContext(Dispatchers.IO) 包着，
        // 所以正常任务不受影响；只有记忆归纳这条路径漏了。
        val outcome = withContext(Dispatchers.IO) {
            runCatching { MemoryWriter.write(context, llm, turns) }
                .getOrElse { e ->
                    // 异常也要落盘：这里用**应用日志**（logger 是任务级的，
                    // 任务一结束就被 close 了，写它等于没写）
                    MemoryWriter.Outcome.NotWritten(
                        "归纳调用抛异常：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
        }
        // 无论成败都记一笔。记忆写不进去是用户看得见的事，
        // 必须留下可追查的痕迹 —— 而且**要用 afterTask**：
        // 任务级 logger 在 `agent.run` 返回时就 close 了，写它等于写进黑洞。
        val line = when (outcome) {
            is MemoryWriter.Outcome.Written -> "记忆已更新：${outcome.title}"
            is MemoryWriter.Outcome.NotWritten -> "记忆未写入：${outcome.reason}"
        }
        AppLog.afterTask(
            line,
            "记忆",
            if (outcome is MemoryWriter.Outcome.NotWritten) "W" else "I",
        )
        onDone(outcome)
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
