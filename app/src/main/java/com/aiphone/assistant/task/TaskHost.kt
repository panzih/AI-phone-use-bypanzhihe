package com.aiphone.assistant.task

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.aiphone.assistant.agent.Agent
import com.aiphone.assistant.agent.AgentPrompt
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.ContextStore
import com.aiphone.assistant.data.SavedContext
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.log.CrashGuard
import com.aiphone.assistant.memory.Conversation
import com.aiphone.assistant.ui.LogEntry
import com.aiphone.assistant.ui.LogKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * 崩溃提示那条系统消息的 id 前缀。
 *
 * 它是个**会话级**提示，不是对话内容 —— 所以落盘时要按这个前缀滤掉，
 * 否则用户以后每次打开都看到一条早就过时的"上次崩溃了"。
 */
internal const val CRASH_NOTICE_ID_PREFIX = "crash_notice_"

/**
 * 上次任务未正常结束（进程被回收/强制停止）的提示 id 前缀。
 * 和崩溃提示同理：只在发现的那一次启动里显示，不写进对话历史。
 */
internal const val TASK_INTERRUPTED_ID_PREFIX = "task_interrupted_"

/**
 * 任务状态的持有者。
 *
 * ## 为什么要从 Composable 里搬出来
 *
 * 以前 `isRunning`、日志、模型历史全用 `remember` 存在主界面里，任务协程
 * 来自 `rememberCoroutineScope()`。后果是：旋转屏幕、分屏、改字体大小
 * 都会重建 Activity —— 协程被取消、状态被清空，AI 正干到一半就没了。
 *
 * 现在这些状态和协程都挂在 Application 上（[PaperboxApp.taskHost]）：
 *
 * ```
 *   Activity 重建  →  Composable 重新订阅同一份 State，任务照跑
 *   进程被系统杀  →  task_in_flight 标记还在，下次启动明确报告"任务已中断"
 * ```
 *
 * ## 什么该放这里，什么不该
 *
 * 放这里：跨 Activity 生命周期仍然要活着的东西（运行状态、日志、对话、
 * 模型历史、上下文快照、任务协程）。
 *
 * 不放这里：纯界面状态（当前在哪一页、输入框草稿、toast、各页统计），
 * 那些本来就该随 Activity 重建，重建后重新算一遍即可。
 */
class TaskHost(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 任务协程的作用域。
     *
     * 用 `SupervisorJob`：一次导出/记忆归纳失败不能把整个作用域带走。
     * 它只随进程结束而结束 —— 这正是"Activity 重建不取消任务"的关键。
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- 运行状态 ----
    val isRunningState = mutableStateOf(false)
    val stopRequestedState = mutableStateOf(false)
    val reviewingState = mutableStateOf(false)
    val lastAgentState = mutableStateOf<Agent?>(null)
    val taskTurnStartState = mutableIntStateOf(0)
    val progressState = mutableStateOf("")

    /** 定时任务自己的副屏选项；手动任务永远 false */
    val runOnVirtualDisplayState = mutableStateOf(false)

    // ---- 对话与上下文 ----
    val logs = mutableStateListOf<LogEntry>()
    val conversation = Conversation()

    val modelHistoryState = mutableStateOf<List<ChatTurn>>(emptyList())
    val modelFingerprintsState = mutableStateOf<List<Int>>(emptyList())
    val contextMemoryState = mutableStateOf("")
    val contextSkillCatalogState = mutableStateOf("")
    val contextModelNameState = mutableStateOf("")

    /** 从磁盘读到的原始上下文（可能因提示词/模型/技能目录变化被判作废） */
    var diskContext: SavedContext? = null
        private set

    /** 通过一致性检查、真正恢复出来的上下文；null = 按新开处理 */
    var savedContext: SavedContext? = null
        private set

    private var initialized = false

    /**
     * 第一次进主界面时从磁盘恢复。
     *
     * 只做一次：Activity 重建时状态已经在内存里，重新读盘反而会把
     * 这次任务刚产生的日志/历史覆盖掉。
     */
    fun initialize(settings: AppSettings, currentCatalog: String) {
        if (initialized) return
        initialized = true

        // 进程死亡报告要放在最前面：它描述的是"上一次进程"发生的事
        reportInterruptedTask()

        diskContext = ContextStore.load(appContext)
        val saved = diskContext?.takeIf {
            it.promptVersion == AgentPrompt.VERSION &&
                it.modelName == settings.modelName &&
                it.skillCatalog == currentCatalog
        }
        savedContext = saved

        logs.addAll(
            saved?.entries?.filterNot { it.isSessionNotice() } ?: emptyList()
        )

        // 磁盘里有旧对话、却因版本/模型/技能目录不匹配被作废：留一条说明，
        // 不然消息凭空消失像 bug
        if (saved == null && diskContext?.entries?.isNotEmpty() == true) {
            logs.add(
                LogEntry(
                    id = "prompt_reset_${System.currentTimeMillis()}",
                    kind = LogKind.SYSTEM,
                    text = "提示词版本 / 模型 / 技能目录变化，已开新上下文",
                    label = "上下文",
                )
            )
        }

        // 上次是崩着退出的：说一句，并指出日志在哪。
        val crashes = CrashGuard.unreportedCount(appContext)
        if (crashes > 0) {
            logs.add(
                LogEntry(
                    id = "$CRASH_NOTICE_ID_PREFIX${System.currentTimeMillis()}",
                    kind = LogKind.ERROR,
                    text = if (crashes == 1) {
                        "上次运行异常退出，崩溃现场已记录 —— 设置 → 日志 → 导出日志"
                    } else {
                        "上次运行异常退出，已记录 $crashes 份崩溃现场 —— 设置 → 日志 → 导出日志"
                    },
                    label = "崩溃",
                )
            )
            CrashGuard.markReported(appContext)
        }

        conversation.restore(
            savedTurns = saved?.turns ?: emptyList(),
            savedActivityAt = saved?.lastActivityAt ?: 0L,
        )
        modelHistoryState.value = saved?.history ?: emptyList()
        modelFingerprintsState.value = saved?.fingerprints ?: emptyList()
        contextMemoryState.value = saved?.memorySnapshot ?: ""
        contextSkillCatalogState.value = saved?.skillCatalog ?: currentCatalog
        contextModelNameState.value = saved?.modelName ?: settings.modelName
    }

    // ------------------------------------------------------------------
    // 进程死亡标记
    // ------------------------------------------------------------------

    private val markerFile: File
        get() = File(appContext.filesDir, MARKER_NAME)

    /**
     * 任务开跑时落一个标记，正常结束时删掉。
     *
     * 进程被系统回收 / 用户强行停止时，`finally` 不会执行，标记会留下来；
     * 下次启动看到它就明确报告"上次任务中断"，而不是让用户以为任务还在跑。
     */
    fun markTaskStarted(task: String) {
        runCatching {
            val payload = buildString {
                appendLine(System.currentTimeMillis())
                append(task.replace('\n', ' ').take(200))
            }
            val tmp = File(appContext.filesDir, "$MARKER_NAME.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(markerFile)) {
                markerFile.writeText(payload)
                tmp.delete()
            }
        }.onFailure {
            AppLog.w("写任务运行标记失败：${it.message}", "任务")
        }
    }

    fun markTaskFinished() {
        runCatching { markerFile.delete() }
    }

    private fun reportInterruptedTask() {
        val payload = runCatching {
            markerFile.takeIf { it.exists() }?.readText()
        }.getOrNull()
        if (payload.isNullOrBlank()) return

        val task = payload.lineSequence().drop(1).firstOrNull()?.trim().orEmpty()
        val text = if (task.isBlank()) {
            "上次任务没有正常结束（应用进程被系统回收或强制停止），任务已中断。"
        } else {
            "上次任务没有正常结束（应用进程被系统回收或强制停止），已中断：$task"
        }
        logs.add(
            LogEntry(
                id = "$TASK_INTERRUPTED_ID_PREFIX${System.currentTimeMillis()}",
                kind = LogKind.ERROR,
                text = text,
                label = "任务中断",
            )
        )
        AppLog.w(text, "任务")
        runCatching { markerFile.delete() }
    }

    private fun LogEntry.isSessionNotice(): Boolean =
        id.startsWith(CRASH_NOTICE_ID_PREFIX) || id.startsWith(TASK_INTERRUPTED_ID_PREFIX)

    private companion object {
        const val MARKER_NAME = "task_in_flight.txt"
    }
}
