package com.aiphone.assistant.agent

import android.graphics.Rect
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证「动作后验证」里的副作用判断 [Agent.hasSideEffect]。
 *
 * 背景：编号点击（模型只给 index、x/y=0）原本拿 (0,0) 做命中测试，会误判
 * 左上角标题/根容器，可能对发送、支付按钮错误放行重试（红线）。这里直接
 * 构造 UiNode，覆盖编号点击、坐标点击、导航键三条路径。
 *
 * 用 Robolectric 提供真实的 android.graphics.Rect，坐标路径的
 * bounds.contains 才能真跑；固定 SDK 34（Rect 逻辑与版本无关）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HasSideEffectTest {

    /** 构造一个可点控件，默认 bounds 任意（编号路径不看坐标） */
    private fun node(
        index: Int,
        text: String = "",
        contentDesc: String = "",
        bounds: Rect = Rect(0, 0, 100, 100),
    ): UiNode = UiNode(
        index = index,
        className = "Button",
        text = text,
        contentDesc = contentDesc,
        viewId = "",
        bounds = bounds,
        clickable = true,
        longClickable = false,
        scrollable = false,
        editable = false,
        enabled = true,
        checked = false,
    )

    // 1. 编号点击敏感的“Delete”按钮、指纹不变 → true（只上报、不重试）
    @Test
    fun `编号点击删除按钮_指纹不变_不重试`() {
        val nodes = listOf(node(5, text = "Delete"))
        assertTrue(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, targetIndex = 5), nodes)
        )
    }

    // 2. 编号点击普通日期、指纹不变 → false（允许重试；真机补测②已 e2e 验证）
    @Test
    fun `编号点击普通日期_指纹不变_允许重试`() {
        val nodes = listOf(node(5, text = "MON, SEP 21"))
        assertFalse(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, targetIndex = 5), nodes)
        )
    }

    // 3. 纯图标按钮只有 contentDesc=“Send” → true
    @Test
    fun `编号点击纯图标发送按钮_走contentDesc_不重试`() {
        val nodes = listOf(node(5, contentDesc = "Send"))
        assertTrue(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, targetIndex = 5), nodes)
        )
    }

    // 4. 列表里没有该编号 → 找不到命中目标，保守当副作用 true
    @Test
    fun `编号点击找不到目标_保守不重试`() {
        val nodes = listOf(node(1, text = "Settings"))
        assertTrue(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, targetIndex = 5), nodes)
        )
    }

    // 5. 坐标点击、命中“发送” → true（坐标路径）
    @Test
    fun `坐标点击发送按钮_不重试`() {
        val nodes = listOf(node(1, text = "发送", bounds = Rect(50, 150, 300, 400)))
        assertTrue(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, x = 100, y = 200), nodes)
        )
    }

    // 6. 坐标点击、命中普通“Alarm” → false（坐标路径、非敏感）
    @Test
    fun `坐标点击普通按钮_允许重试`() {
        val nodes = listOf(node(1, text = "Alarm", bounds = Rect(50, 150, 300, 400)))
        assertFalse(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, x = 100, y = 200), nodes)
        )
    }

    // 7. 返回键无副作用 → false
    @Test
    fun `返回键_无副作用_允许重试`() {
        assertFalse(
            Agent.hasSideEffect(TouchAction(TouchKind.KEY_BACK), emptyList())
        )
    }

    // 8. 英文 Book 不含整词 ok → false（旧的子串匹配会把 book 误判成 ok）
    @Test
    fun `英文Book按钮_不被ok误伤_允许重试`() {
        val nodes = listOf(node(5, text = "Book"))
        assertFalse(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, targetIndex = 5), nodes)
        )
    }

    // 9. 英文 Look 同理 → false
    @Test
    fun `英文Look按钮_不被ok误伤_允许重试`() {
        val nodes = listOf(node(5, text = "Look"))
        assertFalse(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, targetIndex = 5), nodes)
        )
    }

    // 10. 整词 OK 仍是真实的高风险确认按钮 → true
    @Test
    fun `英文OK按钮_整词命中_不重试`() {
        val nodes = listOf(node(5, text = "OK"))
        assertTrue(
            Agent.hasSideEffect(TouchAction(TouchKind.TAP, targetIndex = 5), nodes)
        )
    }
}
