package com.aiphone.assistant

import android.content.Context
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.channel.AccessibilityChannel
import com.aiphone.assistant.channel.DeviceChannel
import com.aiphone.assistant.channel.DisplayChannel
import com.aiphone.assistant.channel.UiTreeRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备通道控制器。
 *
 * 四条通道（见 [Mode]）：无障碍（默认）、ADB/Shizuku 主屏、副屏、内置浏览器。
 * 保留这层是因为：
 *   1. 界面不该直接依赖 AutoService 这种系统组件
 *   2. 上层（Agent、技能）只认这一层的接口，换通道不用改调用方
 *
 * ## 为什么不返回"状态对象"了
 *
 * 原来这里返回一个 PreviewState（连接中 / 就绪 / 不可用），
 * 那是为了驱动主界面中间那块预览区。现在预览区去掉了 ——
 * 走无障碍时被操作的 App 就在用户眼前，应用里再显示一份截图没有意义。
 *
 * 所以这一层回归到最朴素的形态：**要什么给什么，失败返回原因字符串**。
 * 状态判断（授权没授权）由界面直接问 AutoService.isConnected。
 */
class ChannelController(private val context: Context) {

    /**
     * 操作目标在哪块屏上、动作从哪儿走。
     *
     *   ACCESSIBILITY   主屏，走无障碍（默认，一次授权长期有效）
     *   ADB_MAIN        主屏，走 Shizuku 的 shell（用户在「操作方式」里选了
     *                   ADB / Shizuku）；控件树仍借无障碍，没开也能跑
     *   VIRTUAL_DISPLAY 副屏，同样走 shell（没有控件树）
     *
     * 上层（Agent、技能）不需要知道用的是哪条 —— 它们只调
     * [probe]/[screenSize]/[readUiTree]/[captureFrame]/[execute]，
     * 由这里决定命令发到哪儿。
     */
    enum class Mode { ACCESSIBILITY, ADB_MAIN, VIRTUAL_DISPLAY, BROWSER }

    private var accessibility: AccessibilityChannel? = null
    private var display: DisplayChannel? = null

    /** 内置浏览器通道（0.10.1 T43）。由 Agent 在模型下发 browser_open 时建起来 */
    private var browser: com.aiphone.assistant.channel.BrowserChannel? = null

    /** 主屏 shell 通道（ADB / Shizuku 操作方式） */
    private var adbMain: DisplayChannel? = null

    /**
     * 用户在「权限 → 操作方式」里选的是不是 ADB / Shizuku。
     *
     * 单独存一个布尔而不是直接复用 [mode]：主屏 shell 是**懒建**的
     * （建它要问一次屏幕尺寸），而 mode 只有进/出副屏时才动。
     */
    @Volatile
    private var preferredAdb = false

    /** 任务开始前由界面按设置同步一次 */
    fun preferAdbMode(on: Boolean) {
        if (preferredAdb == on) return
        preferredAdb = on
        android.util.Log.i("ChannelController", if (on) "操作方式：ADB / Shizuku" else "操作方式：无障碍")
    }

    @Volatile
    private var mode: Mode = Mode.ACCESSIBILITY

    val isVirtualDisplay: Boolean get() = mode == Mode.VIRTUAL_DISPLAY && display != null

    /**
     * 是不是在「内置浏览器」模式。
     *
     * 这个模式下所有设备操作都打在网页上：控件树 = DOM 摘要、点击 = 点 DOM 元素。
     * 上层（Agent）用它决定两件事：
     *   1. 不要自动迁副屏（把浏览器 Activity 搬到副屏没有任何意义）；
     *   2. "前台是纸盒自己"的告警不成立（浏览器本来就是我们的 Activity）。
     */
    val isBrowser: Boolean get() = mode == Mode.BROWSER && browser != null

    /**
     * 当前通道**能不能读到控件树**。
     *
     * Agent 用它决定"连续读不到树要不要止损"：副屏、以及没开无障碍的 ADB 模式
     * 本来就读不到，那不是环境坏了，不该按失败计数。
     */
    val canDumpUiTree: Boolean get() = ensureChannel().capability.canDumpUiTree

    /**
     * 副屏 displayId；不在副屏模式时为 null。
     * 回迁第一步先把它抓进局部变量 —— [exitVirtualDisplay] 会把 display 置 null，
     * 之后就读不到了。
     */
    val virtualDisplayId: Int?
        get() = if (mode == Mode.VIRTUAL_DISPLAY) display?.displayId else null

    /**
     * 切到副屏模式。
     *
     * @param displayId 由 VirtualDisplayManager 建屏后推断出来的 id
     */
    /** 暴露 context：ShizukuBridge 是 object、调用它的方法要 context */
    val appContext: android.content.Context get() = context

    fun enterVirtualDisplay(displayId: Int, size: Pair<Int, Int>) {
        display?.release()
        display = DisplayChannel(context, displayId, size)
        mode = Mode.VIRTUAL_DISPLAY
        android.util.Log.i("ChannelController", "切到副屏模式：id=$displayId 分辨率=$size")
    }

    /**
     * 切到内置浏览器通道。
     *
     * 由 Agent 在模型下发 `browser_open` 之后调用 —— 也就是说
     * **要不要用浏览器是云端模型决定的**（端侧不替它选信息源）。
     */
    fun enterBrowser() {
        browser?.release()
        browser = com.aiphone.assistant.channel.BrowserChannel(context)
        mode = Mode.BROWSER
        android.util.Log.i("ChannelController", "切到内置浏览器通道")
    }

    /** 退出浏览器模式（浏览器 Activity 关掉时调） */
    fun exitBrowser() {
        browser?.release()
        browser = null
        if (mode == Mode.BROWSER) mode = Mode.ACCESSIBILITY
        android.util.Log.i("ChannelController", "退出内置浏览器通道")
    }

    /** 切回主屏 */
    fun exitVirtualDisplay() {
        display?.release()
        display = null
        mode = Mode.ACCESSIBILITY
        android.util.Log.i("ChannelController", "切回主屏模式")
    }

    /**
     * 拿到当前通道。
     *
     * 副屏没设置好时**回退到无障碍**而不是抛异常 —— 宁可走错通道让用户
     * 看见主屏在被操作，也不要因为一处状态不同步就整个崩掉。
     */
    fun ensureChannel(): DeviceChannel = when {
        mode == Mode.BROWSER ->
            browser ?: com.aiphone.assistant.channel.BrowserChannel(context).also { browser = it }
        isVirtualDisplay -> display!!
        preferredAdb -> adbMain ?: buildAdbMain()
        else -> accessibility ?: AccessibilityChannel(context).also { accessibility = it }
    }

    /**
     * 主屏 shell 通道。
     *
     * 尺寸走 `DisplayManager.getDisplay(0)` 的 real size —— 不能拿应用自己的
     * 窗口尺寸：窗口可能被分屏/挖孔裁过，而 `input tap` 收的是**整屏坐标**。
     */
    private fun buildAdbMain(): DisplayChannel {
        val size = runCatching {
            val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            val d = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            d?.getRealSize(p)
            if (p.x > 0 && p.y > 0) p.x to p.y else 0 to 0
        }.getOrDefault(0 to 0).let { if (it.first > 0) it else fallbackSize() }

        val tree = accessibility ?: AccessibilityChannel(context).also { accessibility = it }
        return DisplayChannel(
            context = context,
            displayId = android.view.Display.DEFAULT_DISPLAY,
            size = size,
            label = "主屏（Shizuku）",
            tree = tree,
        ).also {
            adbMain = it
            android.util.Log.i("ChannelController", "启用 ADB / Shizuku 通道：主屏 $size")
        }
    }

    private fun fallbackSize(): Pair<Int, Int> = runCatching {
        val m = context.resources.displayMetrics
        (m.widthPixels to m.heightPixels).takeIf { it.first > 0 && it.second > 0 }
    }.getOrNull() ?: (1080 to 1920)

    /**
     * 探测能不能用。
     *
     * @return null 表示可用；否则是给用户看的中文原因
     */
    suspend fun probe(): String? = withContext(Dispatchers.IO) { ensureChannel().probe() }

    /** 屏幕分辨率，坐标换算要用 */
    suspend fun screenSize(): Pair<Int, Int>? =
        withContext(Dispatchers.IO) { ensureChannel().screenSize() }

    /**
     * 读一次控件树，返回压缩后的文本列表。
     *
     * 这是本架构的定位主力 —— 模型从这个列表里选编号，
     * 而不是从截图里猜像素。
     *
     * ⚠️ 返回类型是 [UiTreeRead] 而不是 `String?`：文本在没有元素时是一句
     * 占位语，按行数会数成"1 个元素"，把"无障碍没工作"和"这一屏没有控件"
     * 混成同一件事（0.8.5 日志里整整一批失败都是这么来的）。
     */
    suspend fun readUiTree(): UiTreeRead =
        withContext(Dispatchers.IO) { ensureChannel().readTree() }

    /**
     * 结构化节点列表（带 viewId / bounds）。
     *
     * 给宏技能回放用 —— 它需要按 viewId 或文字在当前界面上找回
     * 录制时点的那个控件。
     */
    suspend fun parseNodes(): List<com.aiphone.assistant.a11y.UiNode> =
        withContext(Dispatchers.IO) { ensureChannel().currentNodes() }

    /**
     * 和 [parseNodes] 一样，但**读不到控件树时返回 null**。
     *
     * 判"动作前后界面变没变"必须用它：空列表的指纹是固定值，会让
     * "界面没变化"永远成立，于是每次点击都被误判成没点中并重试。
     */
    suspend fun parseNodesOrNull(): List<com.aiphone.assistant.a11y.UiNode>? =
        withContext(Dispatchers.IO) { ensureChannel().currentNodesOrNull() }

    /**
     * 截一帧，返回 PNG 字节。
     *
     * 用途变了：不是给用户看预览，而是
     *   1. 存进本次运行的日志目录（排查用）
     *   2. 将来发给多模态模型理解界面语义
     */
    suspend fun captureFrame(): ByteArray? =
        withContext(Dispatchers.IO) { ensureChannel().screenshot() }

    /** 最后一次截图失败的原因，null 表示上次成功或还没失败过 */
    fun lastScreenshotError(): String? {
        val ch = ensureChannel()
        return when (ch) {
            is com.aiphone.assistant.channel.AccessibilityChannel -> ch.lastScreenshotError
            is com.aiphone.assistant.channel.DisplayChannel -> ch.lastShotError
            else -> null
        }
    }

    /** 当前能否操作（服务是否真的连着，不是"设置里开着"） */
    /**
     * 当前通道能不能真的用。
     *
     * 主屏看无障碍是否连着；副屏看 Shizuku 那边 —— 两者的"就绪"
     * 根本不是一回事，所以必须按模式分开判断。
     */
    val isReady: Boolean
        get() = if (isBrowser) {
            com.aiphone.assistant.browser.BrowserDriver.isAlive()
        } else if (isVirtualDisplay) {
            com.aiphone.assistant.shell.ShizukuBridge.hasPermission()
        } else {
            AutoService.isConnected
        }

    /**
     * 执行一个动作。
     *
     * @return null 表示成功，否则是给用户看的中文失败原因
     */
    suspend fun execute(
        action: com.aiphone.assistant.touch.TouchAction,
        onPoint: ((Int, Int) -> Unit)? = null,
        hint: com.aiphone.assistant.a11y.UiNode? = null,
    ): String? = withContext(Dispatchers.IO) { ensureChannel().perform(action, onPoint, hint) }

    /**
     * 当前前台应用的包名。
     *
     * 用来判断"我们是不是自己在前台" —— 那种情况下截图拍到的是
     * 纸盒自己的界面，发给模型会误导它去点我们自己的按钮。
     */
    suspend fun currentPackage(): String? = withContext(Dispatchers.IO) {
        // 副屏上的前台应用读不到（那要靠无障碍树），返回 null 让上层
        // 跳过"自己在前台就让位"那个判断 —— 副屏上本来也不会有我们自己
        // 浏览器模式下前台就是纸盒自己的 BrowserActivity —— 那个包名对上层没有意义，
        // 上层拿它只会得出"纸盒自己在前台，去用 open_app"的错误结论
        if (isBrowser) null else if (isVirtualDisplay) null else AutoService.get()?.currentPackage()
    }

    fun release() {
        // 浏览器是我们自己拉起来的 Activity，任务结束要关掉 —— 否则用户
        // 会看到一个停在半路的网页窗口，而且下次任务还会误判成"浏览器还在"
        if (mode == Mode.BROWSER) {
            runCatching { com.aiphone.assistant.browser.BrowserDriver.close() }
        }
        browser?.release()
        browser = null
        accessibility?.release()
        accessibility = null
        display?.release()
        display = null
        adbMain = null
        mode = Mode.ACCESSIBILITY
    }
}
