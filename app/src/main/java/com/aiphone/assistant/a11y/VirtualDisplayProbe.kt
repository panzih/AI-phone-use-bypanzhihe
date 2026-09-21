package com.aiphone.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aiphone.assistant.shell.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 0.7 副屏无障碍探针（**仅调试、不接入生产路径，验完即删**）。
 *
 * 回答四问：
 *   Q1 无障碍能否看到副屏窗口 + 根节点；
 *   Q2 节点 ACTION_CLICK 能否驱动副屏；
 *   Q3 takeScreenshot(displayId) 能否截副屏；
 *   Q4 dispatchGesture 能否打到副屏。
 *
 * 只用 AccessibilityService 的 public API，不改 AutoService / DisplayChannel /
 * ChannelController / 配置文件。报告同时进结果框和 logcat（tag=VDProbe）。
 */
object VirtualDisplayProbe {

    private const val TAG = "VDProbe"

    /** 帧差异率超过这个值认为"画面变了" */
    private const val CHANGE_THRESHOLD = 0.02

    private open class Q(val name: String, val ok: Boolean?, val body: String)

    /** 串联 Q1→Q4，返回完整报告 */
    suspend fun run(context: Context, displayId: Int): String {
        val sb = StringBuilder()
        sb.appendLine("===== 0.7 副屏无障碍探针  displayId=$displayId  SDK=${Build.VERSION.SDK_INT} =====")

        val q1 = q1Windows(displayId)
        appendQ(sb, q1)

        // Q2 依赖 Q1 能拿到副屏 root
        if (q1.ok == true) {
            appendQ(sb, q2NodeClick(context, displayId))
        } else {
            appendQ(sb, Q("Q2", null, "跳过：Q1 未拿到副屏窗口/根节点，无法测节点点击。"))
        }

        appendQ(sb, q3A11yScreenshot(context, displayId))
        appendQ(sb, q4GestureToDisplay(context, displayId, q1.dispW, q1.dispH))

        sb.appendLine()
        sb.appendLine("----- 四问汇总 -----")
        sb.appendLine(summaryLine(q1))
        sb.appendLine("Q2 见上")
        sb.appendLine("Q3 见上")
        sb.appendLine("Q4 见上")
        sb.appendLine("（PNG 已存到应用 cache：q2_*/q3_*/q4_*）")
        return sb.toString()
    }

    // ---------------------------------------------------------------- Q1

    private class Q1Result(name: String, ok: Boolean?, body: String,
                           val dispW: Int, val dispH: Int) : Q(name, ok, body)

    private fun q1Windows(displayId: Int): Q1Result {
        val sb = StringBuilder()
        var dispW = 0
        var dispH = 0

        val service = AutoService.get()
        if (service == null) {
            return Q1Result("Q1", false, "无障碍服务未连接（AutoService.get()==null）。", 0, 0)
        }

        val windows: List<AccessibilityWindowInfo> = service.windows ?: emptyList()
        sb.appendLine("service.windows.size = ${windows.size}")

        var target: AccessibilityWindowInfo? = null
        windows.forEachIndexed { i, w ->
            val wDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) w.displayId else -1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wDisplay == displayId) {
                target = w
            }
            val root = runCatching { w.root }.getOrNull()
            sb.appendLine(
                "  [$i] id=${w.id} type=${typeName(w.type)} active=${w.isActive} " +
                    "focused=${w.isFocused} displayId=$wDisplay title=${w.title} " +
                    "root=${root != null} pkg=${root?.packageName} child=${root?.childCount}"
            )
        }

        val t = target
        if (t == null) {
            sb.appendLine("结论：没有任何窗口 displayId==$displayId —— 系统不把副屏窗口交给无障碍。")
            sb.appendLine("Q1=NO（一票否决节点路线）")
            return Q1Result("Q1", false, sb.toString(), 0, 0)
        }

        val root = t.root
        if (root == null) {
            sb.appendLine("结论：找到副屏窗口但 getRoot()==null。")
            sb.appendLine("Q1=NO")
            return Q1Result("Q1", false, sb.toString(), 0, 0)
        }

        val rect = Rect()
        root.getBoundsInScreen(rect)
        dispW = rect.width()
        dispH = rect.height()

        val total = dfsCount(root)
        val sample = findNode(root) { n ->
            val tx = n.text?.toString().orEmpty()
            val dc = n.contentDescription?.toString().orEmpty()
            (tx.isNotBlank() || dc.isNotBlank() || n.isClickable)
        }
        sb.appendLine("目标窗口 root：pkg=${root.packageName} 总节点数=$total 尺寸=${dispW}x${dispH}")
        if (sample != null) {
            val r = Rect(); sample.getBoundsInScreen(r)
            sb.appendLine(
                "样本节点：class=${sample.className} text=${sample.text} " +
                    "desc=${sample.contentDescription} viewId=${sample.viewIdResourceName} " +
                    "clickable=${sample.isClickable} bounds=$r"
            )
        }

        val ok = total >= 1 && sample != null
        sb.appendLine(if (ok) "Q1=OK" else "Q1=NO（副屏有窗口但 DFS 不出带文字/可点节点）")
        return Q1Result("Q1", ok, sb.toString(), dispW, dispH)
    }

    // ---------------------------------------------------------------- Q2

    private suspend fun q2NodeClick(context: Context, displayId: Int): Q = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val service = AutoService.get()
            ?: return@withContext Q("Q2", false, "无障碍服务未连接。")

        val target = findWindow(service, displayId)
            ?: return@withContext Q("Q2", false, "找不到 displayId==$displayId 的窗口。")
        val root = target.root
            ?: return@withContext Q("Q2", false, "副屏窗口 root==null。")

        // 挑一个带文字、宽列表项、避开顶部搜索/标题栏的可点节点
        val node = findClickableListItem(root)
        if (node == null) {
            return@withContext Q("Q2", false, "副屏 root 里找不到带文字的可点列表项。")
        }
        val nr = Rect(); node.getBoundsInScreen(nr)
        sb.appendLine(
            "目标节点：class=${node.className} text=${node.text} " +
                "viewId=${node.viewIdResourceName} bounds=$nr"
        )

        val before = ShizukuBridge.grabFrame(context)
        if (before == null) {
            return@withContext Q("Q2", false, "grabFrame(before) 失败。")
        }
        sb.appendLine("grabFrame before：${before.size} 字节")

        val ret = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        sb.appendLine("performAction(ACTION_CLICK) 返回 = $ret")
        kotlinx.coroutines.delay(1500)

        val after = ShizukuBridge.grabFrame(context)
        if (after == null) {
            return@withContext Q("Q2", false, "grabFrame(after) 失败。")
        }
        sb.appendLine("grabFrame after：${after.size} 字节")

        val ratio = frameDiffRatio(before, after)
        sb.appendLine("点击前后帧差异率：${if (ratio < 0) "计算失败" else "%.2f%%".format(ratio * 100)}")

        // 重读树看是否换页
        val newRoot = findWindow(service, displayId)?.root
        val newTitle = newRoot?.let { firstText(it) }
        sb.appendLine("点击后新页面首个文本：$newTitle")

        saveCache(context, "q2_before.png", before)
        saveCache(context, "q2_after.png", after)

        val changed = ratio >= 0 && ratio > CHANGE_THRESHOLD
        val ok = ret && changed
        sb.appendLine(if (ok) "Q2=OK（节点点击驱动了副屏）" else "Q2=NO（返回=$ret，帧变化=$changed）")
        Q("Q2", ok, sb.toString())
    }

    // ---------------------------------------------------------------- Q3

    private suspend fun q3A11yScreenshot(context: Context, displayId: Int): Q =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@withContext Q("Q3", null, "takeScreenshot(displayId) 需 SDK>=30，当前 SDK=${Build.VERSION.SDK_INT}。")
            }
            val service = AutoService.get()
                ?: return@withContext Q("Q3", false, "无障碍服务未连接。")

            val shot = takeScreenshotOnDisplay(service, displayId)
            val grab = ShizukuBridge.grabFrame(context)

            if (shot == null) {
                sb.appendLine("takeScreenshot($displayId) 失败，errorCode=${takeScreenshotError}")
                sb.appendLine("Q3=NO")
                return@withContext Q("Q3", false, sb.toString())
            }

            val png = shot.toPng()
            sb.appendLine("takeScreenshot 成功：${shot.width}x${shot.height}，PNG=${png.size} 字节")

            if (grab != null) {
                val grabBmp = BitmapFactory.decodeByteArray(grab, 0, grab.size)
                sb.appendLine("grabFrame 对照：${grabBmp?.width}x${grabBmp?.height}，${grab.size} 字节")
                // 尺寸一致即强烈提示截到的是副屏
                val sameSize = grabBmp != null &&
                    grabBmp.width == shot.width && grabBmp.height == shot.height
                sb.appendLine("尺寸是否与副屏 grabFrame 一致：$sameSize")
                saveCache(context, "q3_grab.png", grab)
            }
            saveCache(context, "q3_a11y.png", png)

            val ok = png.isNotEmpty()
            sb.appendLine(if (ok) "Q3=OK（无障碍可截副屏，肉眼比对 q3_a11y/q3_grab）" else "Q3=NO")
            Q("Q3", ok, sb.toString())
        }

    /** 最近一次 takeScreenshot 的错误码 */
    @Volatile
    private var takeScreenshotError: Int = -1

    private suspend fun takeScreenshotOnDisplay(
        service: AccessibilityService,
        displayId: Int,
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshotError = -1
        service.takeScreenshot(
            displayId,
            { it.run() },
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    var hw: Bitmap? = null
                    runCatching {
                        hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    }
                    result.hardwareBuffer.close()
                    val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    if (cont.isActive) cont.resume(sw)
                }

                override fun onFailure(errorCode: Int) {
                    takeScreenshotError = errorCode
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    // ---------------------------------------------------------------- Q4

    private suspend fun q4GestureToDisplay(
        context: Context,
        displayId: Int,
        dispW: Int,
        dispH: Int,
    ): Q = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val service = AutoService.get()
            ?: return@withContext Q("Q4", false, "无障碍服务未连接。")

        // 点击坐标：副屏中心（Settings 首页中心通常是安全的列表项）
        val tx = if (dispW > 0) dispW / 2 else 540
        val ty = if (dispH > 0) dispH / 2 else 1200
        sb.appendLine("安全提醒：跑 Q4 前主屏应停在纸盒/桌面等无害页面；本次单击坐标=($tx,$ty)")

        val mainBefore = mainScreenPng()
        val dispBefore = ShizukuBridge.grabFrame(context)
        sb.appendLine("主屏 before：${mainBefore?.size ?: "失败"} 字节；副屏 before：${dispBefore?.size ?: "失败"} 字节")

        val dispatched = dispatchTap(service, tx, ty)
        sb.appendLine("dispatchGesture 完成=$dispatched")
        kotlinx.coroutines.delay(1500)

        val mainAfter = mainScreenPng()
        val dispAfter = ShizukuBridge.grabFrame(context)

        val mainRatio = if (mainBefore != null && mainAfter != null)
            frameDiffRatio(mainBefore, mainAfter) else -1.0
        val dispRatio = if (dispBefore != null && dispAfter != null)
            frameDiffRatio(dispBefore, dispAfter) else -1.0

        sb.appendLine("主屏差异率：${if (mainRatio < 0) "失败" else "%.2f%%".format(mainRatio * 100)}")
        sb.appendLine("副屏差异率：${if (dispRatio < 0) "失败" else "%.2f%%".format(dispRatio * 100)}")

        mainBefore?.let { saveCache(context, "q4_main_before.png", it) }
        mainAfter?.let { saveCache(context, "q4_main_after.png", it) }
        dispBefore?.let { saveCache(context, "q4_disp_before.png", it) }
        dispAfter?.let { saveCache(context, "q4_disp_after.png", it) }

        val mainChanged = mainRatio in 0.0..1.0 && mainRatio > CHANGE_THRESHOLD
        val dispChanged = dispRatio in 0.0..1.0 && dispRatio > CHANGE_THRESHOLD

        val conclusion: String
        val ok: Boolean?
        when {
            dispChanged && !mainChanged -> { conclusion = "✅ 手势打到副屏"; ok = true }
            !dispChanged && mainChanged -> { conclusion = "❌ 手势打到主屏（必须用 input -d）"; ok = false }
            !dispChanged && !mainChanged -> { conclusion = "❌ 手势被丢弃（两侧都没反应）"; ok = false }
            else -> { conclusion = "⚠️ 两侧都动，需重跑排除干扰"; ok = null }
        }
        sb.appendLine("Q4：$conclusion")
        Q("Q4", ok, sb.toString())
    }

    private suspend fun dispatchTap(service: AccessibilityService, x: Int, y: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 1)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }

    private suspend fun mainScreenPng(): ByteArray? {
        val r = AutoService.get()?.takeShot()
        return (r as? AutoService.ShotResult.Ok)?.bitmap?.toPng()
    }

    // ---------------------------------------------------------------- 工具

    private fun findWindow(
        service: AccessibilityService,
        displayId: Int,
    ): AccessibilityWindowInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return service.windows?.firstOrNull { it.displayId == displayId }
    }

    private fun dfsCount(root: AccessibilityNodeInfo?): Int {
        if (root == null) return 0
        var count = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            count++
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return count
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (pred(n)) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    /** Settings 首页那种宽列表项：可点、带文字、横向占大半、避开顶部 250px */
    private fun findClickableListItem(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rect = Rect()
        return findNode(root) { n ->
            if (!n.isClickable) return@findNode false
            val tx = n.text?.toString().orEmpty().trim()
            if (tx.isBlank()) return@findNode false
            n.getBoundsInScreen(rect)
            rect.width() > 500 && rect.top > 250
        }
    }

    private fun firstText(root: AccessibilityNodeInfo): String? {
        val n = findNode(root) { it.text?.toString()?.isNotBlank() == true }
        return n?.text?.toString()
    }

    private fun typeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIV"
        else -> "?"
    }

    private fun Bitmap.toPng(): ByteArray {
        val o = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, o)
        return o.toByteArray()
    }

    private fun saveCache(context: Context, name: String, bytes: ByteArray) {
        runCatching { context.cacheDir.resolve(name).writeBytes(bytes) }
    }

    /**
     * 两帧 PNG 的像素差异率（每隔几个像素采样，省 CPU）。
     * @return 0~1；解码失败返回 -1
     */
    private fun frameDiffRatio(a: ByteArray, b: ByteArray): Double {
        val b1 = BitmapFactory.decodeByteArray(a, 0, a.size) ?: return -1.0
        val b2 = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return -1.0
        val w = minOf(b1.width, b2.width)
        val h = minOf(b1.height, b2.height)
        var diff = 0L
        var total = 0L
        val step = 6
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                total++
                if (b1.getPixel(x, y) != b2.getPixel(x, y)) diff++
                x += step
            }
            y += step
        }
        return if (total == 0L) -1.0 else diff.toDouble() / total
    }

    private fun appendQ(sb: StringBuilder, q: Q) {
        sb.appendLine()
        sb.appendLine("----- ${q.name} -----")
        sb.append(q.body)
        if (!q.body.endsWith("\n")) sb.appendLine()
        Log.i(TAG, "${q.name}\n${q.body}")
    }

    private fun summaryLine(q: Q): String =
        "${q.name}=${when (q.ok) { true -> "OK"; false -> "NO"; null -> "N/A" }}"
}
