package com.aiphone.assistant.channel

import com.aiphone.assistant.touch.TouchAction

/**
 * 设备控制通道。
 *
 * 现在只有无障碍一个实现。保留这层抽象是为了：
 *   1. 界面不直接依赖 AutoService 这种系统组件，方便测试和替换
 *   2. 将来若要加别的通道（比如 ADB 兜底），界面代码不用动
 *
 * 所有坐标都是**真实屏幕像素**。归一化坐标的换算在更上层做，
 * 这一层不掺和，避免两处都换算导致算两次。
 */
interface DeviceChannel {

    /** 显示给用户看的名字 */
    val displayName: String

    /** 通道能力，界面据此决定显示什么 */
    val capability: ChannelCapability

    /**
     * 检查通道是否可用。
     * 返回 null 表示可用，返回字符串表示不可用的原因（能直接显示给用户）。
     */
    suspend fun probe(): String?

    /** 屏幕分辨率。坐标换算必须用真实值 */
    suspend fun screenSize(): Pair<Int, Int>?

    /** 截取屏幕。不支持时返回 null */
    suspend fun screenshot(): ByteArray?

    /**
     * 读 UI 控件树，返回给模型看的文本。
     *
     * 这是本项目的定位主力：模型从编号列表里选，而不是从截图里猜像素。
     */
    suspend fun dumpUiTree(): String?

    /**
     * 当前的控件节点列表（带 viewId / bounds）。
     *
     * 和 [dumpUiTree] 的区别：那个是**给模型看的文本**，这个是**给程序用的
     * 结构化节点**。宏技能回放要靠 viewId / 文字在界面上找回同一个控件，
     * 文本列表里没有这些字段。
     */
    suspend fun currentNodes(): List<com.aiphone.assistant.a11y.UiNode>

    /**
     * 执行一个触控动作。返回 null 表示成功。
     *
     * @param onPoint 上报这次动作**真正落在**的屏幕坐标（按编号点击时
     *                取节点的中心）。用来在屏幕上闪一圈水波给用户看，
     *                所以只有通道层知道这个值 —— 模型给的编号在解析成
     *                坐标之前是看不出落点的
     */
    suspend fun perform(action: TouchAction, onPoint: ((Int, Int) -> Unit)? = null): String?

    /** 释放资源 */
    fun release()
}

/**
 * 通道能力声明。
 *
 * 做成数据类而不是一串 Boolean，是为了能一次打印出来对比 ——
 * 排查"为什么这个功能在这个通道下不好使"时很有用。
 */
data class ChannelCapability(
    /** 能不能截屏（决定中间那块能不能显示预览画面） */
    val canScreenshot: Boolean,
    /** 能不能注入点击/滑动 */
    val canInjectInput: Boolean,
    /** 能不能读 UI 控件树 */
    val canDumpUiTree: Boolean,
    /** 多指手势支持吗（双指缩放靠它） */
    val canMultiTouch: Boolean,
    /** 能不能直接灌文本（绕开中文输入的坑） */
    val canSetText: Boolean,
    /** 能不能操作安全窗口（银行密码键盘那种） */
    val canAccessSecureWindow: Boolean,
    /** 重启后是否自动恢复，不用重新授权 */
    val survivesReboot: Boolean,
) {
    fun describe(): String = buildString {
        append("截图=").append(if (canScreenshot) "是" else "否")
        append(" 注入=").append(if (canInjectInput) "是" else "否")
        append(" UI树=").append(if (canDumpUiTree) "是" else "否")
        append(" 多指=").append(if (canMultiTouch) "是" else "否")
        append(" 灌文本=").append(if (canSetText) "是" else "否")
        append(" 安全窗口=").append(if (canAccessSecureWindow) "是" else "否")
        append(" 重启自恢复=").append(if (survivesReboot) "是" else "否")
    }

    companion object {
        /**
         * 无障碍的能力。
         *
         * 选它而不是 ADB 的核心原因：**survivesReboot = true**。
         * Shizuku 在无 root 手机上每次重启都要重新用无线调试启动，
         * 无障碍授权一次就常驻。
         *
         * 代价是 canAccessSecureWindow = false（截不到银行密码键盘）,
         * 以及截图有平台限流。对一个每步 3-8 秒的 AI 循环来说限流不是瓶颈。
         */
        val ACCESSIBILITY = ChannelCapability(
            canScreenshot = true,
            canInjectInput = true,
            canDumpUiTree = true,
            canMultiTouch = true,
            canSetText = true,
            canAccessSecureWindow = false,
            survivesReboot = true,
        )
    }
}
