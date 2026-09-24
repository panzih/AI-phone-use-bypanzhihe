package com.aiphone.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.record.Recorder
import com.aiphone.assistant.touch.GestureSpec
import com.aiphone.assistant.touch.ScrollDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 核心无障碍服务 —— 整个应用的操作手和眼睛。
 *
 * ## 为什么用无障碍而不是 ADB / Shizuku
 *
 * ADB 方案（Shizuku）能力更强，但**无 root 时每次手机重启都要重新用
 * 无线调试启动一次**，对日常使用太不方便。无障碍一旦授权就常驻，重启自动恢复。
 *
 * 代价是两件事做不到：
 *   1. 截不到安全窗口（银行密码键盘那种，FLAG_SECURE 的页面）
 *   2. 截图有平台限流（约 1 秒一次，实测待确认）
 * 对一个每步要 3-8 秒的 AI 循环来说，限流不是瓶颈。
 *
 * ## 这个类里有什么
 *
 *   - 截图（takeScreenshot，API 30+）
 *   - 读 UI 控件树
 *   - 按节点点击（比坐标准，因为坐标是系统给的）
 *   - 按坐标手势（dispatchGesture，支持多指）
 *   - 直接灌文本（ACTION_SET_TEXT，**绕开中文输入的全部坑**）
 */
class AutoService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoService"

        /** 全局实例。同一时刻系统只会绑定一个，用 @Volatile 保证可见性 */
        @Volatile
        private var instance: AutoService? = null

        /** 服务是否已连接 */
        val isConnected: Boolean get() = instance != null

        /** 拿实例。没连接时返回 null，调用方要处理 */
        fun get(): AutoService? = instance
    }

    override fun onServiceConnected() {
        // 录制的第一件事是不录自己：用户在纸盒界面里点「停止录制」
        // 也是一次 TYPE_VIEW_CLICKED，不排掉就会录进去
        Recorder.selfPackage = packageName
        super.onServiceConnected()
        instance = this
        homePackages = resolveHomePackages()
        imePackages = resolveImePackages()
        Log.i(TAG, "无障碍服务已连接，桌面候选：$homePackages，输入法：$imePackages")
    }

    /**
     * 全部可见桌面（launcher）包名，onServiceConnected 解析一次缓存。
     * 用集合而非"挑一个默认"（R2）：真机可能有多个第三方桌面、priority 相同时
     * 取任意一个会认错；FallbackHome、双桌面、切桌面都能自然覆盖。
     */
    @Volatile
    private var homePackages: Set<String> = emptySet()

    /** 回到桌面的起始时间（epoch ms）；0 = 当前不在桌面（0.8.3 自动切副屏用） */
    @Volatile
    private var desktopSinceMsField: Long = 0L

    /** 最近一个非桌面 WINDOW_STATE_CHANGED 的包（用来锁定"回桌面前在哪个 app"，R4） */
    @Volatile
    private var lastForegroundPkg: String? = null

    /** 这次回桌面之前所在的 app 包（R4：证明回桌面是从目标 app 切走的） */
    @Volatile
    private var pkgBeforeDesktop: String? = null

    /** 当前启用的输入法包名（R4：键盘窗口不是"前台 app"，要从 lastForegroundPkg 排除） */
    @Volatile
    private var imePackages: Set<String> = emptySet()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // AI 主动操作时是"轮询截图"模式，不依赖事件驱动。
        // 但「操作记录」要靠事件知道用户点了什么 —— 录制没开始时
        // Recorder 第一件事就是 return，所以这里的开销可以忽略。
        event?.let { Recorder.onEvent(it) }
        event?.let { trackDesktop(it) }
        event?.let { maybeProbeOnOtherDisplay(it) }
    }

    /**
     * 解析全部可见桌面包名（R2：集合，不挑"默认"）。
     * CATEGORY_HOME 的 queryIntentActivities 候选即所有桌面；flags=0 两个都返回
     * （MATCH_DEFAULT_ONLY 实测会漏）。排除纸盒自己。Manifest <queries> 已声明 HOME。
     */
    private fun resolveHomePackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * 当前启用的输入法包名（R4）。键盘（IME）也会发 WINDOW_STATE_CHANGED，
     * 但它不是"前台 app"，记录"回桌面前在哪个 app"时必须排除，否则用户输入后
     * 键盘残留、按 HOME 会把来路误记成输入法。onServiceConnected 取一次缓存。
     */
    private fun resolveImePackages(): Set<String> = runCatching {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.enabledInputMethodList.map { it.serviceInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /**
     * 跟踪"是否回到桌面"，Agent.interruption() 只读这个内存时间、零额外 binder。
     *
     * R1：只认 [TYPE_WINDOW_STATE_CHANGED]——其他事件（systemui 状态栏时钟会
     * 周期性发事件、A1 实验统计到 14 个）一律不碰计时，否则在桌面停着也会被
     * 反复清零、防抖永远攒不满。
     * 护栏3：桌面 [TYPE_VIEW_CLICKED]（用户点图标开 app）把计时重置。
     */
    private fun trackDesktop(event: AccessibilityEvent) {
        if (homePackages.isEmpty()) return
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> when {
                homePackages.contains(pkg) -> {
                    // R4：记下"回桌面前在哪个 app"，直接证明这次回桌面的来路
                    pkgBeforeDesktop = lastForegroundPkg
                    if (desktopSinceMsField == 0L) {
                        desktopSinceMsField = System.currentTimeMillis()
                        Log.i(TAG, "检测到回桌面（$pkg），此前 app：$pkgBeforeDesktop")
                    }
                }
                else -> {
                    // 输入法窗口只是键盘覆盖层，不算"前台 app"（R4）
                    if (pkg !in imePackages) lastForegroundPkg = pkg
                    if (desktopSinceMsField != 0L) Log.i(TAG, "离开桌面（$pkg）")
                    desktopSinceMsField = 0L
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 用户在桌面上点图标 → 要开别的 app，取消这一次待触发（护栏3）
                if (homePackages.contains(pkg) && desktopSinceMsField != 0L) {
                    desktopSinceMsField = 0L
                    Log.i(TAG, "在桌面点击（$pkg），取消自动切副屏")
                }
            }
        }
    }

    /** 回到桌面的起始时间（epoch ms）；0 = 当前不在桌面 */
    fun desktopSinceMs(): Long = desktopSinceMsField

    /** 这次回桌面之前所在的 app 包（R4，供 Agent 判定回桌面是否从目标 app 切走） */
    fun packageBeforeDesktop(): String? = pkgBeforeDesktop

    /** 该包是不是桌面（供 Agent 过滤目标 app，R2 用集合判定） */
    fun isHomePackage(pkg: String?): Boolean = pkg != null && homePackages.contains(pkg)

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

    /**
     * 当前前台是哪个应用（返回包名）。
     *
     * 用途很具体：**判断我们自己是不是在前台**。
     *
     * 无障碍截图抓的是整块物理屏，控制应用自己在前台时，
     * 截到的就是纸盒的界面 —— 把这张图发给模型，它会开始点
     * 我们自己的按钮。这在真机上必然发生，不是理论风险。
     * 所以循环开始前用它检查一下，是我们就先让开。
     *
     * 拿不到时返回 null（比如界面正在切换，根节点还没建好）。
     */
    fun currentPackage(): String? = runCatching {
        rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    // ------------------------------------------------------------------
    // 眼睛：截图
    // ------------------------------------------------------------------

    /** 截图结果 */
    sealed class ShotResult {
        data class Ok(val bitmap: Bitmap) : ShotResult()
        data class Fail(val reason: String) : ShotResult()
    }

    /**
     * 截一帧。
     *
     * 三个已知失败原因，都要明确告诉调用方，不能静默返回空：
     *   - API < 30：没有这个接口
     *   - 安全窗口：FLAG_SECURE 的页面截不到，会返回 ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
     *   - 限流：距上次截图太快，返回 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
     *
     * 这个是异步回调接口，包成阻塞调用方便上层用同步写法。
     */
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
                        // wrapHardwareBuffer 出来的是硬件位图，
                        // 复制一份成软件位图，否则后续 getPixel / 跨线程用会出问题
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

    // ------------------------------------------------------------------
    // 眼睛：UI 控件树
    // ------------------------------------------------------------------

    /** 读当前窗口的控件树 */
    fun readTree(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        Log.w(TAG, "读取控件树失败：${t.message}")
        null
    }

    /**
     * 我们自己的包名。控件树里要**剪掉**这一支 ——
     * 悬浮面板上的「急停」也长得像个按钮，混进列表里模型真会去点，
     * 而点下去就是把用户的任务停了。
     *
     * 放在这里而不是让调用方传：少一个参数就少一处"忘了传"的机会，
     * 而忘了传的后果（模型点自己的急停）非常难查。
     */
    private val ownPackage: String? get() = try {
        packageName
    } catch (t: Throwable) {
        null
    }

    /** 解析成精简后的节点列表 */
    fun parseTree(screenWidth: Int, screenHeight: Int, limit: Int = 60): List<UiNode> =
        UiTreeParser.parse(readTree(), screenWidth, screenHeight, limit, ownPackage)

    // ------------------------------------------------------------------
    // 探针：跨所有 display（含虚拟副屏）读窗口，验证副屏能否拿控件树
    // ------------------------------------------------------------------

    /** 上次探针时间，防抖（副屏事件很密） */
    @Volatile
    private var lastProbeMs: Long = 0L

    /**
     * 事件来自非默认屏（虚拟副屏）时自动跑一次，3s 防抖。
     *
     * 注意：[AccessibilityEvent.getDisplayId] 是 API 33 才有的，
     * minSdk=28，低于 33 的机器直接跳过，否则 onAccessibilityEvent
     * 里会抛 NoSuchMethodError 把无障碍服务搞挂。
     */
    private fun maybeProbeOnOtherDisplay(event: AccessibilityEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (event.displayId == android.view.Display.DEFAULT_DISPLAY) return
        val now = System.currentTimeMillis()
        if (now - lastProbeMs < 3_000) return
        lastProbeMs = now
        probeWindowsOnAllDisplays()
    }

    /**
     * 探针：副屏到底能不能读到控件树。
     *
     * [AccessibilityService.getWindowsOnAllDisplays] 是 API 30+ 的公开方法，
     * 返回 `SparseArray<List<AccessibilityWindowInfo>>`（key=displayId）。
     * 普通 [getWindows] 只覆盖服务所在的那块屏，这个能看到虚拟副屏的窗口
     * 和它们的 root 节点——这正是 0.7.0 当时没验证的点。
     *
     * 只读、不改任何行为。结果写日志，也返回字符串供设置页手动按钮 toast。
     */
    fun probeWindowsOnAllDisplays(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "探针：系统版本低于 Android 11，无 getWindowsOnAllDisplays"
        }
        return runCatching {
            val all: android.util.SparseArray<List<AccessibilityWindowInfo>> =
                getWindowsOnAllDisplays()
            buildString {
                appendLine("探针 getWindowsOnAllDisplays：共 ${all.size()} 块屏")
                for (i in 0 until all.size()) {
                    val displayId = all.keyAt(i)
                    val ws = all.valueAt(i)
                    appendLine("  display $displayId：${ws.size} 个窗口")
                    for (w in ws) {
                        val root = runCatching { w.root }.getOrNull()
                        appendLine(
                            "    ${windowTypeName(w.type)} " +
                                "pkg=${root?.packageName ?: "—"} root=${if (root != null) "非空" else "空"}"
                        )
                    }
                }
            }.also {
                Log.i(TAG, it.trim())
                AppLog.i(it.trim(), "副屏探针")
            }.trim()
        }.getOrElse {
            val msg = "探针 getWindowsOnAllDisplays 失败：${it.javaClass.simpleName} ${it.message}"
            Log.w(TAG, msg)
            AppLog.w(msg, "副屏探针")
            msg
        }
    }

    private fun windowTypeName(t: Int): String = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "应用"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "系统"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "无障碍覆盖层"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "分屏分隔"
        else -> "type$t"
    }

    /**
     * 当前有输入焦点的节点。
     *
     * 灌文本时优先用它 —— 用户刚点过的输入框就是焦点，
     * 不需要再去树里找，也避免灌错框。
     */
    fun findFocus(): AccessibilityNodeInfo? = try {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (t: Throwable) {
        null
    }

    /**
     * 按编号找节点。编号是 [parseTree] 给的。
     *
     * 必须重新解析一遍（节点对象不能跨帧缓存，界面一变就失效），
     * 而且**必须和 parseTree 用同一套遍历** —— 过滤规则只要差一点，
     * 编号就整体错位，模型说点 3 号结果点到 5 号，且完全查不出来。
     * 所以这里直接复用 [UiTreeParser.keepNodes]。
     */
    fun findNodeByIndex(
        index: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): AccessibilityNodeInfo? {
        if (index <= 0) return null
        val nodes = UiTreeParser.keepNodes(
            root = readTree(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            limit = index,
            excludePackage = ownPackage,
        )
        return nodes.getOrNull(index - 1)
    }

    // ------------------------------------------------------------------
    // 手：操作
    // ------------------------------------------------------------------

    /**
     * 按节点点击。
     *
     * 比按坐标点准 —— 坐标是系统直接给的控件 bounds，
     * 不存在"模型算错位置"这回事。
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) {
            // 节点本身不可点，往上找最近的可点父节点
            // （很多界面把点击事件挂在外层容器上）
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

    /**
     * 长按节点。
     */
    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        return false
    }

    /**
     * 往输入框灌文本。
     *
     * **这是无障碍相对 ADB 最大的优势之一。**
     *
     * `adb shell input text` 只支持 ASCII，中文要装 ADBKeyboard 或者走剪贴板，
     * 两种方案都很别扭。而 ACTION_SET_TEXT 直接把字符串设进去，
     * 任意 Unicode 都行，不需要任何额外依赖。
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 点一下获取焦点用的（有些输入框要先聚焦才能灌文本） */
    fun focusNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

    /** 滚动指定节点。forward = 看后面的内容 */
    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(action)
    }

    /**
     * 找当前界面里最可能是"主滚动区"的节点。
     *
     * 一个界面上可能有多个可滚动区域（侧边栏、主列表、嵌套表格），
     * 怎么选？策略是**挑面积最大的那个**。
     *
     * 这是经验判断：主内容区通常占屏幕面积最大，
     * 侧边栏和嵌在小弹窗里的列表都比它小。
     * 比"随便挑第一个"靠谱得多。
     */
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

                // 面积太小的不算主滚动区（比如一小块嵌套列表）
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

    /**
     * 智能滚动：优先节点级，不行退回手势。
     *
     * 为什么要两条路：
     *   - 节点级（ACTION_SCROLL_FORWARD）**不需要坐标**，由系统决定滚多少，
     *     不会滚过头，是最干净的做法
     *   - 但自定义控件（游戏、地图、部分 App）的 isScrollable 是 false，
     *     或者压根不响应这个 action，这时只能用手势模拟
     *
     * 所以先试节点，失败或找不到可滚节点就退回手势。
     * 这跟点击的策略是一致的（先 ACTION_CLICK，不行再坐标）。
     *
     * @return 用了哪种方式；失败返回 null
     */
    fun smartScroll(
        direction: ScrollDirection,
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float = 0.6f,
        onGestureDone: ((Boolean) -> Unit)? = null,
    ): String? {
        // 左右滚动和上下滚动分开判断：可滚节点有水平/垂直之分
        val horizontal = direction == ScrollDirection.LEFT ||
            direction == ScrollDirection.RIGHT

        val node = findMainScrollable(screenWidth, screenHeight)
        if (node != null) {
            // forward 表示"看后面的内容"
            val forward = direction == ScrollDirection.DOWN ||
                direction == ScrollDirection.RIGHT

            if (scrollNode(node, forward)) {
                return if (horizontal) "node-horizontal" else "node"
            }
            // 节点不响应 → 落到下面走手势
        }

        // 退回手势：按屏幕比例算起终点
        val ratio = if (distanceRatio in 0.1f..1.0f) distanceRatio else 0.6f
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val dx = screenWidth * ratio / 2f
        val dy = screenHeight * ratio / 2f

        // 注意方向：说"向下滚动"= 看后面的内容 = 手指向上划
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

    // ------------------------------------------------------------------
    // 手：按坐标手势（支持多指，ADB 做不到）
    // ------------------------------------------------------------------

    /**
     * 按坐标做一个手势。
     *
     * dispatchGesture 是**无障碍相对 ADB 的另一个优势**：它天然支持多指，
     * 而 `input` 命令只支持单指。所以双指缩放、多指手势走这条路。
     *
     * @param points 每根手指的路径。单指就是一条路径
     */
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

    /**
     * 设备真实的长按阈值。
     *
     * 不同 ROM 可能不一样，所以运行时查，不写死。
     * 拖拽判断"按下后多久开始移动"用的就是这个值。
     */
    fun longPressTimeout(): Long = try {
        android.view.ViewConfiguration.getLongPressTimeout().toLong()
    } catch (_: Throwable) {
        500L   // 拿不到就用 AOSP 默认值
    }

    /**
     * 按统一手势规格执行单指手势。
     *
     * 这是所有单指动作的**唯一入口** —— 单击、长按、滑动、甩动、拖拽
     * 都是同一个三段式（按下停顿 / 移动 / 松手前停顿）的参数变体。
     *
     * ## 时序怎么排的
     *
     * StrokeDescription 的构造是 (path, startTime, duration)，
     * 三个参数正好对应三段：
     *
     *     startTime = holdBeforeMoveMs          按下后先不动
     *     duration  = moveMs + restBeforeUpMs   移动 + 停顿
     *
     * **停顿的做法**：路径在终点再补一个相同的点。
     * 这样 duration 变长了，但最后一段时间手指没动 ——
     * 效果就是"停在终点等一会儿再松手"。
     *
     * ## 为什么停顿能让甩动变滑动
     *
     * 系统根据松手前的移动速度决定是否惯性滚动。
     * 终点有停顿时，停顿期间速度归零 → 不触发惯性 → 就是普通滑动。
     * 没有停顿 → 保持速度 → 触发惯性 → 就是甩动。
     */
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

        // 起终点相同就是原地按压（长按），不需要画移动线段
        if (x1 != x2 || y1 != y2) {
            path.lineTo(x2, y2)
        }

        // 停顿靠"在终点加一个重合点"实现，见方法注释
        if (spec.restBeforeUpMs > 0) {
            path.lineTo(x2, y2)
        }

        val strokeDuration = (spec.moveMs + spec.restBeforeUpMs).coerceAtLeast(1)

        val stroke = GestureDescription.StrokeDescription(
            path,
            spec.holdBeforeMoveMs,   // 按下后等这么久才开始移动
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

    /**
     * 单指点击。
     *
     * 走统一手势模型（GestureSpec.TAP）—— 时长要明显小于长按阈值，
     * 否则会被识别成长按。
     */
    fun tapAt(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null) {
        gestureOnPath(x, y, x, y, GestureSpec.TAP, onDone)
    }

    /**
     * 长按。
     *
     * 原地按住不放。下限会 clamp 到长按阈值以上 ——
     * 比阈值短的话系统根本不认这是长按。
     */
    fun longPressAt(
        x: Float, y: Float,
        durationMs: Long = 800,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val safe = durationMs.coerceAtLeast(longPressTimeout() + 100)
        gestureOnPath(x, y, x, y, GestureSpec.longPress(safe), onDone)
    }

    /**
     * 单指滑动（带停顿，不触发惯性）。
     * 需要甩动就用 flickAt，两者区别只在松手前有没有停顿。
     */
    fun swipeAt(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        gestureOnPath(x1, y1, x2, y2,
            GestureSpec.swipe(durationMs), onDone)
    }

    /**
     * 双击。
     *
     * 两次点击，间隔要小于系统双击超时。
     * 太慢会被当成两次独立单击，太快可能被合并。
     */
    fun doubleTapAt(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null) {
        tapAt(x, y) { first ->
            if (!first) {
                onDone?.invoke(false)
                return@tapAt
            }
            // 80ms 间隔：够区分两次，又不会慢到认成单击
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tapAt(x, y, onDone)
            }, 80)
        }
    }

    /** 单指甩动（惯性滑动，划完立刻松手） */
    fun flickAt(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 100,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        gestureOnPath(x1, y1, x2, y2,
            GestureSpec.flick(durationMs), onDone)
    }

    /** 拖拽（按下停顿超过长按阈值再移动） */
    fun dragAt(
        x1: Float, y1: Float, x2: Float, y2: Float,
        moveMs: Long = 500,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        gestureOnPath(x1, y1, x2, y2,
            GestureSpec.drag(longPressTimeout(), moveMs), onDone)
    }

    /** 系统级动作：返回 / 主页 / 多任务 */
    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
}
