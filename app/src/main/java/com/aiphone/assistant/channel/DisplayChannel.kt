package com.aiphone.assistant.channel

import android.content.Context
import android.util.Log
import com.aiphone.assistant.shell.AdbShell
import com.aiphone.assistant.shell.ShizukuBridge
import com.aiphone.assistant.touch.ScrollDirection
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 副屏通道：在虚拟屏幕上截图和注入触控。
 *
 * ## 它和无障碍通道的根本区别
 *
 * | | 无障碍 | 副屏（这条） |
 * |---|---|---|
 * | 截图 | `takeScreenshot` | `screencap -p -d <id>` |
 * | 控件树 | **有**，定位主力 | **没有** |
 * | 注入 | `dispatchGesture`（节点级/手势级） | `input -d <id>` |
 * | 触控目标 | 只能主屏 | 指定那块屏 |
 *
 * **没有控件树这一条影响很大**：模型拿不到"可点元素的编号列表"，
 * 只能看截图猜坐标，或者主动要一张图。准确率会比主屏模式低 ——
 * 这是这条通道天生的短板，不是实现问题。
 *
 * 为什么副屏读不到控件树：`AccessibilityService` 的 `rootInActiveWindow`
 * 和手势注入都只认**默认显示器**，安卓没有给第三方应用"读副屏无障碍树"
 * 的口子。
 *
 * 另外：副屏的**前台应用名读不到**（那也要靠无障碍树）。所以
 * `ChannelController.currentPackage()` 在副屏模式下直接返回 null ——
 * "自己在前台就让位"那个防护在副屏上会自动跳过，这是对的：
 * 副屏上本来就不会有纸盒自己。
 *
 * ## 所以它适合什么
 *
 * 适合"让 AI 在另一块屏上跑固定流程"，同时用户还能正常用主屏。
 * 不适合需要精确定位控件的复杂界面 —— 那种场景留在主屏模式更好。
 */
class DisplayChannel(
    private val context: Context,
    private val displayId: Int,
    private val size: Pair<Int, Int>,
) : DeviceChannel {

    override val displayName: String = "副屏"

    override val capability: ChannelCapability = ChannelCapability(
        canScreenshot = true,
        canInjectInput = true,
        // 副屏读不到无障碍树 —— 这条决定了模型只能用坐标
        canDumpUiTree = false,
        canMultiTouch = false,
        canSetText = false,
        canAccessSecureWindow = true,
        // Shizuku 无 root 时每次重启都要重新授权
        survivesReboot = false,
    )

    /** 上一次截图失败的原因，probe/报错时用 */
    @Volatile
    var lastShotError: String? = null
        private set

    override suspend fun probe(): String? = withContext(Dispatchers.IO) {
        when (ShizukuBridge.state(context)) {
            ShizukuBridge.State.NOT_INSTALLED ->
                "副屏需要 Shizuku，但手机上没装。装好并启动它再用副屏模式。"
            ShizukuBridge.State.NOT_RUNNING ->
                "副屏需要 Shizuku，但它没在运行。打开 Shizuku 启动一次。"
            ShizukuBridge.State.NO_PERMISSION ->
                "还没给纸盒授权。打开 Shizuku 允许纸盒使用。"
            ShizukuBridge.State.READY -> {
                val bound = ShizukuBridge.ensureBound(context)
                when {
                    bound != null -> bound
                    size.first <= 0 || size.second <= 0 -> "拿不到副屏的分辨率。"
                    else -> null
                }
            }
        }
    }

    override suspend fun screenSize(): Pair<Int, Int>? = size

    override suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        val bytes = AdbShell.screenshotDisplay(context, displayId)
        if (bytes.isEmpty()) {
            // 失败要说清楚是哪一种：ROM 不支持、还是屏没了。
            // 只报"截图失败"的话，用户和模型都不知道下一步该干什么
            lastShotError = "截不到副屏 $displayId。可能是这台设备不支持 " +
                "screencap -d，或者副屏已经被撤掉了。"
            Log.w(TAG, lastShotError!!)
            null
        } else {
            lastShotError = null
            bytes
        }
    }

    /**
     * 副屏没有控件树。
     *
     * 返回 null 而不是空串：Agent 收到 null 时会在提示词里写
     * "这次没能读到控件树，请直接用截图判断，并用 x/y 给坐标" ——
     * 这正是副屏模式该走的路。
     */
    override suspend fun dumpUiTree(): String? = null

    override suspend fun currentNodes(): List<com.aiphone.assistant.a11y.UiNode> = emptyList()

    override suspend fun perform(
        action: TouchAction,
        onPoint: ((Int, Int) -> Unit)?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            when (action.kind) {
                TouchKind.TAP -> {
                    onPoint?.invoke(action.x, action.y)
                    ok(AdbShell.tap(context, displayId, action.x, action.y), "点击")
                }

                TouchKind.LONG_PRESS -> {
                    onPoint?.invoke(action.x, action.y)
                    val ms = action.durationMs.coerceIn(500, 10_000)
                    ok(
                        AdbShell.swipe(context, displayId, action.x, action.y, action.x, action.y, ms),
                        "长按",
                    )
                }

                TouchKind.DOUBLE_TAP -> {
                    onPoint?.invoke(action.x, action.y)
                    val first = AdbShell.tap(context, displayId, action.x, action.y)
                    delay(120)
                    val second = AdbShell.tap(context, displayId, action.x, action.y)
                    if (first.contains("错误") || second.contains("错误")) "双击失败" else null
                }

                // 滑动 / 甩动 / 拖拽都落到 input swipe，差别只在时长
                TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG -> {
                    onPoint?.invoke(action.x, action.y)
                    onPoint?.invoke(action.x2, action.y2)
                    val ms = action.durationMs.coerceIn(
                        if (action.kind == TouchKind.FLICK) 50 else 200,
                        10_000,
                    )
                    ok(
                        AdbShell.swipe(
                            context, displayId,
                            action.x, action.y, action.x2, action.y2, ms,
                        ),
                        action.kind.label,
                    )
                }

                /**
                 * 滚动：副屏上**没有可滚动节点**可操作，只能用手势模拟。
                 *
                 * 从屏幕中间往反方向划一段。方向语义和主屏一致：
                 * "向下滚动" = 看后面的内容 = 手指向上划。
                 */
                TouchKind.SCROLL -> {
                    val dir = action.direction ?: ScrollDirection.DOWN
                    val (w, h) = size
                    val cx = w / 2
                    val cy = h / 2
                    val span = (h * 0.35f).toInt()
                    val (x1, y1, x2, y2) = when (dir) {
                        ScrollDirection.DOWN -> intArrayOf(cx, cy + span, cx, cy - span)
                        ScrollDirection.UP -> intArrayOf(cx, cy - span, cx, cy + span)
                        ScrollDirection.LEFT -> intArrayOf(cx + span, cy, cx - span, cy)
                        ScrollDirection.RIGHT -> intArrayOf(cx - span, cy, cx + span, cy)
                    }
                    onPoint?.invoke(x1, y1)
                    ok(
                        AdbShell.swipe(context, displayId, x1, y1, x2, y2, 400),
                        "滚动",
                    )
                }

                TouchKind.INPUT_TEXT -> {
                    // input text 只认 ASCII，中文会静默失败 —— 如实报出来，
                    // 别让模型以为输进去了
                    if (action.text.any { it.code > 127 }) {
                        "副屏上没法输入中文（input text 只支持 ASCII）。" +
                            "这一步请改用主屏模式，或者让用户自己输入。"
                    } else {
                        ok(AdbShell.inputText(context, displayId, action.text), "输入")
                    }
                }

                TouchKind.KEY_BACK -> ok(AdbShell.keyEvent(context, displayId, 4), "返回")
                TouchKind.KEY_HOME -> ok(AdbShell.keyEvent(context, displayId, 3), "主页")
                TouchKind.KEY_RECENTS -> ok(AdbShell.keyEvent(context, displayId, 187), "多任务")

                TouchKind.OPEN_APP -> {
                    val pkg = action.packageName
                    if (pkg.isBlank()) {
                        "打开应用缺少包名"
                    } else {
                        ok(AdbShell.startAppOnDisplay(context, displayId, pkg), "打开应用")
                    }
                }

                TouchKind.WAIT -> {
                    delay(action.durationMs.coerceIn(100, 60_000).toLong())
                    null
                }

                TouchKind.PINCH_OUT, TouchKind.PINCH_IN ->
                    "副屏上不支持双指手势（input 只能单指）"

                TouchKind.MULTI_FINGER -> "副屏上不支持多指手势"
            }
        } catch (t: Throwable) {
            "副屏操作失败：${t.javaClass.simpleName} ${t.message}"
        }
    }

    override fun release() {
        // 没有需要释放的本地资源：所有东西都跑在 shell 进程那边
    }

    /** 命令输出里带"错误：/Exception"就算失败 */
    private fun ok(out: String, what: String): String? =
        if (out.contains("错误：") || out.contains("Exception") || out.contains("Error")) {
            "$what 失败：${out.take(120)}"
        } else {
            null
        }

    private companion object {
        const val TAG = "DisplayChannel"
    }
}
