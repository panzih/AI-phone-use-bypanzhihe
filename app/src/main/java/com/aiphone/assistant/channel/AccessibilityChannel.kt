package com.aiphone.assistant.channel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.a11y.UiTreeParser
import com.aiphone.assistant.touch.ScrollDirection
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AccessibilityChannel(private val context: Context) : DeviceChannel {

    override val displayName: String = "无障碍"
    override val capability: ChannelCapability = ChannelCapability.ACCESSIBILITY

    @Volatile private var cachedSize: Pair<Int, Int>? = null
    @Volatile private var lastNodes: List<UiNode> = emptyList()
    private val service: AutoService? get() = AutoService.get()

    override suspend fun probe(): String? = when {
        service == null -> "无障碍服务未开启。请到「设置 → 无障碍 → 已安装的服务」里开启本应用。"
        else -> null
    }

    override suspend fun screenSize(): Pair<Int, Int>? {
        cachedSize?.let { return it }
        return withContext(Dispatchers.IO) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return@withContext null
            val size = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val m = wm.currentWindowMetrics; val b = m.bounds; b.width() to b.height()
            } else {
                @Suppress("DEPRECATION")
                val p = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }; p.x to p.y
            }
            size.takeIf { it.first > 0 && it.second > 0 }?.also { cachedSize = it }
        }
    }

    override suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null
        repeat(3) { attempt ->
            when (val r = svc.takeShot()) {
                is AutoService.ShotResult.Ok -> {
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        r.bitmap.recycle(); out.toByteArray()
                    }
                    return@withContext bytes
                }
                is AutoService.ShotResult.Fail -> {
                    android.util.Log.w("AccessibilityChannel", "截图失败(尝试 ${attempt + 1}/3): ${r.reason}")
                    if (r.reason.contains("太快") || r.reason.contains("太短")) Thread.sleep(400L * (attempt + 1))
                    else return@withContext null
                }
            }
        }
        null
    }

    override suspend fun dumpUiTree(): String? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null
        val (w, h) = screenSize() ?: (1080 to 1920)
        val nodes = svc.parseTree(w, h, limit = 60)
        lastNodes = nodes
        UiTreeParser.render(nodes)
    }

    fun lastParsedNodes(): List<UiNode> = lastNodes

    suspend fun tapByIndex(index: Int): String? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "无障碍服务未连接"
        val (w, h) = screenSize() ?: (1080 to 1920)
        val nodes = svc.parseTree(w, h, limit = 60)
        lastNodes = nodes
        val node = nodes.firstOrNull { it.index == index }
            ?: return@withContext "找不到编号 $index 的元素。可能界面已经变了，请重新观察。"
        val target = svc.findNodeByIndex(index, w, h)
            ?: return@withContext "编号 $index 的元素已经失效，界面可能变了"
        if (svc.clickNode(target)) return@withContext null
        var ok = false
        svc.tapAt(node.centerX.toFloat(), node.centerY.toFloat()) { ok = it }
        Thread.sleep(120)
        return@withContext if (ok) null else "点击编号 $index 失败"
    }

    private suspend fun sceneIndex(index: Int): android.view.accessibility.AccessibilityNodeInfo? =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext null
            val (w, h) = screenSize() ?: (1080 to 1920)
            svc.findNodeByIndex(index, w, h)
        }

    override suspend fun perform(action: TouchAction): String? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "无障碍服务未连接"
        try {
            when (action.kind) {
                TouchKind.TAP -> {
                    if (action.targetIndex > 0) tapByIndex(action.targetIndex)
                    else {
                        val ok = doGesture { cb -> svc.tapAt(action.x.toFloat(), action.y.toFloat(), cb) }
                        if (ok) null else "点击失败"
                    }
                }
                TouchKind.LONG_PRESS -> {
                    val node = if (action.targetIndex > 0) sceneIndex(action.targetIndex) else null
                    if (node != null && svc.longClickNode(node)) null
                    else {
                        val ok = doGesture { cb ->
                            svc.longPressAt(action.x.toFloat(), action.y.toFloat(),
                                action.durationMs.coerceIn(300, 10_000).toLong(), cb)
                        }
                        if (ok) null else "长按失败"
                    }
                }
                TouchKind.DOUBLE_TAP -> {
                    val ok = doGesture { cb -> svc.doubleTapAt(action.x.toFloat(), action.y.toFloat(), cb) }
                    if (ok) null else "双击失败"
                }
                TouchKind.SCROLL -> {
                    val dir = action.direction ?: ScrollDirection.DOWN
                    val (w, h) = screenSize() ?: (1080 to 1920)
                    val method = svc.smartScroll(dir, w, h, action.distanceRatio)
                    if (method != null) null else "滚动失败"
                }
                TouchKind.DRAG -> {
                    val moveMs = action.durationMs.coerceIn(200, 10_000)
                    val ok = doGesture { cb ->
                        svc.dragAt(action.x.toFloat(), action.y.toFloat(), action.x2.toFloat(), action.y2.toFloat(), moveMs.toLong(), cb)
                    }
                    if (ok) null else "拖拽失败"
                }
                TouchKind.SWIPE -> {
                    val d = action.durationMs.coerceIn(200, 10_000)
                    val ok = doGesture { cb ->
                        svc.swipeAt(action.x.toFloat(), action.y.toFloat(), action.x2.toFloat(), action.y2.toFloat(), d.toLong(), cb)
                    }
                    if (ok) null else "滑动失败"
                }
                TouchKind.FLICK -> {
                    val d = action.durationMs.coerceIn(50, 200)
                    val ok = doGesture { cb ->
                        svc.flickAt(action.x.toFloat(), action.y.toFloat(), action.x2.toFloat(), action.y2.toFloat(), d.toLong(), cb)
                    }
                    if (ok) null else "甩动失败"
                }
                TouchKind.PINCH_OUT, TouchKind.PINCH_IN -> pinch(svc, action)
                TouchKind.MULTI_FINGER -> "多指手势需要具体的手指路径，当前接口还没支持自定义路径"
                TouchKind.INPUT_TEXT -> inputText(action.text)
                TouchKind.KEY_BACK -> if (svc.globalBack()) null else "返回失败"
                TouchKind.KEY_HOME -> if (svc.globalHome()) null else "主页失败"
                TouchKind.KEY_RECENTS -> if (svc.globalRecents()) null else "多任务失败"
                TouchKind.OPEN_APP -> openApp(action.packageName)
                TouchKind.WAIT -> { Thread.sleep(action.durationMs.coerceIn(200, 10_000).toLong()); null }
            }
        } catch (t: Throwable) { "执行失败：${t.javaClass.simpleName} ${t.message}" }
    }

    private suspend fun pinch(svc: AutoService, action: TouchAction): String? {
        val cx = action.x.toFloat(); val cy = action.y.toFloat()
        val (startGap, endGap) = if (action.kind == TouchKind.PINCH_OUT) 120f to 320f else 320f to 120f
        val duration = action.durationMs.coerceIn(200, 2_000).toLong()
        val ok = doGesture { cb ->
            svc.gesture(listOf(
                listOf(PointF(cx - startGap, cy), PointF(cx - endGap, cy)),
                listOf(PointF(cx + startGap, cy), PointF(cx + endGap, cy)),
            ), duration, cb)
        }
        return if (ok) null else "双指手势失败"
    }

    private suspend fun doGesture(block: ((Boolean) -> Unit) -> Unit): Boolean = withTimeoutOrNull(5_000) {
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        block { ok -> result = ok; latch.countDown() }
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        result
    } ?: false

    private suspend fun inputText(text: String): String? {
        if (text.isEmpty()) return null
        val svc = service ?: return "无障碍服务未连接"
        val focused = svc.findFocus()
        if (focused != null && svc.setText(focused, text)) return null
        val (w, h) = screenSize() ?: (1080 to 1920)
        val nodes = svc.parseTree(w, h, limit = 60)
        val editable = nodes.firstOrNull { it.editable }
        if (editable != null) {
            val node = svc.findNodeByIndex(editable.index, w, h)
            if (node != null) { svc.focusNode(node); Thread.sleep(120); if (svc.setText(node, text)) return null }
        }
        return "找不到可输入的输入框。请先点击要输入的框再重试。"
    }

    private suspend fun openApp(pkg: String): String? {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "找不到应用：$pkg（包名可能不对）"
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent); Thread.sleep(600); null
        } catch (t: Throwable) { "启动失败：${t.message}" }
    }

    override fun release() { cachedSize = null; lastNodes = emptyList() }
}
