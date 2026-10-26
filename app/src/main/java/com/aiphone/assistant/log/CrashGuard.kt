package com.aiphone.assistant.log

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获。
 *
 * ## 为什么必须有它
 *
 * 0.10.6 那次「免费模式点发送就闪退」，用户说「导出日志」之后，包里**什么都没有**：
 * 因为崩溃发生在任务真正开始之前，`AppLog.i/w/e` 那时是空操作（没有活动 logger），
 * 而 Android 的未捕获异常默认只写 logcat —— logcat 要 adb 才看得到，
 * 普通用户手里就是一个"莫名其妙闪退了"。
 *
 * 这个类补的就是这条缝：把**任何线程**的未捕获异常落成一份文件，
 * 进到 `logs/crash/` 里，跟着导出包一起走。
 *
 * ## 三条不能动的规矩
 *
 * 1. **处理完必须把异常交回原处理器**。吞掉异常的后果是进程带着半死状态继续跑
 *    （下次莫名其妙地卡住），比直接崩更难查。所以这里只做"记录"，不做"消化"。
 * 2. **写文件必须同步刷盘**（`fd.sync()`）。进程下一刻就没了，
 *    靠 `close()` 的缓冲刷新不一定来得及。
 * 3. **处理器内部任何异常都不许外抛**。它自己要是崩了，原来的崩溃信息就丢了，
 *    用户看到的是一个空白的崩溃记录。
 *
 * ## 为什么不去碰 RunLogger
 *
 * 崩溃可能就发生在 `RunLogger` 持锁的代码里（它所有写操作都在 `synchronized(lock)` 里）。
 * 处理器再去调它的方法就有死锁风险 —— 而崩溃现场死锁等于什么都留不下。
 * 所以这里只**读** `AppLog.current()?.runDir` 这个路径，然后自己直接写文件。
 */
object CrashGuard {

    /** 崩溃记录放在 `logs/crash/`，和 `runs/`、`diag/` 平级 */
    const val CRASH_SUBDIR = "crash"

    /** 最多留几份。一份几十 KB，20 份约 1MB 量级，够追溯一段时间 */
    const val KEEP_REPORTS = 20

    /**
     * "已经提示过用户"的标记文件。
     *
     * 为什么用文件而不是设置项：崩溃处理器写它的时候设置存储还不一定初始化完
     * （崩溃可能发生在 Application.onCreate 中途），而这只是本机的一次性状态，
     * 不需要跨设备同步。
     */
    private const val REPORTED_MARK = ".last_reported"

    @Volatile
    private var installed = false

    /** 正在处理中。防止处理器自身再抛异常时无限重入 */
    @Volatile
    private var handling = false

    @Volatile
    private var appContext: Context? = null

    /**
     * 装钩子。**在 `Application.onCreate` 里就要调**，不要等 Activity ——
     * 崩溃可能发生在 Activity 起来之前。
     */
    fun install(context: Context) {
        if (installed) return
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handling) {
                // 处理器自己在处理时又崩了：不再记录，直接交出去
                handOver(previous, thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            handling = true
            // 记不下来也不能影响原有行为 —— 这里的所有失败都只能进 logcat
            runCatching {
                appContext?.let { recordCrash(it, thread.name, thread.isDaemon, error) }
            }.onFailure { android.util.Log.w(TAG, "写崩溃记录失败：$it", it) }
            handOver(previous, thread, error)
        }
        installed = true
    }

    /** 把异常交回系统原来的处理器；没有就按系统的默认行为杀掉进程 */
    private fun handOver(
        previous: Thread.UncaughtExceptionHandler?,
        thread: Thread,
        error: Throwable,
    ) {
        if (previous != null) {
            runCatching { previous.uncaughtException(thread, error) }
                .onFailure {
                    android.util.Log.w(TAG, "原崩溃处理器抛异常：$it", it)
                    killSelf()
                }
        } else {
            killSelf()
        }
    }

    private fun killSelf() {
        runCatching { Process.killProcess(Process.myPid()) }
        runCatching { System.exit(10) }
    }

    // ------------------------------------------------------------------
    // 写报告
    // ------------------------------------------------------------------

    /**
     * 把一次崩溃落成文件。**返回写出来的文件**（失败返回 null）。
     *
     * 从钩子里拆出来是为了能测：钩子本身是全局状态，在测试里装一次就污染后续用例，
     * 而"真的在磁盘上生成一份可读的报告"才是这个功能的核心，必须能验。
     */
    internal fun recordCrash(
        context: Context,
        threadName: String,
        threadDaemon: Boolean,
        error: Throwable,
    ): File? = runCatching {
        val ctx = context.applicationContext
        val at = System.currentTimeMillis()
        val dir = crashDir(ctx)
        // 毫秒级时间戳 + 撞名兜底：写崩溃报告最不能出的错就是"把上一次覆盖掉"，
        // 那样"崩了 5 次"这个事实会凭空消失，只剩 1 份
        var file = File(dir, "crash_${FILE_STAMP.format(Date(at))}.log")
        var n = 2
        while (file.exists()) {
            file = File(dir, "crash_${FILE_STAMP.format(Date(at))}_$n.log")
            n++
        }

        // 当前任务：只读路径，不碰 logger 的方法（理由见类注释）
        val run = AppLog.current()
        val taskInfo = if (run == null) {
            "无（崩溃发生在任务之外：未开始、已结束，或根本没跑任务）"
        } else {
            "任务「${run.task}」· 目录 ${run.runDir.name}"
        }

        val body = buildReport(
            at = at,
            threadName = threadName,
            threadDaemon = threadDaemon,
            error = error,
            taskInfo = taskInfo,
            env = environment(),
            recent = AppLog.recentSnapshot(),
        )

        // 同步刷盘：进程马上要死，不能等缓冲区
        FileOutputStream(file).use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }

        // 顺手把这一条也写进 logcat，用 adb 连着的人第一时间能看到
        android.util.Log.e(TAG, "崩溃已记录：${file.name}")

        // 如果有正在跑的任务，往它的 run.log 追一行指向崩溃文件 ——
        // 用户翻那次运行的时候能顺着这行找到完整现场
        run?.let { r ->
            runCatching {
                File(r.runDir, RunLogger.LOG_NAME).appendText(
                    "[${CLOCK.format(Date(at))}] 进程崩溃，完整现场见 crash/${file.name}\n"
                )
            }
        }

        pruneOld(ctx)
        file
    }.getOrNull()

    /**
     * 拼报告文本。
     *
     * 抽成纯函数是为了能单测 —— 真正容易出错的是"字段缺了/顺序乱了/堆栈被截断"，
     * 而不是上面那段 IO。
     *
     * @param recent 崩溃前内存里的最近日志（[AppLog.recentSnapshot]）
     */
    fun buildReport(
        at: Long,
        threadName: String,
        threadDaemon: Boolean,
        error: Throwable,
        taskInfo: String,
        env: List<String>,
        recent: List<String>,
    ): String = buildString {
        appendLine("纸盒崩溃报告")
        appendLine("=".repeat(60))
        appendLine()
        appendLine("【概要】")
        appendLine("时间      ：${FULL_STAMP.format(Date(at))}")
        appendLine("线程      ：$threadName${if (threadDaemon) "（守护线程）" else ""}")
        appendLine("异常类型  ：${error.javaClass.name}")
        appendLine("异常消息  ：${error.message ?: "（无）"}")
        appendLine("当前任务  ：$taskInfo")
        appendLine()
        appendLine("【环境】")
        env.forEach { appendLine("  $it") }
        appendLine()
        appendLine("【完整堆栈】")
        // stackTraceToString 会带上 cause 链，不用自己递归
        appendLine(error.stackTraceToString())
        appendLine()
        appendLine("【崩溃前的最近日志】")
        if (recent.isEmpty()) {
            appendLine("  （空 —— 日志系统还没开始工作就崩了）")
        } else {
            recent.forEach { appendLine("  $it") }
        }
        appendLine()
        appendLine("=".repeat(60))
        appendLine("说明：这份文件由崩溃处理器当场写出，内容截止到进程死亡那一刻。")
        appendLine("      如果【当前任务】写着「无」，说明崩溃发生在任务之外 ——")
        appendLine("      这类崩溃以前一条日志都不会留下，现在至少这里有现场。")
    }

    /** 设备与版本信息。取不到的项写「?」，不让它拖垮整份报告 */
    private fun environment(): List<String> = listOf(
        "应用版本：" + runCatching { appContext?.let { versionName(it) } }.getOrNull().orEmpty()
            .ifBlank { "?" },
        "系统    ：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        "机型    ：${Build.MANUFACTURER} ${Build.MODEL}",
        "进程    ：pid ${Process.myPid()}",
        "内存    ：" + runCatching {
            val rt = Runtime.getRuntime()
            "已用 ${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024} MB / 上限 ${rt.maxMemory() / 1024 / 1024} MB"
        }.getOrDefault("?"),
        "可用存储：" + runCatching {
            val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            "${st.availableBytes / 1024 / 1024} MB / ${st.totalBytes / 1024 / 1024} MB"
        }.getOrDefault("?"),
    )

    @Suppress("DEPRECATION")
    private fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // ------------------------------------------------------------------
    // 目录与"有没有还没看过的崩溃"
    // ------------------------------------------------------------------

    fun crashDir(context: Context): File =
        File(AppLog.logRoot(context), CRASH_SUBDIR).apply { mkdirs() }

    /** 所有崩溃记录，最新的在前 */
    fun listAll(context: Context): List<File> =
        crashDir(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash_") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    private fun markerFile(context: Context): File = File(crashDir(context), REPORTED_MARK)

    /**
     * 还没提示过用户的崩溃有几份。
     *
     * 判据是「文件修改时间晚于标记时间」—— 不用维护"已读列表"，
     * 少一个要跟着改的状态。
     */
    fun unreportedCount(context: Context): Int = runCatching {
        val since = markerFile(context).takeIf { it.isFile }?.lastModified() ?: 0L
        listAll(context).count { it.lastModified() > since }
    }.getOrDefault(0)

    /** 把当前所有崩溃标记为"已提示过"（用户看过那条消息之后调） */
    fun markReported(context: Context) {
        runCatching { markerFile(context).writeText(System.currentTimeMillis().toString()) }
    }

    /** 只留最近 [KEEP_REPORTS] 份 */
    private fun pruneOld(context: Context) {
        runCatching {
            listAll(context).drop(KEEP_REPORTS).forEach { it.delete() }
        }
    }

    /** 「删除日志」时一起清掉，否则删完还剩一堆崩溃记录 */
    fun deleteAll(context: Context) {
        runCatching {
            crashDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    private const val TAG = "CrashGuard"

    /**
     * 文件名时间戳。**带毫秒**：一个进程只会崩一次，但用户可能在几秒内
     * 反复打开一个必崩的版本 —— 秒级时间戳会让这几份报告互相覆盖，
     * 只剩最后一份，把"连续崩了 5 次"这个事实抹掉。
     */
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val FULL_STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
}
