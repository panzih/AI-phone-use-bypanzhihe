package com.aiphone.assistant.shell

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 跑在 shell 进程里的服务实现。
 *
 * ## 这个类不在应用的进程里
 *
 * Shizuku 用 `app_process` 起一个 **uid=2000（shell）** 的进程，把这份代码
 * 加载进去。所以：
 *
 *   - 这里**碰不到应用的对象**（没有 Context、没有单例、没有 UI）
 *   - 抛出的异常会跨进程传回去，最好都自己吞掉转成文本
 *   - 日志分两条：logcat 里看到的 tag 是这个进程打的
 *
 * 换句话说，这个文件里能用的只有 JDK 和 Android framework 的基础能力。
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        Log.i(TAG, "ShellService 被销毁")
    }

    override fun exec(command: String): String = try {
        val p = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        // 等它结束再返回，否则调用方拿到的是"命令还没跑完"的状态
        p.waitFor()
        out
    } catch (t: Throwable) {
        Log.w(TAG, "exec 失败：$command → ${t.message}")
        "命令执行失败：${t.javaClass.simpleName} ${t.message}"
    }

    override fun execBytes(command: String): ByteArray = try {
        val p = ProcessBuilder("sh", "-c", command).start()
        val out = ByteArrayOutputStream(1 shl 16)
        // 必须单独读：截图几百 KB，如果和 stderr 共用一条流会被污染
        p.inputStream.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        p.errorStream?.close()
        p.waitFor()
        out.toByteArray()
    } catch (t: Throwable) {
        Log.w(TAG, "execBytes 失败：$command → ${t.message}")
        ByteArray(0)
    }

    private companion object {
        const val TAG = "ShellService"
    }
}
