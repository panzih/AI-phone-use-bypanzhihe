package com.aiphone.assistant.a11y

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 钉住"编号漂移"时的身份找回规则 [NodeLocator]。
 *
 * 背景（0.8.5 日志）：模型看到的是**第 N 步开头**那一份元素列表，动作真正
 * 执行是在一次网络往返之后。这中间界面重排（桌面图标加载完、列表滚动）
 * 之后，旧实现直接点"第 30 个" —— 点到了别的控件，日志里只留下一句
 * "已执行但界面没变化"，根本查不出来。高德、中信证券都踩到了。
 *
 * 用 Robolectric 提供真实的 android.graphics.Rect。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeLocatorTest {

    private fun node(
        index: Int,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        cls: String = "Button",
        cx: Int = 100,
        cy: Int = 200,
    ) = UiNode(
        index = index,
        className = cls,
        text = text,
        contentDesc = desc,
        viewId = viewId,
        bounds = Rect(cx - 50, cy - 20, cx + 50, cy + 20),
        clickable = true,
        longClickable = false,
        scrollable = false,
        editable = false,
        enabled = true,
        checked = false,
    )

    @Test
    fun `资源 id 相同就算同一个控件哪怕编号完全变了`() {
        val hint = node(30, text = "高德地图", viewId = "com.miui.home:id/icon")
        val now = listOf(
            node(1, text = "相机", viewId = "com.miui.home:id/other"),
            node(2, text = "高德地图", viewId = "com.miui.home:id/icon", cx = 400, cy = 900),
        )
        val m = NodeLocator.locate(hint, now, screenWidth = 1220)
        assertNotNull(m)
        assertEquals(2, m!!.node.index)
        assertTrue(m.reason.contains("资源id"))
    }

    @Test
    fun `文字相同可以跨编号找回`() {
        val hint = node(7, text = "开始导航")
        val now = listOf(node(3, text = "开始导航", cx = 848, cy = 2604))
        val m = NodeLocator.locate(hint, now, screenWidth = 1220)
        assertNotNull(m)
        assertEquals(3, m!!.node.index)
    }

    @Test
    fun `只有位置接近但没有文字或 id 时不能认定`() {
        // 列表里相邻的行位置差不多 —— 这正是"点错控件"的来源，必须判丢
        val hint = node(5, text = "张三", cls = "TextView")
        val now = listOf(node(9, text = "李四", cls = "TextView"))
        assertNull(NodeLocator.locate(hint, now, screenWidth = 1220))
    }

    @Test
    fun `目标已经不在界面上时返回 null 而不是随便挑一个`() {
        val hint = node(30, text = "高德地图", viewId = "com.miui.home:id/icon")
        val now = listOf(node(1, text = "微信"), node(2, text = "设置"))
        assertNull(NodeLocator.locate(hint, now, screenWidth = 1220))
    }

    @Test
    fun `空的一帧直接判丢`() {
        val hint = node(1, text = "确定")
        assertNull(NodeLocator.locate(hint, emptyList(), screenWidth = 1220))
    }

    @Test
    fun `类名加位置也能兜住没有文字的自绘控件`() {
        val hint = node(2, cls = "FrameLayout", cx = 600, cy = 1300)
        val now = listOf(node(5, cls = "FrameLayout", cx = 604, cy = 1305))
        val m = NodeLocator.locate(hint, now, screenWidth = 1220)
        assertNotNull(m)
        assertEquals(5, m!!.node.index)
    }

    @Test
    fun `位置离得太远时类名相同也不算`() {
        val hint = node(2, cls = "FrameLayout", cx = 100, cy = 100)
        val now = listOf(node(5, cls = "FrameLayout", cx = 900, cy = 2400))
        assertNull(NodeLocator.locate(hint, now, screenWidth = 1220))
    }

    @Test
    fun `多个候选时选分最高的那个`() {
        // 两个都叫"导航"，但只有一个是同一个 viewId
        val hint = node(9, text = "导航", viewId = "com.x:id/nav_btn")
        val now = listOf(
            node(3, text = "导航", viewId = "com.x:id/other"),
            node(8, text = "导航", viewId = "com.x:id/nav_btn", cx = 300, cy = 700),
        )
        val m = NodeLocator.locate(hint, now, screenWidth = 1220)
        assertNotNull(m)
        assertEquals(8, m!!.node.index)
    }

    @Test
    fun `容差按屏宽缩放 最小 24 像素`() {
        assertEquals(24, NodeLocator.tolerance(600))
        assertEquals(40, NodeLocator.tolerance(2000))
    }
}
