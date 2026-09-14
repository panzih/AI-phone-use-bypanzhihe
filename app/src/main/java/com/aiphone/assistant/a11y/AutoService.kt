package com.aiphone.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aiphone.assistant.touch.GestureSpec
import com.aiphone.assistant.touch.ScrollDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AutoService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoService"

        @Volatile
        private var instance: AutoService? = null

        val isConnected: Boolean get() = instance != null

        fun get(): AutoService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.w(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun currentPackage(): String? = runCatching {
        rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    sealed class ShotResult {
        data class Ok(val bitmap: Bitmap) : ShotResult()
        data class Fail(val reason: String) : ShotResult()
    }

    fun takeShot(timeoutMs: Long = 5_000): ShotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ShotResult.Fail("系统版本低于 Android 11，无障碍截图不可用")
        }

        val latch = CountDownLatch(1)
        val result = AtomicReference<ShotResult>(ShotResult.Fail("截图超时（回调没来）"))

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace,
                    )
                    screenshot.hardwareBuffer.close()

                    if (bmp == null) {
                        result.set(ShotResult.Fail("截图数据无法解码"))
                    } else {
                        result.set(ShotResult.Ok(bmp.copy(Bitmap.Config.ARGB_8888, false)))
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "截图失败 code=$errorCode: ${explainError(errorCode)}")
                    result.set(ShotResult.Fail(explainError(errorCode)))
                    latch.countDown()
                }
            }
        )

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun explainError(code: Int): String = when (code) {
        1 -> "截图失败：距上次截图太快（系统限流，稍等一下重试）"
        2 -> "截图失败：无法截取当前窗口"
        3 -> "截图失败：距上次截图时间太短"
        4 -> "截图失败：当前页面有安全保护（银行/支付类页面），系统不允许截图"
        else -> "截图失败：未知错误码 $code"
    }

    fun readTree(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        Log.w(TAG, "读取控件树失败：${t.message}")
        null
    }

    fun parseTree(screenWidth: Int, screenHeight: Int, limit: Int = 60): List<UiNode> =
        UiTreeParser.parse(readTree(), screenWidth, screenHeight, limit)

    fun findFocus(): AccessibilityNodeInfo? = try {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (t: Throwable) {
        null
    }

    fun findNodeByIndex(
        index: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): AccessibilityNodeInfo? {
        var i = 1
        var found: AccessibilityNodeInfo? = null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        readTree()?.let { stack.addLast(it) }

        val seen = HashSet<String>()
        val rect = Rect()

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val interactive = node.isClickable || node.isLongClickable ||
                node.isScrollable || node.isEditable

            if (node.isVisibleToUser &&
                (interactive || text.isNotEmpty() || desc.isNotEmpty())
            ) {
                node.getBoundsInScreen(rect)
                if (rect.width() * rect.height() >= 24 * 24 &&
                    rect.right > 0 && rect.bottom > 0 &&
                    rect.left < screenWidth && rect.top < screenHeight
                ) {
                    val key = "$text|$desc|${rect.left},${rect.top},${rect.right},${rect.bottom}"
                    if (seen.add(key)) {
                        if (i == index) {
                            found = node
                            break
                        }
                        i++
                    }
                }
            }
            for (c in 0 until node.childCount) {
                node.getChild(c)?.let { stack.addLast(it) }
            }
        }
        return found
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
                depth++
            }
            return false
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        return false
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun focusNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(action)
    }

    fun findMainScrollable(screenWidth: Int, screenHeight: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val rect = Rect()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        readTree()?.let { stack.addLast(it) }

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()

            if (node.isScrollable && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()

                if (area > bestArea && area > screenWidth * screenHeight / 20) {
                    bestArea = area
                    best = node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }

    fun smartScroll(
        direction: ScrollDirection,
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float = 0.6f,
        onGestureDone: ((Boolean) -> Unit)? = null,
    ): String? {
        val horizontal = direction == ScrollDirection.LEFT ||
            direction == ScrollDirection.RIGHT

        val node = findMainScrollable(screenWidth, screenHeight)
        if (node != null) {
            val forward = direction == ScrollDirection.DOWN ||
                direction == ScrollDirection.RIGHT

            if (scrollNode(node, forward)) {
                return if (horizontal) "node-horizontal" else "node"
            }
        }

        val ratio = if (distanceRatio in 0.1f..1.0f) distanceRatio else 0.6f
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val dx = screenWidth * ratio / 2f
        val dy = screenHeight * ratio / 2f

        var x1 = cx
        var y1 = cy
        var x2 = cx
        var y2 = cy

        when (direction) {
            ScrollDirection.DOWN -> { y1 = cy + dy; y2 = cy - dy }
            ScrollDirection.UP -> { y1 = cy - dy; y2 = cy + dy }
            ScrollDirection.LEFT -> { x1 = cx + dx; x2 = cx - dx }
            ScrollDirection.RIGHT -> { x1 = cx - dx; x2 = cx + dx }
        }

        swipeAt(x1, y1, x2, y2, 300L, onGestureDone)
        return "gesture"
    }

    fun gesture(
        points: List<List<android.graphics.PointF>>,
        durationMs: Long,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onDone?.invoke(false)
            return
        }

        val builder = GestureDescription.Builder()
        for (path in points) {
            if (path.isEmpty()) continue
            val p = Path()
            p.moveTo(path.first().x, path.first().y)
            for (i in 1 until path.size) {
                p.lineTo(path[i].x, path[i].y)
            }
            builder.addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
        }

        dispatchGesture(
            builder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone?.invoke(false)
                }
            },
            null,
        )
    }

    fun longPressTimeout(): Long = try {
        android.view.ViewConfiguration.getLongPressTimeout().toLong()
    } catch (_: Throwable) {
        500L
    }

    fun gestureOnPath(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        spec: GestureSpec,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onDone?.invoke(false)
            return
        }

        val path = Path()
        path.moveTo(x1, y1)

        if (x1 != x2 || y1 != y2) {
            path.lineTo(x2, y2)
        }

        if (spec.restBeforeUpMs > 0) {
            path.lineTo(x2, y2)
        }

        val strokeDuration = (spec.moveMs + spec.restBeforeUpMs).coerceAtLeast(1)

        val stroke = GestureDescription.StrokeDescription(
            path,
            spec.holdBeforeMoveMs,
            strokeDuration,
        )

        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { onDone?.invoke(true) }
                override fun onCancelled(g: GestureDescription?) { onDone?.invoke(false) }
            },
            null,
        )
    }

    fun tapAt(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null) {
        gestureOnPath(x, y, x, y, GestureSpec.TAP, onDone)
    }

    fun longPressAt(
        x: Float, y: Float,
        durationMs: Long = 800,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val safe = durationMs.coerceAtLeast(longPressTimeout() + 100)
        gestureOnPath(x, y, x, y, GestureSpec.longPress(safe), onDone)
    }

    fun swipeAt(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        gestureOnPath(x1, y1, x2, y2,
            GestureSpec.swipe(durationMs), onDone)
    }

    fun doubleTapAt(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null) {
        tapAt(x, y) { first ->
            if (!first) {
                onDone?.invoke(false)
                return@tapAt
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tapAt(x, y, onDone)
            }, 80)
        }
    }

    fun flickAt(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 100,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        gestureOnPath(x1, y1, x2, y2,
            GestureSpec.flick(durationMs), onDone)
    }

    fun dragAt(
        x1: Float, y1: Float, x2: Float, y2: Float,
        moveMs: Long = 500,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        gestureOnPath(x1, y1, x2, y2,
            GestureSpec.drag(longPressTimeout(), moveMs), onDone)
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
}
