package com.aiphone.assistant

import android.content.Context
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.channel.AccessibilityChannel
import com.aiphone.assistant.channel.DeviceChannel
import com.aiphone.assistant.channel.DisplayChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备通道控制器。
 *
 * 现在只有无障碍一个通道（ADB/Shizuku 方案已按需求移除）。
 * 保留这层是因为：
 *   1. 界面不该直接依赖 AutoService 这种系统组件
 *   2. 将来要加别的通道（比如 ADB 兜底）时，界面代码不用动
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
     * 操作目标在哪块屏上。
     *
     *   ACCESSIBILITY   主屏，走无障碍（默认，能力最全）
     *   VIRTUAL_DISPLAY 副屏，走 Shizuku 的 shell 命令（没有控件树）
     *
     * 上层（Agent、技能）不需要知道用的是哪条 —— 它们只调
     * [probe]/[screenSize]/[readUiTree]/[captureFrame]/[execute]，
     * 由这里决定命令发到哪儿。
     */
    enum class Mode { ACCESSIBILITY, VIRTUAL_DISPLAY }

    private var accessibility: AccessibilityChannel? = null
    private var display: DisplayChannel? = null

    @Volatile
    private var mode: Mode = Mode.ACCESSIBILITY

    val isVirtualDisplay: Boolean get() = mode == Mode.VIRTUAL_DISPLAY && display != null

    /**
     * 切到副屏模式。
     *
     * @param displayId 由 VirtualDisplayManager 建屏后推断出来的 id
     */
    fun enterVirtualDisplay(displayId: Int, size: Pair<Int, Int>) {
        display?.release()
        display = DisplayChannel(context, displayId, size)
        mode = Mode.VIRTUAL_DISPLAY
        android.util.Log.i("ChannelController", "切到副屏模式：id=$displayId 分辨率=$size")
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
    fun ensureChannel(): DeviceChannel =
        if (isVirtualDisplay) {
            display!!
        } else {
            accessibility ?: AccessibilityChannel(context).also { accessibility = it }
        }

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
     */
    suspend fun readUiTree(): String? =
        withContext(Dispatchers.IO) { ensureChannel().dumpUiTree() }

    /**
     * 结构化节点列表（带 viewId / bounds）。
     *
     * 给宏技能回放用 —— 它需要按 viewId 或文字在当前界面上找回
     * 录制时点的那个控件。
     */
    suspend fun parseNodes(): List<com.aiphone.assistant.a11y.UiNode> =
        withContext(Dispatchers.IO) { ensureChannel().currentNodes() }

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
        get() = if (isVirtualDisplay) {
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
    ): String? = withContext(Dispatchers.IO) { ensureChannel().perform(action, onPoint) }

    /**
     * 当前前台应用的包名。
     *
     * 用来判断"我们是不是自己在前台" —— 那种情况下截图拍到的是
     * 纸盒自己的界面，发给模型会误导它去点我们自己的按钮。
     */
    suspend fun currentPackage(): String? = withContext(Dispatchers.IO) {
        // 副屏上的前台应用读不到（那要靠无障碍树），返回 null 让上层
        // 跳过"自己在前台就让位"那个判断 —— 副屏上本来也不会有我们自己
        if (isVirtualDisplay) null else AutoService.get()?.currentPackage()
    }

    fun release() {
        accessibility?.release()
        accessibility = null
        display?.release()
        display = null
        mode = Mode.ACCESSIBILITY
    }
}
