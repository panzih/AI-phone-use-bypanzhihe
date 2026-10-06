package com.aiphone.assistant.channel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.a11y.NodeLocator
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.a11y.UiTreeParser
import com.aiphone.assistant.log.AppLog
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

    /** 最后一次截图失败的原因，null 表示上次成功或还没失败过 */
    @Volatile
    var lastScreenshotError: String? = null
        private set

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
        val svc = service ?: run {
            lastScreenshotError = "无障碍服务未连接"
            return@withContext null
        }

        repeat(3) { attempt ->
            when (val r = svc.takeShot()) {
                is AutoService.ShotResult.Ok -> {
                    // **全分辨率** JPEG。两条都是硬要求：
                    //   1. 绝对不缩放 —— 提示词里承诺的是"坐标范围 0..屏宽/屏高"，
                    //      一缩放模型给的 x/y 就和真实屏幕对不上，点哪都偏。
                    //   2. 用 JPEG 而不是 PNG —— 历史里的图**每次请求都要重发**，
                    //      PNG 一张 1MB 级，base64 后更大；q82 的 JPEG 同样分辨率
                    //      通常只有几十 KB，界面文字依然清楚。
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        r.bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        r.bitmap.recycle()
                        out.toByteArray()
                    }
                    lastScreenshotError = null
                    return@withContext bytes
                }

                is AutoService.ShotResult.Fail -> {
                    android.util.Log.w("AccessibilityChannel",
                        "截图失败(尝试 ${attempt + 1}/3): ${r.reason}")
                    // 限流就等一下重试；安全窗口没救，直接放弃
                    if (r.reason.contains("太快") || r.reason.contains("太短")) {
                        lastScreenshotError = "截图被系统限流：${r.reason}"
                        Thread.sleep(400L * (attempt + 1))
                    } else {
                        lastScreenshotError = "截图失败：${r.reason}"
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
        readTree().text
    }

    /**
     * 读一次控件树，带可读性信息。
     *
     * **根节点读不到时返回 text = null**，而不是渲染成一句占位语。
     * 旧实现在这里返回占位语，于是上层"读不到树就自动带截图"的分支
     * 对无障碍通道永远走不到 —— 0.8.5 日志里「控件树：1 个元素」然后
     * 模型全程瞎猜，根因就在这一行。
     */
    override suspend fun readTree(): UiTreeRead = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext UiTreeRead.unavailable()
        val (w, h) = screenSize() ?: (1080 to 1920)

        // 只读一次 root：parseTreeDetailed 内部自己 readTree，
        // 但那样日志里的 rawNodeCount 和实际发给模型的那份就可能不是同一帧
        val root = svc.readTree() ?: return@withContext UiTreeRead.unavailable()
        val parsed = UiTreeParser.parseDetailed(root, w, h, TREE_LIMIT, svc.ownPackageName)
        lastNodes = parsed.nodes
        UiTreeRead(
            // 没有元素就给 null，让"读不到元素列表"在类型上是个明确分支
            text = if (parsed.nodes.isEmpty()) null else UiTreeParser.render(parsed.nodes),
            nodes = parsed.nodes,
            rootAvailable = true,
            rawNodeCount = parsed.rawNodeCount,
            truncated = parsed.truncated,
        )
    }

    /**
     * 结构化节点列表，给宏技能回放用。
     *
     * 为什么不用 [dumpUiTree] 的文本：回放要比对 viewId 和精确的文字，
     * 而那个格式是给模型读的（截断过、带装饰），用来做匹配不可靠。
     */
    override suspend fun currentNodes(): List<UiNode> = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext emptyList()
        val (w, h) = screenSize() ?: (1080 to 1920)
        svc.parseTree(w, h, limit = TREE_LIMIT).also { lastNodes = it }
    }

    /**
     * 和 [currentNodes] 一样，但**读不到根节点时返回 null** 而不是空列表。
     *
     * 空列表的指纹是一个固定值，会让"动作前后界面没变化"永远成立 ——
     * 于是每一次点击都被判成"没点中"、被无意义地重试一遍（0.8.5 日志里
     * 高德和中信证券都出现过）。null 才能表达"这一帧压根没看到东西"。
     */
    override suspend fun currentNodesOrNull(): List<UiNode>? {
        val r = readTree()
        if (!r.rootAvailable) return null
        lastNodes = r.nodes
        return r.nodes
    }

    /** 上一帧的节点列表，界面可以拿来显示 */
    fun lastParsedNodes(): List<UiNode> = lastNodes

    /**
     * 按节点编号点击。
     *
     * 这是**比坐标点击更可靠**的一条路：坐标直接来自系统给的 bounds，
     * 不存在模型算偏的问题。
     *
     * ## ⚠️ 这里必须按"身份"找回目标，不能直接点第 N 个
     *
     * 编号是**逐帧**的。模型看到列表是在一次网络往返之前（1~5 秒），
     * 这中间界面可能重排 —— 桌面图标加载完、列表滚动、页面刷新。
     * 旧实现到点击那一刻才重新解析，然后直接取「第 N 个」：
     * 界面一变它就是**另一个控件**，点下去不会有任何反应，日志里只留下
     * 一句"已执行但界面没变化"。0.8.5 日志里高德「编号[38]/[49]/[33]」
     * 反复点不动，就是这一类。
     *
     * 所以现在拿模型当时看到的节点当线索（[hint]）：
     *   - 在新一帧里按身份找回 → 点它（日志会记下依据）；
     *   - 找不回 → **如实失败**，让模型重新观察，绝不盲点第 N 个。
     *
     * @param hint 模型看到的那一份列表里的同一个节点；旧调用点（端侧清弹窗）
     *        传 null，表示"编号是刚刚这一帧解析出来的"，可以直接用。
     */
    suspend fun tapByIndex(
        index: Int,
        hint: UiNode? = null,
        onPoint: ((Int, Int) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "无障碍服务未连接"

        // 每次操作都要重新解析 —— 节点对象不能跨帧复用，界面一变就失效
        val (w, h) = screenSize() ?: (1080 to 1920)
        val parsed = svc.parseTreeDetailed(w, h, limit = TREE_LIMIT)
        val nodes = parsed.nodes
        lastNodes = nodes

        // 拿模型看到的那一帧当线索时，先按身份在新一帧里找回来
        val match: NodeLocator.Match = if (hint != null) {
            val m = NodeLocator.locate(hint, nodes, w)
                ?: return@withContext "这一步要点的「${hint.labelForLog()}」" +
                    "（当时是编号[$index]）已经不在当前界面上了，说明界面变了。" +
                    "请重新观察界面元素后再点。"
            // 记下"怎么找回来的"：复盘时要能看出是身份匹配还是编号兜底
            if (m.node.index != index) {
                android.util.Log.i(
                    "AccessibilityChannel",
                    "编号漂移：模型看到 [$index]「${hint.labelForLog()}」，" +
                        "按${m.reason}在新一帧匹配到 [${m.node.index}]",
                )
            }
            m
        } else {
            val direct = nodes.firstOrNull { it.index == index }
                ?: return@withContext "找不到编号 $index 的元素。可能界面已经变了，请重新观察。"
            NodeLocator.Match(direct, "编号")
        }
        val resolved: UiNode = match.node

        // 先报落点再动手：用户看到的水波位置，就是这一下真正点下去的地方
        onPoint?.invoke(resolved.centerX, resolved.centerY)

        // 取原始节点：节点级点击最准（不可点的容器会自动往上找可点的父节点）。
        // 取回来时再核对一次 bounds —— 万一刚才那一帧又变了，就不做节点级点击，
        // 直接用匹配到的中心坐标手势，至少落点是"身份匹配过"的那个控件
        val target = svc.findNodeByIndex(resolved.index, w, h)
        val rect = android.graphics.Rect()
        target?.getBoundsInScreen(rect)
        val sameSpot = target != null &&
            kotlin.math.abs(rect.centerX() - resolved.centerX) <= NodeLocator.tolerance(w) &&
            kotlin.math.abs(rect.centerY() - resolved.centerY) <= NodeLocator.tolerance(w)

        if (target != null && sameSpot && svc.clickNode(target)) {
            AppLog.i(
                "  编号[$index] 实际点中「${resolved.labelForLog()}」" +
                    "@(${resolved.centerX},${resolved.centerY})，节点级点击",
                "执行",
            )
            return@withContext null
        }

        // 节点点不动就退回坐标手势（有些自定义控件不响应 ACTION_CLICK）
        var ok = false
        svc.tapAt(resolved.centerX.toFloat(), resolved.centerY.toFloat()) { ok = it }
        Thread.sleep(120)
        if (!ok) {
            AppLog.e(
                "  编号[$index] →「${resolved.labelForLog()}」" +
                    "@(${resolved.centerX},${resolved.centerY}) 手势也没成功",
                "执行",
            )
            return@withContext "点击编号 [$index]→「${resolved.labelForLog()}」失败"
        }
        AppLog.i(
            "  编号[$index] 实际点中「${resolved.labelForLog()}」" +
                "@(${resolved.centerX},${resolved.centerY})，坐标手势",
            "执行",
        )
        return@withContext null
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
        hint: UiNode?,
    ): String? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "无障碍服务未连接"

        try {
            when (action.kind) {
                // dismiss_dialog 在 Agent 层就转成 TAP，不会走到通道；兜底报错
                TouchKind.DISMISS_DIALOG -> "内部错误：关闭弹窗未在端侧处理"

                // capture 由 Agent 自己处理（截图不走通道），走到这里说明有 bug
                TouchKind.CAPTURE -> "内部错误：截屏不该发到设备通道"

                TouchKind.TAP -> {
                    if (action.targetIndex > 0) {
                        // 按编号走 —— 更准，优先；带上模型看到的那一帧当身份线索
                        tapByIndex(action.targetIndex, hint, onPoint)
                    } else {
                        onPoint?.invoke(action.x, action.y)
                        doGesture { cb -> svc.tapAt(action.x.toFloat(), action.y.toFloat(), cb) }
                            .toError("点击")
                    }
                }

                TouchKind.LONG_PRESS -> {
                    // 优先节点级长按（更准），不行退回坐标手势。
                    // 编号同样可能已经漂移，所以先用 [hint] 找回同一个控件
                    val node: android.view.accessibility.AccessibilityNodeInfo? = when {
                        hint != null -> {
                            val n = relocateNode(svc, hint)
                            if (n == null) {
                                return@withContext "这一步要长按的「${hint.labelForLog()}」" +
                                    "已经不在当前界面上了，请重新观察。"
                            }
                            n
                        }
                        action.targetIndex > 0 -> sceneIndex(action.targetIndex)
                        else -> null
                    }

                    // 落点：按编号时用节点中心，否则用给的坐标
                    node?.let { n ->
                        val r = android.graphics.Rect()
                        n.getBoundsInScreen(r)
                        onPoint?.invoke(r.centerX(), r.centerY())
                    } ?: onPoint?.invoke(action.x, action.y)

                    if (node != null && svc.longClickNode(node)) {
                        null
                    } else {
                        doGesture { cb ->
                            svc.longPressAt(
                                action.x.toFloat(), action.y.toFloat(),
                                action.durationMs.coerceIn(300, 10_000).toLong(), cb,
                            )
                        }.toError("长按")
                    }
                }

                TouchKind.DOUBLE_TAP -> {
                    // ⚠️ 这里原来直接用 action.x / action.y，**完全忽略了 index**。
                    // 而提示词告诉模型双击可以只给编号 —— 那样坐标是 (0,0)，
                    // 双击就打在屏幕左上角。下面按点击的同一套逻辑先解析编号。
                    val node: android.view.accessibility.AccessibilityNodeInfo? = when {
                        hint != null -> {
                            val n = relocateNode(svc, hint)
                            if (n == null) {
                                return@withContext "这一步要双击的「${hint.labelForLog()}」" +
                                    "已经不在当前界面上了，请重新观察。"
                            }
                            n
                        }
                        action.targetIndex > 0 -> sceneIndex(action.targetIndex)
                        else -> null
                    }
                    val rect = android.graphics.Rect()
                    node?.getBoundsInScreen(rect)
                    val cx = if (node != null) rect.centerX() else action.x
                    val cy = if (node != null) rect.centerY() else action.y
                    if (node != null && (cx <= 0 || cy <= 0)) return@withContext "双击目标无效"

                    onPoint?.invoke(cx, cy)
                    doGesture { cb -> svc.doubleTapAt(cx.toFloat(), cy.toFloat(), cb) }
                        .toError("双击")
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
                    doGesture { cb ->
                        svc.dragAt(
                            action.x.toFloat(), action.y.toFloat(),
                            action.x2.toFloat(), action.y2.toFloat(),
                            moveMs.toLong(), cb,
                        )
                    }.toError("拖拽")
                }

                // 滑动：松手前有停顿，不触发惯性
                TouchKind.SWIPE -> {
                    val d = action.durationMs.coerceIn(200, 10_000)
                    // 起终点各闪一下：用户看到"从哪划到哪"，
                    // 而不是只有一个孤零零的圈
                    onPoint?.invoke(action.x, action.y)
                    onPoint?.invoke(action.x2, action.y2)
                    doGesture { cb ->
                        svc.swipeAt(
                            action.x.toFloat(), action.y.toFloat(),
                            action.x2.toFloat(), action.y2.toFloat(),
                            d.toLong(), cb,
                        )
                    }.toError("滑动")
                }

                // 甩动：松手前不停顿，保持速度触发惯性滚动。
                // 和滑动的唯一区别就在这里，见 GestureSpec
                TouchKind.FLICK -> {
                    val d = action.durationMs.coerceIn(50, 200)
                    // 起终点各闪一下：用户看到"从哪划到哪"，
                    // 而不是只有一个孤零零的圈
                    onPoint?.invoke(action.x, action.y)
                    onPoint?.invoke(action.x2, action.y2)
                    doGesture { cb ->
                        svc.flickAt(
                            action.x.toFloat(), action.y.toFloat(),
                            action.x2.toFloat(), action.y2.toFloat(),
                            d.toLong(), cb,
                        )
                    }.toError("甩动")
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

        return doGesture { cb ->
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
        }.toError("双指手势")
    }

    /** 把回调式的 dispatchGesture 包成挂起调用 */
    private suspend fun doGesture(
        block: ((Boolean) -> Unit) -> Unit,
    ): GestureResult {
        val result = withTimeoutOrNull(5_000) {
            var success = false
            val latch = java.util.concurrent.CountDownLatch(1)
            block { ok ->
                success = ok
                latch.countDown()
            }
            latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
            if (success) GestureResult.Success else GestureResult.Cancelled
        }
        return result ?: GestureResult.Timeout
    }

    /** 手势执行结果 */
    private sealed class GestureResult {
        object Success : GestureResult()
        object Cancelled : GestureResult()
        object Timeout : GestureResult()

        /** 转成给用户看的错误信息，null 表示成功 */
        fun toError(actionName: String): String? = when (this) {
            Success -> null
            is Cancelled -> "${actionName}被系统取消了（可能是界面正在切换，或者有弹窗挡住了）"
            is Timeout -> "${actionName}超时了（系统 5 秒内没给结果）"
        }
    }

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
     *
     * ## ⚠️ 必须回读确认，不能信 performAction 的返回值
     *
     * 0.8.5 日志里连着两次「输入「张三」→ 已执行」，可截图里搜索框
     * **依然是空的**，模型因此又花了两步反复输入。原因就是
     * `ACTION_SET_TEXT` 对某些自绘/自定义输入框会返回 true 但什么也没写进去，
     * 我们却把它当成成功回灌给了模型。
     *
     * 所以现在：设完**回读节点文字**核对。
     *   - 读回来包含要输入的内容 → 成功
     *   - 读回来是别的内容 → 如实报失败
     *   - 读不回来（输入框不暴露文字）→ 报"已输入但无法确认"，让模型去看截图，
     *     而不是像以前那样直接说"已执行"
     */
    private suspend fun inputText(text: String): String? {
        if (text.isEmpty()) return null
        val svc = service ?: return "无障碍服务未连接"
        val own = svc.ownPackageName

        // 路 1：当前有焦点的节点
        val focused = svc.findFocus()
        if (focused != null) {
            val pkg = runCatching { focused.packageName?.toString() }.getOrNull()
            // 焦点落在我们自己的输入框上（纸盒主界面在后台但仍有焦点）时
            // 绝不能往里灌 —— 灌进去会改掉用户刚打的字，而且完全看不出来
            if (own != null && pkg == own) {
                AppLog.w("输入被拒：当前焦点是纸盒自己的输入框（$pkg），不往里灌文字", "输入")
            } else if (svc.setText(focused, text)) {
                return verifyText(svc, focused, text)
            }
        }

        // 路 2：树里第一个可编辑节点
        val (w, h) = screenSize() ?: (1080 to 1920)
        val parsed = svc.parseTreeDetailed(w, h, limit = TREE_LIMIT)
        if (!parsed.nodes.any { it.editable }) {
            return "找不到可输入的输入框（这一屏没有可编辑控件，或读不到控件树）。" +
                "请先点击要输入的框再重试。"
        }
        val editable = parsed.nodes.first { it.editable }
        val node = svc.findNodeByIndex(editable.index, w, h)
            ?: return "输入框（编号[${editable.index}]）在这一帧已经失效，请重新观察后再输入。"
        svc.focusNode(node)
        Thread.sleep(120)
        if (!svc.setText(node, text)) {
            return "输入框（编号[${editable.index}]）不接受直接写入（系统拒绝了 SET_TEXT）。" +
                "请改用点击输入框后再输入。"
        }
        return verifyText(svc, node, text)
    }

    /**
     * 灌完文字回读核对。
     *
     * @return null = 确认成功；否则是给模型看的失败/未确认原因
     */
    private fun verifyText(
        svc: AutoService,
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String,
    ): String? {
        val readBack = runCatching { node.refresh() }.getOrDefault(false)
        val actual = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        return when {
            actual.contains(text) -> null
            actual.isNotBlank() ->
                "输入「${text.take(20)}」没成功：输入框里现在是「${actual.take(20)}」。请重新操作。"
            // 回读不到内容：可能是自绘输入框。**不能报成功** —— 那正是 0.8.5
            // 里"模型以为输入了、其实没有"的来源。如实说"没确认"
            !readBack || actual.isBlank() ->
                "已经尝试写入「${text.take(20)}」，但这个输入框读不回内容，" +
                    "无法确认是否真的输入成功。请截图确认一下再决定下一步。"
            else -> "输入「${text.take(20)}」没成功，请重新操作。"
        }
    }

    /**
     * 用"身份线索"在当前一帧里找回同一个节点（长按/双击用）。
     *
     * 返回 null 表示这一帧已经找不到它了 —— 调用方要**如实失败**，
     * 不能退化成按原编号点。
     */
    private suspend fun relocateNode(
        svc: AutoService,
        hint: UiNode,
    ): android.view.accessibility.AccessibilityNodeInfo? = withContext(Dispatchers.IO) {
        val (w, h) = screenSize() ?: (1080 to 1920)
        val nodes = svc.parseTree(w, h, limit = TREE_LIMIT)
        val m = NodeLocator.locate(hint, nodes, w) ?: return@withContext null
        svc.findNodeByIndex(m.node.index, w, h)
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

    companion object {
        /**
         * 发给模型的元素上限。
         *
         * 撞到上限时 [UiTreeRead.truncated] 为 true，Agent 会在日志里写明
         * "已达上限、界面还有元素没发给模型" —— 以前这个是静默截断的，
         * 复盘时看到"控件树：60 个元素"完全看不出是被砍过的。
         */
        const val TREE_LIMIT = 60
    }
}
