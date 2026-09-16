package com.aiphone.assistant

import android.content.Context
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.channel.AccessibilityChannel
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

    private var channel: AccessibilityChannel? = null

    /** 拿到通道实例（懒建），给需要执行动作的上层用 */
    fun ensureChannel(): AccessibilityChannel =
        channel ?: AccessibilityChannel(context).also { channel = it }

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
     * 截一帧，返回 PNG 字节。
     *
     * 用途变了：不是给用户看预览，而是
     *   1. 存进本次运行的日志目录（排查用）
     *   2. 将来发给多模态模型理解界面语义
     */
    suspend fun captureFrame(): ByteArray? =
        withContext(Dispatchers.IO) { ensureChannel().screenshot() }

    /** 当前能否操作（服务是否真的连着，不是"设置里开着"） */
    val isReady: Boolean get() = AutoService.isConnected

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
    suspend fun currentPackage(): String? =
        withContext(Dispatchers.IO) { AutoService.get()?.currentPackage() }

    fun release() {
        channel?.release()
        channel = null
    }
}
