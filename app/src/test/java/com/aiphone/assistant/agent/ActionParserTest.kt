package com.aiphone.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * screenshot_after 解析与边界。
 *
 * 核心循环（Agent）依赖通道、不好在 JVM 单测，但解析层只用到 org.json；
 * 用 Robolectric 提供真实 org.json（否则 android.jar 里是 stub），
 * 把"模型给的 JSON 会被归一成什么"在这里钉死。固定 SDK 34。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionParserTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun screenshotAfter_withActions_isKept() {
        val p = ActionParser.parse(
            """{"thought":"点通知后会弹框","screenshot_after":true,
               "actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertTrue(p.screenshotAfter)
        assertFalse(p.needImage)
        assertEquals(1, p.actions.size)
        assertFalse(p.finished)
        assertNull(p.warning)
    }

    @Test
    fun screenshotAfter_withNeedImage_needImageWins() {
        val p = ActionParser.parse(
            """{"thought":"两个都给了","need_image":true,"screenshot_after":true,
               "actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertTrue(p.needImage)
        assertFalse(p.screenshotAfter)
    }

    @Test
    fun screenshotAfter_withoutActions_fallsBackToNeedImage() {
        // 只说"动作后给图"却没动作：意图就是要图，退化成立即要图，
        // 不该落到"空转"警告
        val p = ActionParser.parse(
            """{"thought":"想看新画面","screenshot_after":true}""",
            w, h,
        )
        assertTrue(p.needImage)
        assertFalse(p.screenshotAfter)
        assertTrue(p.actions.isEmpty())
        assertNull(p.warning)
    }

    @Test
    fun screenshotAfter_withSkillButNoActions_keepsSkill() {
        // 没动作但同时在要技能：走技能，不退化成立即要图
        val p = ActionParser.parse(
            """{"thought":"先查应用","screenshot_after":true,"use_skill":"list_apps"}""",
            w, h,
        )
        assertEquals("list_apps", p.skillId)
        assertFalse(p.needImage)
        assertFalse(p.screenshotAfter)
    }

    @Test
    fun normalActions_defaultScreenshotAfterFalse() {
        val p = ActionParser.parse(
            """{"thought":"点一下","actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertFalse(p.screenshotAfter)
        assertFalse(p.needImage)
    }

    // ---- finished 与动作同批：修复"动作被跳过"的回归用例 ----

    @Test
    fun finished_withActions_keepsBoth() {
        // 被修场景：同一批既给动作又说 finished（语义"做完这批就完成"）。
        // 动作必须保留、finished 也为 true，由 Agent 先执行动作再收尾。
        val p = ActionParser.parse(
            """{"thought":"做完这批就完成","actions":[{"action":"tap","index":3}],
               "finished":true,"summary":"已完成"}""",
            w, h,
        )
        assertEquals(1, p.actions.size)
        assertTrue(p.finished)
    }

    @Test
    fun finished_withoutActions_staysEmpty() {
        // 原行为：无动作直接说完成 → actions 空、finished true（立即收尾），别改坏
        val p = ActionParser.parse(
            """{"thought":"无需动作即完成","finished":true}""",
            w, h,
        )
        assertTrue(p.actions.isEmpty())
        assertTrue(p.finished)
    }

    @Test
    fun actions_withoutFinished_notFinished() {
        // 回归：给动作但不说完成 → finished false
        val p = ActionParser.parse(
            """{"thought":"先点一下","actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertEquals(1, p.actions.size)
        assertFalse(p.finished)
    }
}
