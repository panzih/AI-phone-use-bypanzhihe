package com.aiphone.assistant.browser

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.touch.ScrollDirection
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 内置浏览器的驱动入口。
 *
 * ## 定位
 *
 * 网页和原生界面最大的不同：原生界面能读系统的控件树，网页的 DOM 只能
 * 在页面里用 JS 读。所以这一层只做三件事：
 *   1. 保证 Activity / WebView 活着，并把命令排到主线程（evaluateJavascript
 *      规定必须在主线程）
 *   2. 把"要点击第 N 个 / 输入文字 / 滚动"翻译成一段 JS
 *   3. 失败时给**中文原因**给上层回灌，而不是返回 null 让模型瞎猜
 *
 * ## 并发约定
 *
 * Activity 可能随时被用户关掉。所有入口都先确认 Activity 还活着，
 * 再 await 页面加载；任何一步失败都写进 [lastError]，由 Agent 拼进日志。
 */
object BrowserDriver {

    private const val NOT_RUNNING = "内置浏览器没在运行"

    /** 单页最多展示多少个可交互元素。和 AccessibilityChannel.TREE_LIMIT 对齐 */
    const val TREE_LIMIT = 60

    @Volatile
    private var activity: BrowserActivity? = null

    @Volatile
    private var lastErrorText: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** Activity 在 onCreate 里登记自己；只有登记过才能被驱动 */
    fun attach(activity: BrowserActivity) {
        this.activity = activity
    }

    /** Activity 销毁时摘掉自己，避免驱动继续往一个死窗口发命令 */
    fun detach(activity: BrowserActivity) {
        if (this.activity === activity) this.activity = null
    }

    /**
     * 打开内置浏览器并加载 url（空串 = 空白页）。
     *
     * 会等到页面加载完成才返回成功 —— 上层紧接着就会读页面元素，
     * 提前返回只会让第一次读取必然撞上"还在加载"。
     */
    suspend fun open(context: Context, url: String): String? {
        lastErrorText = null

        // 解析层已经校验过一次；这里再校验一次，保证任何调用方都绕不过去。
        val target = when (val r = BrowserUrl.normalize(url)) {
            is BrowserUrl.Result.Ok -> r.url
            is BrowserUrl.Result.Rejected -> return fail("不打开这个地址：${r.reason}")
        }

        // 旧窗口先关掉，避免两个 WebView 同时挂着一份页面
        val old = activity
        if (old != null) {
            withContext(Dispatchers.Main) { runCatching { old.finish() } }
            activity = null
            delay(120)
        }

        val intent = Intent(context, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_URL, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            withContext(Dispatchers.Main) { context.startActivity(intent) }
        } catch (t: Throwable) {
            return fail("打开内置浏览器失败：" + (t.message ?: t.javaClass.simpleName))
        }

        // Activity 的 onCreate 是异步的，等它把自己 attach 上来
        val deadline = System.currentTimeMillis() + 8_000
        var host: BrowserActivity? = null
        while (System.currentTimeMillis() < deadline) {
            host = activity
            if (host != null) break
            delay(50)
        }
        if (host == null) {
            return fail("内置浏览器没能打开（系统可能限制了后台启动界面）")
        }

        val err = host.awaitReady(10_000)
        return if (err != null) fail(err) else null
    }

    /** 浏览器 Activity 是否还活着（能被驱动） */
    fun isAlive(): Boolean = activity?.isAliveNow() == true

    /** 关闭浏览器。用 Handler 投到主线程，因为 finish() 必须在主线程 */
    fun close() {
        val a = activity ?: return
        mainHandler.post { runCatching { a.finish() } }
    }

    /** 最近一次失败原因，给 Agent 拼进日志 / 回灌给模型 */
    fun lastError(): String? = lastErrorText

    // ------------------------------------------------------------------
    // 页面读取
    // ------------------------------------------------------------------

    /**
     * 取页面 HTML。
     *
     * @param selector 为空取 document.documentElement.outerHTML；否则 querySelector
     * @param maxChars >0 时在**页面里**就截断，避免把几 MB 的 HTML 先搬回内存
     */
    suspend fun html(selector: String?, maxChars: Int): String? {
        val host = requireReady() ?: return null
        host.prepareHelper()

        val sel = selector?.trim().orEmpty()
        val max = if (maxChars > 0) maxChars else 0
        val source = "(function(){ var sel=" + BrowserJs.jsString(sel) + "; var max=" + max + ";" +
            "var el=sel?document.querySelector(sel):document.documentElement;" +
            "if(!el) return {ok:false,reason:'页面里找不到选择器 '+sel};" +
            "var h=el.outerHTML||''; if(max>0) h=h.slice(0,max);" +
            "return {ok:true,html:h};})()"

        return when (val r = execJs(host, source, 8_000)) {
            is Js.Fail -> fail(r.reason)
            is Js.Ok -> {
                val m = parseMap(r.out)
                if (m == null) {
                    fail("页面返回的 HTML 数据格式不对")
                } else if (m["ok"] != true) {
                    fail((m["reason"] as? String) ?: "读取页面 HTML 失败")
                } else {
                    m["html"] as? String
                }
            }
        }
    }

    /** 执行一段 JS，返回结果转成的字符串（对象用 JSON.stringify）。null=失败 */
    suspend fun eval(js: String): String? {
        val host = requireReady() ?: return null
        host.prepareHelper()
        return when (val r = execJs(host, js, 5_000)) {
            is Js.Fail -> fail(r.reason)
            is Js.Ok -> r.out
        }
    }

    // ------------------------------------------------------------------
    // 控件树（DOM）
    // ------------------------------------------------------------------

    /** 取一份页面快照。null = 读不到（Activity 不在 / JS 没就绪 / 页面刚跳到一半） */
    internal suspend fun snapshot(limit: Int = TREE_LIMIT): BrowserDom.Snapshot? {
        val host = requireReady() ?: return null
        host.prepareHelper()
        val width = host.viewWidth()
        if (width <= 0) {
            fail("拿不到浏览器窗口宽度（页面可能还没排版）")
            return null
        }

        return when (val r = execJs(host, "__zh__.snapshot(" + limit + "," + width + ")", 8_000)) {
            is Js.Fail -> {
                fail(r.reason)
                null
            }
            is Js.Ok -> BrowserDom.parseSnapshot(r.out) ?: run {
                fail("页面返回的元素数据格式不对")
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // 截图 / 尺寸
    // ------------------------------------------------------------------

    internal suspend fun screenshot(): ByteArray? {
        val host = activity
        if (host == null || !host.isAliveNow()) {
            fail(NOT_RUNNING)
            return null
        }
        val bytes = host.captureJpeg()
        if (bytes == null) {
            fail("截取浏览器画面失败（窗口还没准备好或已被销毁）")
            return null
        }
        lastErrorText = null
        return bytes
    }

    internal suspend fun size(): Pair<Int, Int>? {
        val host = activity ?: return null
        val (w, h) = host.webViewSize()
        return if (w > 0 && h > 0) w to h else null
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    /**
     * 执行一个触控动作。
     *
     * 浏览器里没有系统级的触摸注入语义，所以全部翻译成 JS：
     *   - 点编号 → 在页面里按身份找回元素再 .click()
     *   - 点坐标 → document.elementFromPoint().click()
     *   - 输入   → 原生 setter + input/change 事件（受控组件才认）
     *   - 滚动   → window.scrollBy
     *
     * 找不到目标时**必须如实失败**。网页重排比原生更频繁（骨架屏、无限列表），
     * 拿"第 N 个"直接点一定会点错，而且点错通常没有任何可见反馈。
     */
    internal suspend fun perform(
        action: TouchAction,
        onPoint: ((Int, Int) -> Unit)?,
        hint: UiNode?,
    ): String? {
        val host = requireReady() ?: return lastErrorText
        host.prepareHelper()
        val width = host.viewWidth().coerceAtLeast(1)

        return try {
            when (action.kind) {
                TouchKind.CAPTURE -> fail("内部错误：截屏不该发到设备通道")

                TouchKind.DISMISS_DIALOG -> fail("内部错误：关闭弹窗未在端侧处理")

                // 这三条是"浏览器会话"级动作，由 Agent 直接调 BrowserDriver
                // 的 open/html/eval，不走 perform —— 走到这里说明路由错了
                TouchKind.BROWSER_OPEN -> fail("内部错误：打开浏览器不该作为触控动作下发")
                TouchKind.BROWSER_HTML -> fail("内部错误：读网页源码不该作为触控动作下发")
                TouchKind.BROWSER_EVAL -> fail("内部错误：执行 JS 不该作为触控动作下发")

                TouchKind.TAP -> {
                    if (action.targetIndex > 0) {
                        val seq = seqOf(hint, action.targetIndex)
                        val want = wantText(hint)
                        pointResult(
                            host,
                            execJs(host, "__zh__.click(" + seq + "," + BrowserJs.jsString(want) + "," + width + ")"),
                            onPoint,
                        )
                    } else {
                        pointResult(
                            host,
                            execJs(host, "__zh__.clickPoint(" + action.x + "," + action.y + "," + width + ")"),
                            onPoint,
                        )
                    }
                }

                TouchKind.LONG_PRESS -> {
                    val seq = seqOf(hint, action.targetIndex)
                    val want = wantText(hint)
                    pointResult(
                        host,
                        execJs(host, "__zh__.longpress(" + seq + "," + BrowserJs.jsString(want) + "," + width + ")"),
                        onPoint,
                    )
                }

                TouchKind.DOUBLE_TAP -> {
                    val seq = seqOf(hint, action.targetIndex)
                    val want = wantText(hint)
                    pointResult(
                        host,
                        execJs(host, "__zh__.dblclick(" + seq + "," + BrowserJs.jsString(want) + "," + width + ")"),
                        onPoint,
                    )
                }

                TouchKind.INPUT_TEXT -> inputText(host, action, hint, width)

                TouchKind.SCROLL -> {
                    val dir = action.direction ?: ScrollDirection.DOWN
                    val (_, h) = host.webViewSize()
                    val base = if (h > 0) h else 1080
                    val ratio = if (action.distanceRatio > 0f) action.distanceRatio else 0.7f
                    val amount = (base * ratio).toInt().coerceAtLeast(1)
                    val dx = when (dir) {
                        ScrollDirection.DOWN, ScrollDirection.UP -> 0
                        ScrollDirection.LEFT -> -amount
                        ScrollDirection.RIGHT -> amount
                    }
                    val dy = when (dir) {
                        ScrollDirection.DOWN -> amount
                        ScrollDirection.UP -> -amount
                        ScrollDirection.LEFT, ScrollDirection.RIGHT -> 0
                    }
                    simpleResult(execJs(host, "__zh__.scrollPx(" + dx + "," + dy + "," + width + ")"))
                }

                // 浏览器没有"手指路径"语义，统一按起终点位移滚动页面。
                // 方向和原生一致：手指往上滑 = 看后面的内容 = scrollBy 正数
                TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG -> {
                    val dx = action.x - action.x2
                    val dy = action.y - action.y2
                    if (dx == 0 && dy == 0) {
                        fail("滑动需要起终点坐标，浏览器里没法用别的方式推断方向")
                    } else {
                        simpleResult(execJs(host, "__zh__.scrollPx(" + dx + "," + dy + "," + width + ")"))
                    }
                }

                TouchKind.KEY_BACK -> simpleResult(execJs(host, "__zh__.back()"))

                TouchKind.WAIT -> {
                    delay(action.durationMs.coerceIn(100, 10_000).toLong())
                    null
                }

                TouchKind.PINCH_IN, TouchKind.PINCH_OUT -> fail("浏览器通道不支持双指缩放")

                TouchKind.MULTI_FINGER -> fail("浏览器通道不支持多指手势")

                TouchKind.KEY_HOME -> fail("浏览器通道不支持主页键，请关闭浏览器回到桌面")

                TouchKind.KEY_RECENTS -> fail("浏览器通道不支持多任务键")

                TouchKind.OPEN_APP -> fail("浏览器通道不负责打开应用")
            }
        } catch (t: Throwable) {
            fail("浏览器操作失败：" + t.javaClass.simpleName + " " + (t.message ?: ""))
        }
    }

    /**
     * 输入文字。
     *
     * 必须把结果**回读**出来核对：受控组件（React/Vue）收到 input 事件后
     * 可能因为校验/格式化改掉内容，只信"事件发出去了"会重演无障碍通道
     * "模型以为输入了、其实没输入"的老问题。
     */
    private suspend fun inputText(
        host: BrowserActivity,
        action: TouchAction,
        hint: UiNode?,
        width: Int,
    ): String? {
        if (action.text.isEmpty()) return null
        val seq = if (action.targetIndex > 0) seqOf(hint, action.targetIndex) else 0
        val want = wantText(hint)
        val r = execJs(
            host,
            "__zh__.type(" + seq + "," + BrowserJs.jsString(want) + "," +
                BrowserJs.jsString(action.text) + "," + width + ")",
        )
        when (r) {
            is Js.Fail -> return fail(r.reason)
            is Js.Ok -> {
                val m = parseMap(r.out) ?: return fail("页面返回的输入结果格式不对")
                if (m["ok"] != true) return fail((m["reason"] as? String) ?: "输入失败")
                val actual = m["text"] as? String ?: ""
                return when {
                    actual.contains(action.text) -> null
                    actual.isNotBlank() ->
                        "输入「" + action.text.take(20) + "」没成功：输入框里现在是「" +
                            actual.take(20) + "」。请重新操作。"
                    else ->
                        "已经尝试写入「" + action.text.take(20) + "」，但这个输入框读不回内容，" +
                            "无法确认是否真的输入成功。请截图确认一下再决定下一步。"
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private sealed class Js {
        data class Ok(val out: String) : Js()
        data class Fail(val reason: String) : Js()
    }

    /** 执行并解析桥返回的 {ok,out|error} 信封 */
    private suspend fun execJs(host: BrowserActivity, source: String, timeoutMs: Long = 5_000): Js {
        val raw = host.runJs(source, timeoutMs)
            ?: return Js.Fail("执行 JS 超时（页面可能卡住了，或者正在跳转）")
        val envelope = runCatching { BrowserJson.parse(raw) }.getOrNull() as? Map<*, *>
            ?: return Js.Fail("浏览器返回了无法识别的结果")
        if (envelope["ok"] != true) {
            return Js.Fail((envelope["error"] as? String) ?: "JS 执行出错")
        }
        return Js.Ok(envelope["out"] as? String ?: "")
    }

    /** 带落点的动作：成功时把元素中心（换算成整块屏幕坐标）报给水波纹 */
    private suspend fun pointResult(host: BrowserActivity, js: Js, onPoint: ((Int, Int) -> Unit)?): String? {
        when (js) {
            is Js.Fail -> return fail(js.reason)
            is Js.Ok -> {
                val m = parseMap(js.out) ?: return fail("页面返回的操作结果格式不对")
                if (m["ok"] != true) return fail((m["reason"] as? String) ?: "操作失败")
                val x = (m["x"] as? Number)?.toInt() ?: 0
                val y = (m["y"] as? Number)?.toInt() ?: 0
                if (x > 0 || y > 0) {
                    // JS 给的坐标以 WebView 左上角为原点；水波纹画在整块屏幕上，
                    // 要加上工具条和状态栏的高度，否则会偏到看不见的地方
                    val (ox, oy) = host.locationOnScreen()
                    onPoint?.invoke(x + ox, y + oy)
                }
                lastErrorText = null
                return null
            }
        }
    }

    private fun simpleResult(js: Js): String? = when (js) {
        is Js.Fail -> fail(js.reason)
        is Js.Ok -> {
            val m = parseMap(js.out)
            if (m != null && m["ok"] != true) {
                fail((m["reason"] as? String) ?: "操作失败")
            } else {
                lastErrorText = null
                null
            }
        }
    }

    private fun parseMap(json: String): Map<*, *>? =
        runCatching { BrowserJson.parse(json) }.getOrNull() as? Map<*, *>

    /** hint 里的 viewId 是 web:<序号>；hint 为空时退回模型给的原编号 */
    private fun seqOf(hint: UiNode?, fallback: Int): Int {
        val vid = hint?.viewId ?: return fallback
        return vid.removePrefix("web:").toIntOrNull() ?: fallback
    }

    /** 元素身份线索：优先文字，其次无障碍描述 */
    private fun wantText(hint: UiNode?): String {
        val t = hint?.text?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return hint?.contentDesc?.trim().orEmpty()
    }

    /** 确认 Activity 活着、页面加载完，否则返回 null 并写好 lastError */
    private suspend fun requireReady(timeoutMs: Long = 8_000): BrowserActivity? {
        val host = activity
        if (host == null || !host.isAliveNow()) {
            lastErrorText = NOT_RUNNING
            return null
        }
        val err = host.awaitReady(timeoutMs)
        if (err != null) {
            lastErrorText = err
            return null
        }
        return host
    }

    private fun fail(reason: String): String {
        lastErrorText = reason
        return reason
    }
}
