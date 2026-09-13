package com.aiphone.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aiphone.assistant.channel.AccessibilityChannel
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.a11y.UiTreeParser
import com.aiphone.assistant.ui.ChannelStatus
import com.aiphone.assistant.ui.PreviewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备通道控制器。
 *
 * 现在只有无障碍一个通道（ADB/Shizuku 方案已按需求移除）。
 * 保留这层是因为：
 *   1. 界面不该直接依赖 AutoService 这种系统组件
 *   2. 将来要加别的通道（比如 ADB 兜底）时，界面代码不用动
 */
class ChannelController(private val context: Context) {

    private var channel: AccessibilityChannel? = null

    val accessibilityChannel: AccessibilityChannel?
        get() = channel

    /**
     * 连接并返回状态。
     *
     * 不抛异常 —— 所有失败都变成 PreviewState.message，
     * 因为界面要显示的是"为什么不能用"，不是一个崩溃。
     */
    suspend fun connect(): PreviewState = withContext(Dispatchers.IO) {
        val ch = channel ?: AccessibilityChannel(context).also { channel = it }

        val problem = ch.probe()
        if (problem != null) {
            return@withContext PreviewState(
                status = ChannelStatus.UNAVAILABLE,
                message = problem,
            )
        }

        val size = ch.screenSize()
        PreviewState(
            status = ChannelStatus.READY,
            screenWidth = size?.first ?: 0,
            screenHeight = size?.second ?: 0,
        )
    }

    /**
     * 抓一帧画面。
     *
     * 重要：**重活放在 IO 线程，状态更新回到调用方线程**。
     *
     * 原来的写法是在 withContext(Dispatchers.IO) 内部直接调 onUpdate，
     * 那是从后台线程写 Compose 状态 —— 更新可能不生效，界面就一直卡在旧状态。
     * 这个坑很隐蔽：日志显示一切正常，但界面不刷新。
     *
     * 这个架构下截图不是为了算坐标，而是给模型理解界面语义。
     * 精确坐标走 UI 控件树，所以截图失败不算致命。
     */
    suspend fun captureFrame(onUpdate: (PreviewState) -> Unit) {
        val ch = channel ?: return

        // 重活：截图 + 解码 + 像素分析，全在 IO 线程
        val next = withContext(Dispatchers.IO) {
            val bytes = ch.screenshot()
            if (bytes == null || bytes.isEmpty()) {
                return@withContext PreviewState(
                    status = ChannelStatus.UNAVAILABLE,
                    message = "截图失败。可能是页面有安全保护（银行/支付类），" +
                        "或者截图太频繁被系统限流。",
                )
            }

            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) {
                return@withContext PreviewState(
                    status = ChannelStatus.UNAVAILABLE,
                    message = "画面解码失败。",
                )
            }

            val size = ch.screenSize()
            PreviewState(
                status = ChannelStatus.READY,
                frame = bmp,
                screenWidth = size?.first ?: bmp.width,
                screenHeight = size?.second ?: bmp.height,
                isProbablySecure = isAllBlack(bmp),
            )
        }

        // 状态更新回到调用方的线程（主线程）
        onUpdate(next)
    }

    /**
     * 读一次控件树并渲染成给模型的文本。
     *
     * 这是本架构的定位主力 —— 模型从这个列表里选编号，
     * 而不是从截图里猜像素。
     */
    suspend fun readUiTree(): String? = withContext(Dispatchers.IO) {
        channel?.dumpUiTree()
    }

    /** 当前能否操作（服务是否连着） */
    val isReady: Boolean get() = AutoService.isConnected

    /**
     * 粗判全黑。
     *
     * 只为给用户一个提示，不参与决策。抽样几百个点，
     * 逐像素扫一张 1080x2400 太慢。
     */
    private fun isAllBlack(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 20).coerceAtLeast(1)
        val stepY = (bmp.height / 20).coerceAtLeast(1)
        var dark = 0
        var total = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val lum = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                if (lum < 8) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && dark.toFloat() / total > 0.98f
    }

    fun release() {
        channel?.release()
    }
}
