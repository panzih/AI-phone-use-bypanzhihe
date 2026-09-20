package com.aiphone.assistant.display

import android.content.Context
import android.util.Log
import com.aiphone.assistant.shell.AdbShell
import com.aiphone.assistant.shell.ShizukuBridge

/**
 * 虚拟副屏的管理。
 *
 * ## 副屏是怎么建出来的
 *
 * 让 shell 进程（[com.aiphone.assistant.shell.ShellService]，uid=2000）
 * 反射调 `DisplayManager.createVirtualDisplay`，建一块带 TRUSTED 标志的
 * 真副屏。宽高和密度在**服务端运行时读主屏照抄**，跨屏迁移时应用不会被重建。
 *
 * ## displayId 不再靠"猜"
 *
 * 建屏接口直接把系统分配的 displayId 返回（失败为 -1），不用再像旧的
 * `overlay_display_devices` 方案那样建前建后 dump 两次、拿差集猜 id。
 *
 * 界面上仍保留 [refresh]：列出当前所有显示器，方便核对。
 */
object VirtualDisplayManager {

    private const val TAG = "VirtualDisplay"

    data class State(
        /** 现在系统里所有显示器 */
        val displays: List<AdbShell.DisplayInfo> = emptyList(),
        /** 副屏 id（没建成则为 null） */
        val displayId: Int? = null,
        /** 原始的显示器列表，给用户核对 */
        val raw: String = "",
        /** 上一步的结论（成功/失败都说人话） */
        val message: String = "",
    )

    /** 取当前显示器列表（不改变任何东西） */
    suspend fun refresh(context: Context): State {
        val list = AdbShell.listDisplays(context)
        return State(
            displays = list,
            displayId = list.lastOrNull { it.virtual }?.id,
            raw = list.joinToString("\n") { it.label() }.ifBlank { "（没有解析出显示器信息）" },
        )
    }

    /** 建一块照抄主屏的副屏。displayId 由 shell 服务直接返回 */
    suspend fun create(context: Context): State {
        val id = ShizukuBridge.createDisplay(context)
        if (id < 0) {
            val why = ShizukuBridge.lastError ?: "未知原因"
            Log.w(TAG, "建副屏失败：$why")
            return State(message = "副屏没能创建：$why")
        }
        val after = AdbShell.listDisplays(context)
        Log.i(TAG, "副屏已建 id=$id")
        return State(
            displays = after,
            displayId = id,
            raw = after.joinToString("\n") { it.label() },
            message = "副屏已创建，id = $id",
        )
    }

    /** 撤掉副屏 */
    suspend fun remove(context: Context): State {
        val id = ShizukuBridge.destroyDisplay(context)
        val after = AdbShell.listDisplays(context)
        return State(
            displays = after,
            displayId = null,
            raw = after.joinToString("\n") { it.label() },
            message = when (id) {
                -2 -> "撤副屏失败：${ShizukuBridge.lastError ?: "未知原因"}"
                else -> "副屏已撤掉，现在有 ${after.size} 块显示器"
            },
        )
    }

    /** 有没有装 Shizuku、能不能用 */
    fun shizukuState(context: Context): ShizukuBridge.State = ShizukuBridge.state(context)
}
