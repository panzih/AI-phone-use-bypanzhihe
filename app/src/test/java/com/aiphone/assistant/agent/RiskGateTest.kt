package com.aiphone.assistant.agent

import android.graphics.Rect
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 高风险动作闸门的纯函数测试。
 *
 * 背景（2026-10-27 评估 P0-4）：屏幕/网页内容是模型输入的一部分，
 * 恶意页面可以诱导模型去点支付/删除。这里钉住"执行层能识别"，
 * 以及"确认输出无法解析时按不通过处理"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RiskGateTest {

    private fun node(text: String, index: Int = 1) = UiNode(
        index = index,
        className = "Button",
        text = text,
        contentDesc = "",
        viewId = "",
        bounds = Rect(0, 0, 100, 100),
        clickable = true,
        longClickable = false,
        scrollable = false,
        editable = false,
        enabled = true,
        checked = false,
    )

    private fun tap(label: String, index: Int = 1): Pair<TouchAction, UiNode> =
        TouchAction(kind = TouchKind.TAP, targetIndex = index) to node(label, index)

    @Test
    fun `支付转账删除发送授权都会命中`() {
        listOf("确认支付", "立即转账", "删除记录", "发送消息", "授权登录").forEach { label ->
            val (action, target) = tap(label)
            assertNotNull("应命中：$label", AgentLimits.highRiskReason(action, listOf(target), target))
        }
    }

    @Test
    fun `通用确认按钮不算高风险`() {
        listOf("确定", "确认", "好的", "完成", "保存").forEach { label ->
            val (action, target) = tap(label)
            assertNull("不应命中：$label", AgentLimits.highRiskReason(action, listOf(target), target))
        }
    }

    @Test
    fun `英文按整词匹配，book 不会命中 ok`() {
        val (bookAction, book) = tap("book")
        assertNull(AgentLimits.highRiskReason(bookAction, listOf(book), book))

        val (payAction, pay) = tap("Pay now")
        assertNotNull(AgentLimits.highRiskReason(payAction, listOf(pay), pay))
    }

    @Test
    fun `输入 4 到 8 位数字按验证码处理`() {
        val target = node("验证码", index = 3)
        val action = TouchAction(kind = TouchKind.INPUT_TEXT, targetIndex = 3, text = "123456")
        assertNotNull(AgentLimits.highRiskReason(action, listOf(target), target))

        val normal = TouchAction(kind = TouchKind.INPUT_TEXT, targetIndex = 3, text = "你好")
        assertNull(AgentLimits.highRiskReason(normal, listOf(target), target))
    }

    @Test
    fun `获取验证码按钮不算高风险`() {
        val (action, target) = tap("获取验证码")
        assertNull(AgentLimits.highRiskReason(action, listOf(target), target))
    }

    @Test
    fun `二次确认只认 CONFIRM 或 ABORT，其它一律解析失败`() {
        assertEquals(true, RiskReview.parse("CONFIRM: 用户明确要求转账")?.confirmed)
        assertEquals(false, RiskReview.parse("ABORT: 任务里没有要求")?.confirmed)
        assertNull(RiskReview.parse("好的，可以执行"))
        assertNull(RiskReview.parse(""))
        assertTrue(RiskReview.parse("confirm：没空格")?.confirmed == true)
        assertFalse(RiskReview.parse("ABORT")?.confirmed ?: true)
    }
}
