package com.aiphone.assistant.agent

import android.graphics.Rect
import com.aiphone.assistant.a11y.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证端侧「关闭弹窗」意图执行器 [LocalRuleEngine.findSafeDismiss]：
 * 只在云端显式下发（dismiss_dialog）时才被调用，找到安全关闭按钮就返回它、
 * 找不到（或页面涉及授权/支付）就返回 null，端侧不自主决策。
 *
 * 用 Robolectric 提供真实的 android.graphics.Rect；固定 SDK 34。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalRuleEngineTest {

    /** 构造一个控件，默认可点 */
    private fun node(
        index: Int,
        text: String = "",
        clickable: Boolean = true,
    ): UiNode = UiNode(
        index = index,
        className = "Button",
        text = text,
        contentDesc = "",
        viewId = "",
        bounds = Rect(0, 0, 100, 100),
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = false,
        enabled = true,
        checked = false,
    )

    private val engine = LocalRuleEngine()

    // 1. 强弹窗词「以后再说」→ 直接命中
    @Test
    fun `强弹窗词以后再说_命中`() {
        val nodes = listOf(node(1, "以后再说"))
        assertEquals(1, engine.findSafeDismiss(nodes).target?.index)
    }

    // 2. 稀疏弹窗只有「取消」→ 通用词命中
    @Test
    fun `稀疏弹窗取消_命中`() {
        val nodes = listOf(node(1, "标题", clickable = false), node(2, "取消"))
        assertEquals(2, engine.findSafeDismiss(nodes).target?.index)
    }

    // 3. 复杂页面（>18 节点）有「取消」→ 通用词不自动点
    @Test
    fun `复杂页面取消_不命中`() {
        val nodes = (1..19).map { node(it, "条目$it", clickable = false) } +
            node(20, "取消")
        assertNull(engine.findSafeDismiss(nodes).target)
    }

    // 4. 权限弹窗「允许 / 拒绝」→ 有授权按钮，不自动关闭
    @Test
    fun `权限弹窗允许拒绝_不自动关闭`() {
        val nodes = listOf(node(1, "允许"), node(2, "拒绝"))
        assertNull(engine.findSafeDismiss(nodes).target)
    }

    // 5. 权限弹窗「允许 / 以后再说」→ 即使有强弹窗词，也不自动关闭
    @Test
    fun `权限弹窗允许以后再说_不自动关闭`() {
        val nodes = listOf(node(1, "允许"), node(2, "以后再说"))
        assertNull(engine.findSafeDismiss(nodes).target)
    }

    // 6. 升级弹窗「立即升级 / 以后再说」（无授权支付按钮）→ 点以后再说，防过度收紧
    @Test
    fun `升级弹窗立即升级以后再说_点以后再说`() {
        val nodes = listOf(node(1, "立即升级"), node(2, "以后再说"))
        assertEquals(2, engine.findSafeDismiss(nodes).target?.index)
    }

    // 7. 没有任何关闭按钮 → 不命中
    @Test
    fun `无关闭按钮_不命中`() {
        val nodes = listOf(node(1, "设置"), node(2, "完成"))
        assertNull(engine.findSafeDismiss(nodes).target)
    }

    // 8. 英文稀疏弹窗「Not Now」→ 命中
    @Test
    fun `英文NotNow_命中`() {
        val nodes = listOf(node(1, "Not Now"))
        assertEquals(1, engine.findSafeDismiss(nodes).target?.index)
    }
}
