package com.aiphone.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作环境卡（ROADMAP §0 / agent-loop.md 5.1、5.3）。
 *
 * 固定四行、位置固定 —— 这是模型判断"我在哪、拿到了什么"的唯一入口；
 * 阈值（5/10）决定要不要劝模型换视图，多劝是干扰，少劝是坑。
 */
class EnvCardTest {

    @Test
    fun `环境卡固定四行`() {
        val card = AgentPrompt.envCard(
            mode = "操作软件（无障碍）",
            screen = "前台（主屏）",
            input = "控件树 42 项",
            nextOptions = "控件树 / 截图 / 两者",
        )
        val lines = card.lines()
        assertEquals("# 操作环境", lines[0])
        assertEquals("模式：操作软件（无障碍）", lines[1])
        assertEquals("屏幕：前台（主屏）", lines[2])
        assertEquals("本轮输入：控件树 42 项", lines[3])
        assertTrue(lines[4].startsWith("下一轮可选："))
    }

    @Test
    fun `view_image 的阈值建议`() {
        // ≥10：多到值得建议换树
        val many = AgentPrompt.viewImageOnlyReason(count = 12, rootAvailable = true)
        assertTrue(many.contains("12 个控件"))
        assertTrue(many.contains("view"))
        // <5：少到只能按坐标点
        val few = AgentPrompt.viewImageOnlyReason(count = 3, rootAvailable = true)
        assertTrue(few.contains("3 个控件"))
        assertTrue(few.contains("坐标"))
        assertFalse(
            "中间地带不该劝换视图",
            AgentPrompt.viewImageOnlyReason(7, true).contains("\"view\""),
        )
        // 读不到根节点：不报数（别把"看不到"说成"空页"）
        val blind = AgentPrompt.viewImageOnlyReason(count = 0, rootAvailable = false)
        assertFalse(blind.contains("个控件"))
        assertTrue(blind.contains("读不到"))
    }

    @Test
    fun `stepMessage 里环境卡在当前状态之后、界面元素之前`() {
        val msg = AgentPrompt.stepMessage(
            step = 2,
            maxSteps = 30,
            task = "打开蓝牙",
            screenWidth = 1080,
            screenHeight = 2400,
            foregroundPackage = "com.android.settings",
            uiTree = "[1] Button \"蓝牙\" @(100,300) [可点]",
            envCard = AgentPrompt.envCard("操作软件（无障碍）", "前台（主屏）", "控件树 1 项", "控件树 / 截图"),
        )
        val st = msg.indexOf("# 当前状态")
        val env = msg.indexOf("# 操作环境")
        val ui = msg.indexOf("# 界面元素")
        assertTrue(st in 0 until env && env < ui)
    }
}
