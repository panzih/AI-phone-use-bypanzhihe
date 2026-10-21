package com.aiphone.assistant.channel

import android.content.Context
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.browser.BrowserDom
import com.aiphone.assistant.browser.BrowserDriver
import com.aiphone.assistant.touch.TouchAction

/**
 * 内置浏览器通道。
 *
 * ## 它和别的通道的根本不同
 *
 * 无障碍 / shell 通道操作的是"别的应用"，界面元素由系统给；浏览器通道
 * 操作的是**我们自己拉起来的 WebView**，元素由页面 DOM 给。于是能力表
 * 有一处关键差异：canAccessSecureWindow = false（WebView 本来就不该碰
 * 银行 App 的安全窗口），canMultiTouch = false（网页里没有双指缩放语义，
 * 页面缩放已关闭）。
 *
 * ## 为什么 screenshot 用 WebView.draw 而不是系统截屏
 *
 * 这条通道的卖点就是"完全不依赖无障碍/Shizuku"。draw 只画 WebView
 * 自己的内容，截出来的图和元素 bounds 天然对齐（同一个左上角原点），
 * 模型看图给出的坐标可以直接用。代价是截不到工具条 —— 而工具条本来
 * 也没有操作价值。
 */
class BrowserChannel(private val context: Context) : DeviceChannel {

    override val displayName: String = "内置浏览器"

    override val capability: ChannelCapability = ChannelCapability(
        canScreenshot = true,
        canInjectInput = true,
        canDumpUiTree = true,
        // 网页没有系统级多指手势；页面缩放也关了
        canMultiTouch = false,
        // 用原生 setter 灌值，中文/emoji 都没问题
        canSetText = true,
        // WebView 的内容本来就不是安全窗口；也不该被用来碰银行页面
        canAccessSecureWindow = false,
        // 每次都要重新打开 Activity，不存在"重启自恢复"
        survivesReboot = false,
    )

    override suspend fun probe(): String? =
        if (BrowserDriver.isAlive()) null else "内置浏览器没在运行"

    override suspend fun screenSize(): Pair<Int, Int>? = BrowserDriver.size()

    override suspend fun screenshot(): ByteArray? = BrowserDriver.screenshot()

    /**
     * 读控件树。
     *
     * 读不到页面时必须返回 [UiTreeRead.unavailable]（text=null、
     * rootAvailable=false），**不能**返回一句占位文本 —— 否则上层
     * "读不到树就带截图"的分支永远走不到，模型既没列表也没图。
     */
    override suspend fun readTree(): UiTreeRead {
        val snapshot = BrowserDriver.snapshot(BrowserDriver.TREE_LIMIT)
            ?: return UiTreeRead.unavailable()
        return UiTreeRead(
            // 页面读到了但没有可交互元素，也给 null：让"没有列表"在类型上
            // 是个明确分支，而不是让模型去数一行占位语
            text = if (snapshot.nodes.isEmpty()) null else BrowserDom.render(snapshot),
            nodes = snapshot.nodes,
            rootAvailable = true,
            rawNodeCount = snapshot.total,
            truncated = snapshot.truncated,
        )
    }

    override suspend fun dumpUiTree(): String? = readTree().text

    override suspend fun currentNodes(): List<UiNode> =
        BrowserDriver.snapshot(BrowserDriver.TREE_LIMIT)?.nodes ?: emptyList()

    override suspend fun currentNodesOrNull(): List<UiNode>? {
        val r = readTree()
        return if (r.rootAvailable) r.nodes else null
    }

    override suspend fun perform(
        action: TouchAction,
        onPoint: ((Int, Int) -> Unit)?,
        hint: UiNode?,
    ): String? = BrowserDriver.perform(action, onPoint, hint)

    override fun release() {
        // 浏览器 Activity 的生命周期归任务收尾时的 BrowserDriver.close() 管，
        // 这里不做任何事：通道被换掉不等于应该把用户正在看的页面关掉
    }

}
