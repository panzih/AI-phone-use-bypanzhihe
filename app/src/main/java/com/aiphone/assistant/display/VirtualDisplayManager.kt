package com.aiphone.assistant.display

import android.content.Context
import android.util.Log
import com.aiphone.assistant.shell.AdbShell
import com.aiphone.assistant.shell.ShizukuBridge

/**
 * 虚拟副屏的管理。
 *
 * ## 它是怎么建出来的
 *
 * 走系统设置项 `overlay_display_devices`（`AdbShell.createVirtualDisplay`），
 * 不是 `DisplayManager.createVirtualDisplay` —— 后者要 signature 级权限，
 * shell 身份也拿不到。
 *
 * ## 为什么 id 要靠"猜"
 *
 * 系统没有提供"我刚建的那块屏 id 是几"这种查询。`dumpsys display` 里
 * 能拿到显示器列表和名字，但 id 和列表顺序的对应关系**各 ROM 不一致**。
 *
 * 所以这里的策略是：
 *
 *   1. 建之前先记一份显示器列表，建之后再取一份，**多出来的那块**当作副屏
 *   2. 同时把原始输出保留下来，界面上让用户能核对
 *   3. 界面上提供**手动填 id** 的入口 —— 万一推断错了，用户看一眼
 *      dumpsys 就能自己指定
 *
 * 第 3 条不是偷懒：这块的解析规则我没法在所有 ROM 上验证，
 * 而"给用户一个自己纠正的口子"比"假装推断一定对"要诚实得多。
 */
object VirtualDisplayManager {

    private const val TAG = "VirtualDisplay"

    /** 副屏分辨率。和主屏一致最省事，应用在副屏上的布局也最接近真机 */
    const val DEFAULT_WIDTH = 1080
    const val DEFAULT_HEIGHT = 2400
    const val DEFAULT_DPI = 440

    data class State(
        /** 现在系统里所有显示器 */
        val displays: List<AdbShell.DisplayInfo> = emptyList(),
        /** 推断出来的副屏 id */
        val displayId: Int? = null,
        /** 上一次操作的原始输出，给用户核对 */
        val raw: String = "",
        /** 上一步的结论（成功/失败都说人话） */
        val message: String = "",
    )

    /** 建副屏之前记下的列表，用来做差集 */
    private var baseline: List<AdbShell.DisplayInfo> = emptyList()

    /** 取当前状态（不改变任何东西） */
    suspend fun refresh(context: Context): State {
        val list = AdbShell.listDisplays(context)
        return State(
            displays = list,
            displayId = pickVirtual(list),
            raw = list.joinToString("\n") { it.label() }.ifBlank { "（没有解析出显示器信息）" },
        )
    }

    /**
     * 建一块副屏。
     *
     * 建之前先取一次列表当基线 —— 差值法是这里唯一靠得住的判断方式。
     */
    suspend fun create(
        context: Context,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI,
    ): State {
        baseline = runCatching { AdbShell.listDisplays(context) }.getOrDefault(emptyList())
        Log.i(TAG, "建副屏前有 ${baseline.size} 块显示器")

        val out = AdbShell.createVirtualDisplay(context, width, height, dpi)
        // 系统建屏要一点时间，立刻 dump 可能还没出现
        kotlinx.coroutines.delay(2500)

        val after = AdbShell.listDisplays(context)
        val added = after.filter { now -> baseline.none { it.name == now.name } }
        val id = added.lastOrNull()?.id ?: pickVirtual(after)

        return State(
            displays = after,
            displayId = id,
            raw = after.joinToString("\n") { it.label() },
            message = when {
                out.contains("错误：") -> out
                id == null -> "命令执行了，但没能从 dumpsys 里认出副屏的 id。" +
                    "请把下面的原始输出发我，或者手动填一个 id 试试。"
                else -> "副屏已创建，推断 id = $id（新增 ${added.size} 块）"
            },
        )
    }

    suspend fun remove(context: Context): State {
        val out = AdbShell.removeVirtualDisplay(context)
        kotlinx.coroutines.delay(1500)
        baseline = emptyList()
        val after = AdbShell.listDisplays(context)
        return State(
            displays = after,
            displayId = null,
            raw = after.joinToString("\n") { it.label() },
            message = if (out.contains("错误：")) out else "副屏已撤掉，现在有 ${after.size} 块显示器",
        )
    }

    /**
     * 挑出副屏。
     *
     * 优先"虚拟"标记；没有标记就用差值；再不行返回 null（不猜）。
     */
    private fun pickVirtual(list: List<AdbShell.DisplayInfo>): Int? {
        if (baseline.isNotEmpty()) {
            list.lastOrNull { now -> baseline.none { it.name == now.name } }?.let { return it.id }
        }
        return list.lastOrNull { it.virtual }?.id
    }

    /** 有没有装 Shizuku、能不能用 */
    fun shizukuState(context: Context): ShizukuBridge.State = ShizukuBridge.state(context)
}
