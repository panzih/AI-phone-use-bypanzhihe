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
 * shell 通道：用 Shizuku 借来的 shell 身份截图、注入触控。
 *
 * 同一个类服务两种用法，**区别只在构造参数**：
 *
 * | | `displayId` | `label` | `tree` |
 * |---|---|---|---|
 * | 副屏（虚拟显示器） | 副屏 id | 「副屏」 | null（副屏读不到控件树） |
 * | **ADB / Shizuku 操作方式** | **0（主屏）** | 「主屏（Shizuku）」 | **无障碍通道** |
 *
 * 主屏那一档是关键：动作和截图走 shell（`screencap -p` / `input tap`），
 * **控件树仍然借无障碍** —— 所以在主屏上这条通道既不丢"元素编号列表"
 * 这个定位主力，又能做到无障碍做不到的两件事：**点得动安全窗口/某些系统弹窗、
 * 截得了安全窗口**（`screencap` 走 shell，不受 FLAG_SECURE 影响）。
 * 没开无障碍时它自动退化成"看截图用坐标"，仍然能跑。
 *
 * ## 它和无障碍通道的根本区别
 *
 * | | 无障碍 | shell（这条） |
 * |---|---|---|
 * | 截图 | `takeScreenshot` | 主屏 `screencap -p` / 副屏 `grabFrame` |
 * | 控件树 | **有**，定位主力 | 主屏借无障碍；副屏**没有** |
 * | 注入 | `dispatchGesture`（节点级/手势级） | `input -d <id>` |
 * | 触控目标 | 只能主屏 | 指定那块屏 |
 *
 * ## 副屏那一档为什么没有控件树
 *
 * `AccessibilityService` 的 `rootInActiveWindow` 和手势注入都只认**默认显示器**，
 * 安卓没有给第三方应用"读副屏无障碍树"的口子。实测依据（0.7.0 探针，AVD API35，
 * TRUSTED 副屏、副屏上 Settings 处于 resumed）：
 * - `getWindows()` 不返回任何副屏窗口（结果全部 displayId=0），拿不到 root；
 * - `dispatchGesture` 手势落到主屏（点击后主屏帧差异 4.27%、副屏 0%），
 *   它没有指定显示器的参数；
 * - 但 `takeScreenshot(displayId)` 能截副屏，结果与 `grabFrame` 逐帧一致
 *   —— 即只有截图能走无障碍，节点 / 手势不行。截图仍统一用 `grabFrame`
 *   （见 capability.canAccessSecureWindow：无障碍截图会过滤 FLAG_SECURE）。
 *
 * 所以副屏那条路**模型拿不到"可点元素的编号列表"**，只能看截图猜坐标 ——
 * 这是它天生的短板，不是实现问题。适合"让 AI 在另一块屏上跑固定流程"，
 * 同时用户还能正常用主屏。
 */
class DisplayChannel(
    private val context: Context,
    val displayId: Int,
    private val size: Pair<Int, Int>,
    /** 给用户看的名字：「副屏」/「主屏（Shizuku）」 */
    private val label: String = "副屏",
    /**
     * 控件树来源（可选）。
     *
     * 主屏（displayId=0）传无障碍通道 —— 于是这条通道也有元素列表；
     * 副屏没有 → null，模型只能看截图猜坐标。借来的通道**不归本类释放**
     * （它由 ChannelController 持有、跨任务复用）。
     */
    private val tree: AccessibilityChannel? = null,
) : DeviceChannel {

    override val displayName: String = label

    /**
     * ⚠️ 这几项是**动态**的：主屏那档借的是无障碍通道，而"借不借得到"取决于
     * 无障碍服务此刻连着没有 —— 用 `tree != null` 判断会撒谎（没开无障碍时
     * 仍然报"能读控件树"，于是 Agent 的"连续读不到树"止损会在第 6 步误杀，
     * 实测踩过）。
     */
    private val a11yOnline: Boolean
        get() = tree != null && com.aiphone.assistant.a11y.AutoService.isConnected

    override val capability: ChannelCapability
        get() = ChannelCapability(
            canScreenshot = true,
            canInjectInput = true,
            // 有控件树可借（主屏 + 无障碍此刻在线）才有"元素列表"这个能力
            canDumpUiTree = a11yOnline,
            // 双指缩放 / 中文输入 shell 的 `input` 都做不到，借无障碍来做
            canMultiTouch = a11yOnline,
            canSetText = a11yOnline,
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
        // 主屏（displayId=0）：`screencap -p` 就是最直接的取图方式，
        // 而且走的是 shell 身份 —— 安全窗口也截得到。
        // 副屏：`screencap -d` 对虚拟屏返回 Status -2、拿不到图，
        // 画面进的是 ImageReader，只能从 shell 服务抓当前帧。
        val bytes = if (displayId == 0) {
            AdbShell.screenshot(context)
        } else {
            // 刚建屏/刚切过来时 ImageReader 里**还没有第一帧** —— VirtualDisplay
            // 是"有内容画上去才出帧"的。立刻抓必然空，别把"还没画出来"报成
            // "屏幕没了"：小等几轮再放弃（副屏真是空的话，这几百毫秒是
            // 该付的代价 —— 那本来就是错误路径）。
            var shot = ShizukuBridge.grabFrame(context)
            var tries = 0
            while (shot.isEmpty() && tries < 4) {
                delay(250)
                shot = ShizukuBridge.grabFrame(context)
                tries++
            }
            shot
        }
        if (bytes.isEmpty()) {
            lastShotError = if (displayId == 0) {
                "抓不到主屏画面，shell 服务可能断开了。"
            } else {
                "抓不到$label（id=$displayId）的画面：要么是刚建屏还没画出第一帧，" +
                    "要么副屏上没有应用在跑。"
            }
            Log.w(TAG, lastShotError!!)
            null
        } else {
            lastShotError = null
            bytes
        }
    }

    /**
     * 控件树交给 [tree]（主屏借无障碍）。
     *
     * 没有可借的（副屏、或用户没开无障碍）时返回 null 而不是空串：
     * Agent 收到 null 时会在提示词里写"这次没能读到控件树，请直接用截图
     * 判断，并用 x/y 给坐标" —— 这正是那条路该走的形态。
     */
    override suspend fun dumpUiTree(): String? = tree?.dumpUiTree()

    /** 借不到控件树时明确回报"根节点拿不到"，而不是一句空文本 */
    override suspend fun readTree(): UiTreeRead = tree?.readTree() ?: UiTreeRead.unavailable()

    override suspend fun currentNodes(): List<com.aiphone.assistant.a11y.UiNode> =
        tree?.currentNodes() ?: emptyList()

    /** 借不到控件树，也就不存在"读到没读到"的区别 —— 一律 null */
    override suspend fun currentNodesOrNull(): List<com.aiphone.assistant.a11y.UiNode>? =
        tree?.currentNodesOrNull()

    override suspend fun perform(
        action: TouchAction,
        onPoint: ((Int, Int) -> Unit)?,
        hint: com.aiphone.assistant.a11y.UiNode?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 可借的无障碍通道：主屏那档才有，而且**必须此刻在线**
            // （没开无障碍时借不到，下面各分支要给出"没有元素列表"的实话）
            val borrow = tree?.takeIf { com.aiphone.assistant.a11y.AutoService.isConnected }

            // 编号类动作：shell 的 `input` 不认识编号，但可以**借无障碍把编号
            // 解析成坐标**（身份匹配那套保护照旧生效），再由 shell 点下去 ——
            // 于是"元素列表"这个定位主力在 ADB 模式下也不丢，
            // 而落点仍然是 shell 注入的（安全窗口也点得动）。
            if (action.targetIndex > 0) {
                if (borrow == null) {
                    return@withContext "$label 没有元素列表，不能用 index。" +
                        "请只看截图、用 x / y 给坐标，不要给 index。"
                }
                // 只有"点/长按/双击"这三种按编号做才有意义；其余（滚动、输入、按键…）
                // 照下面的坐标逻辑走
                when (action.kind) {
                    TouchKind.TAP, TouchKind.LONG_PRESS, TouchKind.DOUBLE_TAP -> {
                        val node = when (val t = borrow.resolveTapTarget(action.targetIndex, hint)) {
                            is AccessibilityChannel.TapTarget.Failed -> return@withContext t.reason
                            is AccessibilityChannel.TapTarget.Found -> t.node
                        }
                        onPoint?.invoke(node.centerX, node.centerY)
                        return@withContext when (action.kind) {
                            TouchKind.TAP -> ok(
                                AdbShell.tap(context, displayId, node.centerX, node.centerY),
                                "点击",
                            )

                            TouchKind.LONG_PRESS -> {
                                val ms = action.durationMs.coerceIn(500, 10_000)
                                ok(
                                    AdbShell.swipe(
                                        context, displayId,
                                        node.centerX, node.centerY,
                                        node.centerX, node.centerY, ms,
                                    ),
                                    "长按",
                                )
                            }

                            else -> {
                                val first =
                                    AdbShell.tap(context, displayId, node.centerX, node.centerY)
                                delay(120)
                                val second =
                                    AdbShell.tap(context, displayId, node.centerX, node.centerY)
                                if (first.contains("错误") || second.contains("错误")) {
                                    "双击失败"
                                } else {
                                    null
                                }
                            }
                        }
                    }

                    else -> Unit
                }
            }

            when (action.kind) {
                // dismiss_dialog 在 Agent 层就转成 TAP，不会走到通道；兜底报错
                TouchKind.DISMISS_DIALOG -> "内部错误：关闭弹窗未在端侧处理"

                // capture 由 Agent 自己处理（截图不走通道），走到这里说明有 bug
                TouchKind.CAPTURE -> "内部错误：截屏不该发到设备通道"

                // 浏览器三动作走 BrowserDriver（Agent 里处理），不该进设备通道
                TouchKind.BROWSER_OPEN, TouchKind.BROWSER_HTML, TouchKind.BROWSER_EVAL ->
                    "内部错误：浏览器动作不该发到设备通道"

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
                        // 中文 shell 的 input 做不到，借无障碍的 ACTION_SET_TEXT
                        if (borrow != null) {
                            borrow.perform(action, onPoint, hint)
                        } else {
                            "$label 上没法输入中文（input text 只支持 ASCII），" +
                                "无障碍又没开着。让用户自己输入，或者到「权限」页把无障碍开上。"
                        }
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
                    // 双指 shell 的 input 做不到（只支持单指），借无障碍的手势模拟
                    if (borrow != null) {
                        borrow.perform(action, onPoint, hint)
                    } else {
                        "$label 上不支持双指手势（input 只能单指）"
                    }

                TouchKind.MULTI_FINGER ->
                    if (borrow != null) {
                        borrow.perform(action, onPoint, hint)
                    } else {
                        "$label 上不支持多指手势"
                    }
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
