package com.aiphone.assistant

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.aiphone.assistant.R
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.SettingsStore
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.log.LogExporter
import com.aiphone.assistant.memory.Conversation
import com.aiphone.assistant.memory.InsightStore
import com.aiphone.assistant.memory.RawDistiller
import com.aiphone.assistant.memory.Turn
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
}

/**
 * 界面根节点。
 *
 * 用本地状态驱动。等 Agent 循环接上来（会有协程、需要跨重组存活）时
 * 再换 ViewModel，各界面不用改。
 */
@Composable
private fun AppRoot(
    controller: ChannelController,
    store: SettingsStore,
    appVersion: String,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.CONTROL) }
    var input by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(store.load()) }
    var isRunning by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(AutoService.isConnected) }
    var logStats by remember { mutableStateOf("") }
    var insightCount by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }

    val logs = remember { mutableStateListOf<LogEntry>() }
    val conversation = remember { Conversation() }

    fun addLog(kind: LogKind, text: String, label: String? = null) {
        logs.add(LogEntry(id = "l${logs.size}_${System.currentTimeMillis()}", kind = kind, text = text, label = label))
    }

    fun refreshStats() {
        val runs = AppLog.listRuns(context)
        val total = runs.sumOf { run -> run.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        logStats = context.getString(R.string.settings_log_stats, runs.size, formatBytes(total))
        insightCount = InsightStore.list(context).size
    }

    // 每次回到前台重新判断授权状态，并处理"闲置超时自动清空上下文"
    var resumeTick by remember { mutableIntStateOf(0) }
    val hostLifecycle = (LocalContext.current as? ComponentActivity)?.lifecycle
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
        refreshStats()

        // 闲置超时：该清的清掉
        if (conversation.isIdleBeyond(settings.autoClearMinutes)) {
            persistConversationIfNeeded(conversation, settings, context)
            conversation.clear()
            if (settings.saveLogs) {
                AppLog.i("上下文闲置超时，已清空", "记忆")
            }
        }
    }

    /**
     * 执行一次任务。
     *
     * **注意：AI 决策循环还没接上。** 这里做的是循环里"看"的那一半 ——
     * 探测通道、取屏幕尺寸、读控件树、截图 —— 并且全部写进本次运行的日志。
     *
     * 之所以先把它接通，是因为这条链路（截图 → 落盘 → 导出）是后面
     * 一切的基础。它现在就是**可验证**的：发一条指令，导出日志，
     * 里面能看到截图文件和控件树，说明地基是通的。
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
                    detail = settings.detail,
                ),
            )
        } else {
            null
        }

        logger?.line("任务：$task", tag = "任务")
        addLog(LogKind.ACTION, task, "指令")

        // 1. 通道能不能用
        val problem = controller.probe()
        if (problem != null) {
            logger?.error(problem, "通道")
            addLog(LogKind.ERROR, problem, "通道不可用")
            logger?.close()
            return
        }
        logger?.line("通道就绪：${settings.mode.label}", "通道")
        addLog(LogKind.RESULT, "通道就绪：${settings.mode.label}", "通道")

        // 2. 屏幕尺寸
        val size = controller.screenSize()
        val w = size?.first ?: 0
        val h = size?.second ?: 0
        logger?.line("屏幕：${w} x ${h}", "通道")

        // 3. 控件树 —— 这才是定位主力
        val tree = controller.readUiTree()
        val nodeCount = tree?.lines()?.count { it.isNotBlank() } ?: 0
        logger?.line("控件树：$nodeCount 个元素", "UI")
        if (tree != null) {
            logger?.section("控件树内容")
            tree.lines().forEach { logger?.line("  $it") }
        }
        addLog(LogKind.THOUGHT, "读到 $nodeCount 个可操作元素", "控件树")

        // 4. 截图
        val shot = controller.captureFrame()
        if (shot != null && shot.isNotEmpty()) {
            val saved = if (settings.saveScreenshots) {
                logger?.saveScreenshot(1, shot)
            } else null
            val msg = if (saved != null) {
                "截图已保存：${saved.name}（${shot.size} 字节）"
            } else {
                "截图成功（${shot.size} 字节，未保存到本地）"
            }
            logger?.line(msg, "截图")
            addLog(LogKind.RESULT, msg, "截图")
        } else {
            val msg = "截图失败。可能是页面有安全保护（银行/支付类），或者被系统限流。"
            logger?.error(msg, "截图")
            addLog(LogKind.ERROR, msg, "截图")
        }

        logger?.line("说明：AI 决策循环尚未接入，本次只完成了「看」这一步。", "状态")
        logger?.close()
    }

    fun submit() {
        val task = input.trim()
        if (task.isBlank() || isRunning) return
        input = ""
        conversation.add(Turn(Turn.Role.USER, task))

        scope.launch {
            isRunning = true
            try {
                runTask(task)
            } catch (t: Throwable) {
                addLog(LogKind.ERROR, "执行出错：${t.message}", "错误")
                if (settings.saveLogs) AppLog.e("执行出错：${t}", "任务")
            } finally {
                isRunning = false
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
            if (settings.saveLogs) {
                AppLog.i("手动清空上下文（原有 $had 轮）", "记忆")
            }
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
                logStats = logStats,
                insightCount = insightCount,
                toast = toast,
            ),
            onSettingsClick = { screen = Screen.SETTINGS },
            onInputChange = { input = it },
            onSubmit = { submit() },
            onStop = { isRunning = false },
            onControlPhone = { screen = Screen.CONTROL },
        )

        Screen.SETTINGS -> SettingsScreen(
            state = MainUiState(
                settings = settings,
                authorized = authorized,
                logStats = logStats,
                insightCount = insightCount,
                toast = toast,
            ),
            onSettingsChange = { next ->
                settings = next
                store.save(next)
                // 设置变更也记一笔 —— 出问题时"当时是什么配置"是第一批要问的
                if (next.saveLogs) {
                    AppLog.i(
                        "设置变更：通道=${next.mode.label} 模型=${next.modelName} " +
                            "精度=${next.detail} 记忆=${next.memoryEnabled} " +
                            "自动清空=${next.autoClear.label}",
                        "设置",
                    )
                }
            },
            onBack = { screen = Screen.CONTROL; toast = null },
            onGotoAuth = { onOpenAccessibilitySettings() },
            onClearContext = { clearContext() },
            onExportLatest = { exportLatest() },
            onExportAll = { exportAll() },
        )
    }
}

/**
 * 开了"保存记忆"就把上下文沉淀成 md。
 *
 * 现在的 distiller 是 [RawDistiller]：原样存，不做归纳 ——
 * 因为真正的归纳要调大模型，那是下一步。
 * 但**存这个动作本身是真的在工作的**，内容不会丢，
 * 接上模型后把 RawDistiller 换掉即可，这里一行都不用改。
 */
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

/** 字节数转可读文本 */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
