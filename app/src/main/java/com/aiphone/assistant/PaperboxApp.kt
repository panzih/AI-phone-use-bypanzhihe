package com.aiphone.assistant

import android.app.Application
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.log.CrashGuard

/**
 * 应用入口。
 *
 * ## 为什么要有这个类
 *
 * 以前日志系统靠 `MainActivity.onCreate` 里的 `AppLog.attach(this)` 初始化。
 * 这带来两个漏洞，都真出过事：
 *
 * 1. **崩溃在 Activity 之前/之外**（前台服务、定时任务、广播、走副屏时的另一个进程）
 *    就完全没有日志能力 —— 没有应用级 Context，写不了盘；
 * 2. **闪退时一行都不留**：没有任何地方装 `UncaughtExceptionHandler`，
 *    异常只进 logcat，而用户手里没有 adb。
 *
 * 这两条合起来就是 0.10.6 那个局面的成因：用户说"它闪退了，日志导出来看看"，
 * 导出来的包里什么都没有 —— 不是日志功能坏了，是**那次崩溃根本没走日志系统**。
 *
 * 所以把初始化提到 Application：任何组件起来之前，日志和崩溃捕获都已经就位。
 */
class PaperboxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 顺序不能反：CrashGuard 写报告时要用 AppLog 的目录和内存日志
        AppLog.attach(this)
        CrashGuard.install(this)
        AppLog.boot()
    }
}
