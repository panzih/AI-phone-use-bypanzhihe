package com.aiphone.assistant.channel

import com.aiphone.assistant.touch.TouchAction

interface DeviceChannel {
    val displayName: String
    val capability: ChannelCapability
    suspend fun probe(): String?
    suspend fun screenSize(): Pair<Int, Int>?
    suspend fun screenshot(): ByteArray?
    suspend fun dumpUiTree(): String?
    suspend fun perform(action: TouchAction): String?
    fun release()
}

data class ChannelCapability(
    val canScreenshot: Boolean,
    val canInjectInput: Boolean,
    val canDumpUiTree: Boolean,
    val canMultiTouch: Boolean,
    val canSetText: Boolean,
    val canAccessSecureWindow: Boolean,
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
        val ACCESSIBILITY = ChannelCapability(
            canScreenshot = true, canInjectInput = true, canDumpUiTree = true,
            canMultiTouch = true, canSetText = true, canAccessSecureWindow = false,
            survivesReboot = true,
        )
    }
}
