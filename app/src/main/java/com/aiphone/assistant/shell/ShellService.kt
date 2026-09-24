package com.aiphone.assistant.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Looper
import android.os.Handler
import android.os.Process
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 跑在 shell 进程里的服务实现。
 *
 * ## 这个类不在应用的进程里
 *
 * Shizuku 用 `app_process` 起一个 **uid=2000（shell）** 的进程，把这份代码
 * 加载进去。所以：
 *
 *   - 这里**碰不到应用的对象**（没有现成 Context、没有单例、没有 UI），
 *     需要 Context 时得自己反射 `ActivityThread.systemMain()`
 *   - 抛出的异常会跨进程传回去，最好都自己吞掉转成文本/错误码
 *
 * 换句话说，这个文件里能用的只有 JDK 和 Android framework 的基础能力。
 *
 * ## 0.5.0 起新增：TRUSTED 虚拟副屏
 *
 * `createDisplay / grabFrame / startOnDisplay` 三个方法把"建一块可交互的
 * 副屏、在上面起应用、抓帧"全部放进这个长驻服务进程，是后台模式的地基。
 */
class ShellService : IShellService.Stub() {

    private var appContext: Context? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** 上一次成功抓到的帧；静态画面没新帧时 grabFrame 也能稳定返回 */
    private var lastFrameBytes: ByteArray? = null

    /** 孤儿 :shell 清理是否已做过（每个服务进程只在首次建屏前做一次） */
    private var orphanCleaned = false

    override fun destroy() {
        Log.i(TAG, "ShellService 被销毁")
        synchronized(this) { releaseDisplayLocked() }
    }

    /** 释放副屏相关资源（VirtualDisplay + ImageReader + 缓存帧）；destroy/destroyDisplay 共用 */
    private fun releaseDisplayLocked(): Int {
        val id = virtualDisplay?.display?.displayId ?: -1
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        lastFrameBytes = null
        return id
    }

    override fun destroyDisplay(): Int = synchronized(this) {
        try {
            if (virtualDisplay == null) {
                Log.i(TAG, "destroyDisplay：没有副屏可销毁")
                return@synchronized -1
            }
            val id = releaseDisplayLocked()
            Log.i(TAG, "副屏已主动销毁 displayId=$id")
            id
        } catch (t: Throwable) {
            Log.w(TAG, "destroyDisplay 失败：$t")
            -2
        }
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

    // ================= 0.5.0：TRUSTED 虚拟副屏 =================

    override fun createDisplay(): Int = synchronized(this) {
        try {
            // 幂等：已经建了就直接返回现有 id
            virtualDisplay?.let { vd ->
                if (vd.display.displayId >= 0) return@synchronized vd.display.displayId
            }

            // 0.5.1：建自己的屏之前，先清掉上一轮 server 死亡遗留的孤儿 :shell（只一次）
            cleanupOrphansOnce()

            val ctx = ensureContext()
            val manager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            // 铁律：副屏宽/高/density 照抄主屏，任何偏差都会在跨屏时触发
            // configuration change、Activity 被重建，move-stack 就失去意义
            val main = manager.getDisplay(Display.DEFAULT_DISPLAY)
            val w = main.mode.physicalWidth
            val h = main.mode.physicalHeight
            val dpi = ctx.resources.displayMetrics.densityDpi
            Log.i(TAG, "主屏参数 w=$w h=$h dpi=$dpi")

            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
            imageReader = reader

            val vd = manager.createVirtualDisplay(
                DISPLAY_NAME, w, h, dpi, reader.surface, VIRTUAL_DISPLAY_FLAGS,
            )
            virtualDisplay = vd
            Log.i(TAG, "副屏已建 displayId=${vd.display.displayId} flags=0x${VIRTUAL_DISPLAY_FLAGS.toString(16)}")
            vd.display.displayId
        } catch (t: Throwable) {
            // 反射调用包了一层 InvocationTargetException，要顺着 cause 挖到底才是真错误
            val root = generateSequence(t) { it.cause }.lastOrNull { it !is java.lang.reflect.InvocationTargetException } ?: t
            Log.w(TAG, "createDisplay 失败：${root.javaClass.simpleName} ${root.message}", root)
            -1
        }
    }

    override fun grabFrame(): ByteArray = synchronized(this) {
        try {
            val reader = imageReader
                ?: return@synchronized lastFrameBytes ?: ByteArray(0)
            val img: Image? = reader.acquireLatestImage()
            if (img != null) {
                val png = imageToPng(img)
                img.close()
                if (png.isNotEmpty()) {
                    lastFrameBytes = png
                    return@synchronized png
                }
            }
            // 没有新帧（静态画面）就回退上一帧
            lastFrameBytes ?: ByteArray(0)
        } catch (t: Throwable) {
            Log.w(TAG, "grabFrame 失败：$t")
            lastFrameBytes ?: ByteArray(0)
        }
    }

    override fun startOnDisplay(component: String, displayId: Int): String = synchronized(this) {
        try {
            exec("am start --display $displayId -n $component")
        } catch (t: Throwable) {
            "启动失败：${t.javaClass.simpleName} ${t.message}"
        }
    }

    // ================= 0.5.1：生命周期 =================

    /**
     * 只跑一次：杀掉同 uid 下、除自己以外残留的 assistant:shell 进程。
     *
     * Shizuku server 被强杀/重启时，它 fork 的 :shell 会变孤儿（父进程=1）、
     * 仍占着虚拟屏。新服务建屏前把它们 kill，系统的死亡接收器会自动释放其 VirtualDisplay。
     * 用 [Process.myPid] 排除自己，绝不会自断。
     *
     * 这是"尽力而为"的清理：kill 之后不重新枚举确认，失败也只 Log.w。
     * 孤儿进程占的 VirtualDisplay 会随进程死亡被系统回收，不需要手动确认。
     */
    private fun cleanupOrphansOnce() {
        if (orphanCleaned) return
        orphanCleaned = true
        try {
            val myPid = Process.myPid()
            // ps -A 列：USER PID PPID ...，PID 是第 2 列
            val orphans = runUnlocked("ps -A").lineSequence()
                .filter { it.contains("com.aiphone.assistant:shell") }
                .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }
                .filter { it != myPid }
                .toList()
            if (orphans.isEmpty()) {
                Log.i(TAG, "孤儿清理：无残留 :shell")
            } else {
                for (pid in orphans) {
                    Log.i(TAG, "清理上一轮遗留的孤儿 :shell pid=$pid（其副屏随之释放）")
                    runCatching { Process.killProcess(pid) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "孤儿清理失败：$t")
        }
    }

    /** 直接 ProcessBuilder 跑命令（持锁内用，避免重入 synchronized 的 exec） */
    private fun runUnlocked(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out
    }

    /**
     * 反射拿一个**包名匹配本进程 uid** 的 Context。
     *
     * - shell(uid2000)：system context 包名是 "android"（uid1000），直接用会在
     *   createVirtualDisplay 报 "packageName must match the calling uid"，
     *   要换成 createPackageContext("com.android.shell")
     * - root(uid0)：system context 可直接用
     */
    private fun ensureContext(): Context {
        appContext?.let { return it }

        // Shizuku 的 binder 方法跑在 binder 线程，而 ActivityThread.systemMain()
        // 内部 new Handler 必须用主线程 Looper。Shizuku 启动时主线程 Looper 已就绪，
        // 这里把初始化 post 回主线程、阻塞等结果。
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null && Looper.myLooper() != mainLooper) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: Context? = null
            var err: Throwable? = null
            Handler(mainLooper).post {
                try {
                    result = initSystemContext()
                } catch (t: Throwable) {
                    err = t
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
            err?.let { throw it }
            return (result as Context).also { appContext = it }
        }

        // 已在主线程（裸 app_process 等情况）
        return initSystemContext().also { appContext = it }
    }

    /** 必须在主线程调用：反射拿包名匹配本进程 uid 的 system Context */
    private fun initSystemContext(): Context {
        // 裸 app_process 主线程 Looper 可能还没准备；Shizuku 进程则已准备
        if (Looper.myLooper() == null) Looper.prepare()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        var ctx = at.getMethod("getSystemContext").invoke(thread) as Context
        if (Process.myUid() == Process.SHELL_UID) {
            ctx = ctx.createPackageContext("com.android.shell", 0)
        }
        return ctx
    }

    /** Image（RGBA_8888，可能带 rowStride padding）转成 PNG 字节 */
    private fun imageToPng(img: Image): ByteArray {
        val plane = img.planes[0]
        val buf: ByteBuffer = plane.buffer
        val paddedW = plane.rowStride / plane.pixelStride
        val w = img.width
        val h = img.height
        val bmp = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val fixed = if (paddedW != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
        val out = ByteArrayOutputStream(w * h / 8)
        fixed.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "ShellService"
        const val DISPLAY_NAME = "PaperBoxVD"
        const val MAX_IMAGES = 3

        // 与一手实测程序 RootVD 完全一致、在 root/shell/user 三种条件下都通过。
        // 各移位位含义：6=SUPPORTS_TOUCH，8=DESTROY_CONTENT_ON_REMOVAL，
        // 10=TRUSTED（核心），11=OWN_DISPLAY_GROUP，12=ALWAYS_UNLOCKED，
        // 13=TOUCH_FEEDBACK_DISABLED，14=OWN_FOCUS，15=DEVICE_DISPLAY_GROUP，
        // 16=STEAL_TOP_FOCUS_DISABLED
        const val VIRTUAL_DISPLAY_FLAGS: Int =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                (1 shl 6) or
                (1 shl 8) or
                (1 shl 10) or
                (1 shl 11) or
                (1 shl 12) or
                (1 shl 13) or
                (1 shl 14) or
                (1 shl 15) or
                (1 shl 16)
    }
}
