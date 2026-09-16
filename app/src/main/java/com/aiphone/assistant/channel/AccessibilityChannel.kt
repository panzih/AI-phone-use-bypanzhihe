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

/**
 * 无障碍通道。
 *
 * ## 定位策略：UI 树为主，截图辅助
 *
 * 这是本项目的核心路线选择。纯视觉（只看截图点像素）有个绕不开的问题：
 * 模型算的坐标会偏。Roubao 的 issue 里坐标偏移是第二高频问题，
 * 作者甚至承认"所有横屏设备都无法使用"。
 *
 * 而读 UI 控件树能**直接从系统拿到元素的精确 bounds** ——
 * 坐标不再需要模型去猜，也就不会偏。
 *
 * 截图仍然要，但用途变了：不是用来算坐标，而是给模型**理解界面语义**
 * （"这一屏是什么界面""该做什么"）。两者配合：
 *
 *     截图           → 理解语义、决定做什么
 *     UI 控件树      → 精确定位、决定点哪个
 *
 * ## 相对 ADB 方案的能力差异
 *
 *   多出来的：多指手势（dispatchGesture 天然支持）、
 *            直接灌文本（ACTION_SET_TEXT，绕开中文输入的全部坑）、
 *            重启不用重新授权
 *   失去的：  截不到安全窗口（FLAG_SECURE 页面）
 *            截图有平台限流（约 1 秒一次，实测确认）
 */
class AccessibilityChannel(private val context: Context) : DeviceChannel {

    override val displayName: String = "无障碍"
    override val capability: ChannelCapability = ChannelCapability.ACCESSIBILITY

    @Volatile
    private var cachedSize: Pair<Int, Int>? = null

    /** 上一帧解析出来的节点，供"按编号操作"用 */
    @Volatile
    private var lastNodes: List<UiNode> = emptyList()

    private val service: AutoService? get() = AutoService.get()

    // ------------------------------------------------------------------
    // 连通性
    // ------------------------------------------------------------------

    override suspend fun probe(): String? = when {
        service == null ->
            "无障碍服务未开启。请到「设置 → 无障碍 → 已安装的服务」里开启本应用。"
        else -> null
    }

    // ------------------------------------------------------------------
    // 屏幕
    // ------------------------------------------------------------------

    override suspend fun screenSize(): Pair<Int, Int>? {
        cachedSize?.let { return it }

        return withContext(Dispatchers.IO) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                ?: return@withContext null

            val size = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val m = wm.currentWindowMetrics
                val b = m.bounds
                b.width() to b.height()
            } else {
                @Suppress("DEPRECATION")
                val p = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
                p.x to p.y
            }

            size.takeIf { it.first > 0 && it.second > 0 }?.also { cachedSize = it }
        }
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------

    /**
     * 截一帧。
     *
     * 会重试 —— 无障碍截图容易撞上平台限流（ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT），
     * 隔一会儿重试基本都能拿到。这不是可选的优化，是必需的容错。
     */
    override suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null

        repeat(3) { attempt ->
            when (val r = svc.takeShot()) {
                is AutoService.ShotResult.Ok -> {
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        r.bitmap.recycle()
                        out.toByteArray()
                    }
                    return@withContext bytes
                }

                is AutoService.ShotResult.Fail -> {
                    android.util.Log.w("AccessibilityChannel",
                        "截图失败(尝试 ${attempt + 1}/3): ${r.reason}")
                    // 限流就等一下重试；安全窗口没救，直接放弃
                    if (r.reason.contains("太快") || r.reason.contains("太短")) {
                        Thread.sleep(400L * (attempt + 1))
                    } else {
                        return@withContext null
                    }
                }
            }
        }
        null
    }

    // ------------------------------------------------------------------
    // UI 控件树
    // ------------------------------------------------------------------

    override suspend fun dumpUiTree(): String? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null
        val (w, h) = screenSize() ?: (1080 to 1920)

        val nodes = svc.parseTree(w, h, limit = 60)
        lastNodes = nodes
        UiTreeParser.render(nodes)
    }

    /** 上一帧的节点列表，界面可以拿来显示 */
    fun lastParsedNodes(): List<UiNode> = lastNodes

    /**
     * 按节点编号点击。
     *
     * 这是**比坐标点击更可靠**的一条路：坐标直接来自系统给的 bounds，
     * 不存在模型算偏的问题。
     */
    suspend fun tapByIndex(
        index: Int,
        onPoint: ((Int, Int) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "无障碍服务未连接"

        // 每次操作都要重新解析 —— 节点对象不能跨帧复用，界面一变就失效
        val (w, h) = screenSize() ?: (1080 to 1920)
        val nodes = svc.parseTree(w, h, limit = 60)
        lastNodes = nodes

        val node = nodes.firstOrNull { it.index == index }
            ?: return@withContext "找不到编号 $index 的元素。可能界面已经变了，请重新观察。"

        val target = svc.findNodeByIndex(index, w, h)
            ?: return@withContext "编号 $index 的元素已经失效，界面可能变了"

        // 先报落点再动手：用户看到的水波位置，就是这一下真正点下去的地方。
        // 按编号点击时坐标来自系统的 bounds，所以这个圈比坐标式点击更可信
        onPoint?.invoke(node.centerX, node.centerY)

        // 先尝试节点级点击（最准）
        if (svc.clickNode(target)) return@withContext null

        // 节点点不动就退回坐标手势（有些自定义控件不响应 ACTION_CLICK）
        var ok = false
        svc.tapAt(node.centerX.toFloat(), node.centerY.toFloat()) { ok = it }
        Thread.sleep(120)
        return@withContext if (ok) null else "点击编号 $index 失败"
    }

    /** 按编号取节点。返回 null 表示编号无效或界面已变 */
    private suspend fun sceneIndex(index: Int): android.view.accessibility.AccessibilityNodeInfo? =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext null
            val (w, h) = screenSize() ?: (1080 to 1920)
            svc.findNodeByIndex(index, w, h)
        }

    // ------------------------------------------------------------------
    // 触控
    // ------------------------------------------------------------------

    override suspend fun perform(
        action: TouchAction,
        onPoint: ((Int, Int) -> Unit)?,
    ): String? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "无障碍服务未连接"

        try {
            when (action.kind) {
                TouchKind.TAP -> {
                    if (action.targetIndex > 0) {
                        // 按编号走 —— 更准，优先
                        tapByIndex(action.targetIndex, onPoint)
                    } else {
                        onPoint?.invoke(action.x, action.y)
                        val ok = doGesture { cb -> svc.tapAt(action.x.toFloat(), action.y.toFloat(), cb) }
                        if (ok) null else "点击失败"
                    }
                }

                TouchKind.LONG_PRESS -> {
                    // 优先节点级长按（更准），不行退回坐标手势
                    val node = if (action.targetIndex > 0) {
                        sceneIndex(action.targetIndex)
                    } else null

                    // 落点：按编号时用节点中心，否则用给的坐标
                    node?.let { n ->
                        val r = android.graphics.Rect()
                        n.getBoundsInScreen(r)
                        onPoint?.invoke(r.centerX(), r.centerY())
                    } ?: onPoint?.invoke(action.x, action.y)

                    if (node != null && svc.longClickNode(node)) {
                        null
                    } else {
                        val ok = doGesture { cb ->
                            svc.longPressAt(
                                action.x.toFloat(), action.y.toFloat(),
                                action.durationMs.coerceIn(300, 10_000).toLong(), cb,
                            )
                        }
                        if (ok) null else "长按失败"
                    }
                }

                TouchKind.DOUBLE_TAP -> {
                    // ⚠️ 这里原来直接用 action.x / action.y，**完全忽略了 index**。
                    // 而提示词告诉模型双击可以只给编号 —— 那样坐标是 (0,0)，
                    // 双击就打在屏幕左上角。下面按点击的同一套逻辑先解析编号。
                    val node = if (action.targetIndex > 0) {
                        sceneIndex(action.targetIndex)
                    } else null
                    val rect = android.graphics.Rect()
                    node?.getBoundsInScreen(rect)
                    val cx = if (node != null) rect.centerX() else action.x
                    val cy = if (node != null) rect.centerY() else action.y
                    if (node != null && (cx <= 0 || cy <= 0)) return@withContext "双击目标无效"

                    onPoint?.invoke(cx, cy)
                    val ok = doGesture { cb -> svc.doubleTapAt(cx.toFloat(), cy.toFloat(), cb) }
                    if (ok) null else "双击失败"
                }

                // 滚动：优先节点级（不需要坐标、不会滚过头），不行退回手势
                TouchKind.SCROLL -> {
                    val dir = action.direction ?: ScrollDirection.DOWN
                    val (w, h) = screenSize() ?: (1080 to 1920)
                    val method = svc.smartScroll(dir, w, h, action.distanceRatio)
                    if (method != null) null else "滚动失败"
                }

                // 拖拽：按下要超过长按阈值再移动，系统才认这是拖拽。
                // 用于移动图标、调滑块、排序这类
                TouchKind.DRAG -> {
                    val moveMs = action.durationMs.coerceIn(200, 10_000)
                    // 起终点各闪一下：用户看到"从哪划到哪"，
                    // 而不是只有一个孤零零的圈
                    onPoint?.invoke(action.x, action.y)
                    onPoint?.invoke(action.x2, action.y2)
                    val ok = doGesture { cb ->
                        svc.dragAt(
                            action.x.toFloat(), action.y.toFloat(),
                            action.x2.toFloat(), action.y2.toFloat(),
                            moveMs.toLong(), cb,
                        )
                    }
                    if (ok) null else "拖拽失败"
                }

                // 滑动：松手前有停顿，不触发惯性
                TouchKind.SWIPE -> {
                    val d = action.durationMs.coerceIn(200, 10_000)
                    // 起终点各闪一下：用户看到"从哪划到哪"，
                    // 而不是只有一个孤零零的圈
                    onPoint?.invoke(action.x, action.y)
                    onPoint?.invoke(action.x2, action.y2)
                    val ok = doGesture { cb ->
                        svc.swipeAt(
                            action.x.toFloat(), action.y.toFloat(),
                            action.x2.toFloat(), action.y2.toFloat(),
                            d.toLong(), cb,
                        )
                    }
                    if (ok) null else "滑动失败"
                }

                // 甩动：松手前不停顿，保持速度触发惯性滚动。
                // 和滑动的唯一区别就在这里，见 GestureSpec
                TouchKind.FLICK -> {
                    val d = action.durationMs.coerceIn(50, 200)
                    // 起终点各闪一下：用户看到"从哪划到哪"，
                    // 而不是只有一个孤零零的圈
                    onPoint?.invoke(action.x, action.y)
                    onPoint?.invoke(action.x2, action.y2)
                    val ok = doGesture { cb ->
                        svc.flickAt(
                            action.x.toFloat(), action.y.toFloat(),
                            action.x2.toFloat(), action.y2.toFloat(),
                            d.toLong(), cb,
                        )
                    }
                    if (ok) null else "甩动失败"
                }

                TouchKind.PINCH_OUT, TouchKind.PINCH_IN -> {
                    onPoint?.invoke(action.x, action.y)
                    pinch(svc, action)
                }

                TouchKind.MULTI_FINGER ->
                    "多指手势需要具体的手指路径，当前接口还没支持自定义路径"

                TouchKind.INPUT_TEXT -> inputText(action.text)

                TouchKind.KEY_BACK -> if (svc.globalBack()) null else "返回失败"
                TouchKind.KEY_HOME -> if (svc.globalHome()) null else "主页失败"
                TouchKind.KEY_RECENTS -> if (svc.globalRecents()) null else "多任务失败"

                TouchKind.OPEN_APP -> openApp(action.packageName)

                TouchKind.WAIT -> {
                    Thread.sleep(action.durationMs.coerceIn(200, 10_000).toLong())
                    null
                }
            }
        } catch (t: Throwable) {
            "执行失败：${t.javaClass.simpleName} ${t.message}"
        }
    }

    /**
     * 双指缩放。
     *
     * **这是无障碍相对 ADB 的一个真实优势** ——
     * `input` 命令只支持单指，ADB 方案做不了这个。
     * 这里用两条手势路径模拟：手指间距从 center±startGap 变到 center±endGap。
     */
    private suspend fun pinch(svc: AutoService, action: TouchAction): String? {
        val cx = action.x.toFloat()
        val cy = action.y.toFloat()

        // 两根手指的起止间距。
        // 放大 = 间距由小变大；缩小 = 由大变小。
        val (startGap, endGap) = if (action.kind == TouchKind.PINCH_OUT) {
            120f to 320f
        } else {
            320f to 120f
        }

        val duration = action.durationMs.coerceIn(200, 2_000).toLong()

        val ok = doGesture { cb ->
            svc.gesture(
                listOf(
                    // 左手指：从 (cx-startGap) 移到 (cx-endGap)
                    listOf(
                        PointF(cx - startGap, cy),
                        PointF(cx - endGap, cy),
                    ),
                    // 右手指：从 (cx+startGap) 移到 (cx+endGap)
                    listOf(
                        PointF(cx + startGap, cy),
                        PointF(cx + endGap, cy),
                    ),
                ),
                duration,
                cb,
            )
        }
        return if (ok) null else "双指手势失败"
    }

    /** 把回调式的 dispatchGesture 包成挂起调用 */
    private suspend fun doGesture(
        block: ((Boolean) -> Unit) -> Unit,
    ): Boolean = withTimeoutOrNull(5_000) {
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        block { ok ->
            result = ok
            latch.countDown()
        }
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        result
    } ?: false

    // ------------------------------------------------------------------
    // 文本输入：ACTION_SET_TEXT（无障碍的核心优势之一）
    // ------------------------------------------------------------------

    /**
     * 输入文本。
     *
     * 优先走两条不需要任何外部依赖的路：
     *   1. 当前焦点就是输入框 → 直接 ACTION_SET_TEXT
     *   2. 树里找到可编辑节点 → 聚焦后 ACTION_SET_TEXT
     *
     * 只有两条都不行，才提示用户去点一下输入框。
     *
     * 对比 ADB 方案：那边 `input text` 只支持 ASCII，中文要么装 ADBKeyboard
     * 要么走剪贴板，两种都别扭。这里是直接设字符串，任意 Unicode 都行。
     */
    private suspend fun inputText(text: String): String? {
        if (text.isEmpty()) return null
        val svc = service ?: return "无障碍服务未连接"

        // 路 1：当前有焦点的节点
        val focused = svc.findFocus()
        if (focused != null && svc.setText(focused, text)) return null

        // 路 2：树里第一个可编辑节点
        val (w, h) = screenSize() ?: (1080 to 1920)
        val nodes = svc.parseTree(w, h, limit = 60)
        val editable = nodes.firstOrNull { it.editable }
        if (editable != null) {
            val node = svc.findNodeByIndex(editable.index, w, h)
            if (node != null) {
                svc.focusNode(node)
                Thread.sleep(120)
                if (svc.setText(node, text)) return null
            }
        }

        return "找不到可输入的输入框。请先点击要输入的框再重试。"
    }

    private suspend fun openApp(pkg: String): String? {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "找不到应用：$pkg（包名可能不对）"
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Thread.sleep(600)
            null
        } catch (t: Throwable) {
            "启动失败：${t.message}"
        }
    }

    override fun release() {
        cachedSize = null
        lastNodes = emptyList()
    }
}
