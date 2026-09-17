package com.aiphone.assistant.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku 桥：拿到 shell 身份，然后跑命令。
 *
 * ## 它在整个方案里的位置
 *
 * ```
 *   应用（普通 uid）
 *     └─ Shizuku.bindUserService
 *          └─ 一个 uid=2000(shell) 的进程，跑着 ShellService
 *               └─ sh -c "screencap / input / settings / am ..."
 * ```
 *
 * 也就是说：**命令字符串和你在电脑上敲的 adb shell 后面那截一模一样**。
 * 这一点很重要 —— 之前写的所有 adb 经验都能直接用。
 *
 * ## 三个必须处理好的现实
 *
 * **1. 绑定是异步的。** `bindUserService` 只负责发起，真正拿到 binder 要等
 * `onServiceConnected`。所以对外暴露的是挂起的 [ensureBound]，内部用
 * 超时兜底 —— 否则"没装 Shizuku""用户没授权""服务起不来"这三种情况
 * 会表现成"一直卡着"，最难查。
 *
 * **2. 权限和启动是两件事。** Shizuku 装没装、跑没跑（pingBinder）、
 * 授没授权（checkSelfPermission），三个状态要分开报，因为处理方式完全不同。
 *
 * **3. 服务会掉。** 用户关掉 Shizuku、系统回收，binder 就没了。
 * 每次执行前检查一下，掉了就重新绑 —— 而不是让调用方自己去猜。
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private const val REQUEST_CODE = 1001
    private const val BIND_TIMEOUT_MS = 8000L

    /** 当前状态，界面用它决定显示什么 */
    enum class State(val label: String) {
        NOT_INSTALLED("没装 Shizuku"),
        NOT_RUNNING("Shizuku 没在运行"),
        NO_PERMISSION("还没授权给纸盒"),
        READY("已就绪"),
    }

    private var service: IShellService? = null
    private var binding = false
    private var boundVersion = 0

    @Volatile
    var lastError: String? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IShellService.Stub.asInterface(binder)
            binding = false
            lastError = null
            Log.i(TAG, "shell 服务已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            Log.w(TAG, "shell 服务断开")
        }
    }

    /** 现在是什么状态 */
    fun state(context: Context): State = when {
        !isInstalled(context) -> State.NOT_INSTALLED
        !ping() -> State.NOT_RUNNING
        !hasPermission() -> State.NO_PERMISSION
        else -> State.READY
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun ping(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        ping() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 弹授权框。用户点允许之后要重新查 [hasPermission] */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { lastError = "申请权限失败：${it.message}" }
    }

    /**
     * 确保 shell 服务可用。
     *
     * @return null 表示可以用；否则是给用户看的中文原因
     */
    suspend fun ensureBound(context: Context): String? {
        service?.let { if (it.asBinder().isBinderAlive) return null }

        val st = state(context)
        if (st != State.READY) {
            return when (st) {
                State.NOT_INSTALLED -> "还没装 Shizuku。装好并启动它之后再用这个功能。"
                State.NOT_RUNNING -> "Shizuku 没在运行。打开 Shizuku 启动它（无 root 时要用无线调试启动）。"
                State.NO_PERMISSION -> "还没授权给纸盒。点「申请授权」并在弹框里允许。"
                State.READY -> null
            }
        }

        if (!binding) {
            binding = true
            runCatching { Shizuku.bindUserService(userServiceArgs(context), connection) }
                .onFailure {
                    binding = false
                    lastError = "绑定 shell 服务失败：${it.message}"
                    Log.w(TAG, lastError!!)
                }
        }

        // 等 binder 上来。超时返回原因而不是无限等 —— 卡住最难查
        val deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            service?.let { if (it.asBinder().isBinderAlive) return null }
            delay(100)
        }
        return lastError ?: "等 shell 服务连接超时（${BIND_TIMEOUT_MS / 1000} 秒）。" +
            "Shizuku 是否还在运行？"
    }

    /** 跑一条命令，拿文本结果 */
    suspend fun run(context: Context, command: String): String = withContext(Dispatchers.IO) {
        val problem = ensureBound(context)
        if (problem != null) return@withContext "错误：$problem"
        runCatching { service?.exec(command).orEmpty() }
            .getOrElse { "命令执行异常：${it.javaClass.simpleName} ${it.message}" }
    }

    /** 跑一条命令，拿二进制结果（截图） */
    suspend fun runBytes(context: Context, command: String): ByteArray = withContext(Dispatchers.IO) {
        val problem = ensureBound(context)
        if (problem != null) {
            lastError = problem
            return@withContext ByteArray(0)
        }
        runCatching { service?.execBytes(command) ?: ByteArray(0) }
            .getOrElse {
                lastError = "命令执行异常：${it.message}"
                ByteArray(0)
            }
    }

    /**
     * UserService 的启动参数。
     *
     * `version` 变了 Shizuku 会重新拉起服务 —— 改了 ShellService 的实现
     * 要记得把它加一，否则可能还跑着旧代码。
     */
    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            // release 包里把它设成 true 会导致服务拉不起来，所以写死 false
            .debuggable(false)
            .version(SHELL_SERVICE_VERSION)
            .tag("纸盒 shell")

    fun release() {
        runCatching { service?.destroy() }
        service = null
        binding = false
    }

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** 改了 ShellService 的实现就 +1 */
    private const val SHELL_SERVICE_VERSION = 1
}
