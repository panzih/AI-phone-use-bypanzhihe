package com.aiphone.assistant.agent

import com.aiphone.assistant.touch.TouchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 解析层：capture / screenshot_after / need_image / 等待时长的归一化与边界。
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

    // ---- capture：动作序列里的截屏 ----

    @Test
    fun capture_inActions_keepsPosition() {
        // 夹在两步点击中间：位置必须保留（截的是"中间那一步"的画面）
        val p = ActionParser.parse(
            """{"thought":"点完弹窗想看一眼","actions":[
                {"action":"tap","index":3},{"action":"capture"},{"action":"tap","index":5}]}""",
            w, h,
        )
        assertEquals(3, p.actions.size)
        assertEquals(TouchKind.TAP, p.actions[0].kind)
        assertEquals(TouchKind.CAPTURE, p.actions[1].kind)
        assertEquals(TouchKind.TAP, p.actions[2].kind)
        assertFalse(p.needImage)
        assertNull(p.warning)
    }

    @Test
    fun capture_atEnd_isKept() {
        val p = ActionParser.parse(
            """{"actions":[{"action":"tap","index":3},{"action":"capture"}]}""",
            w, h,
        )
        assertEquals(2, p.actions.size)
        assertEquals(TouchKind.CAPTURE, p.actions[1].kind)
    }

    @Test
    fun screenshotInsideActions_becomesCapture() {
        // 老写法（把 screenshot 当动作名塞进 actions）→ 归一成 CAPTURE 动作，
        // 而不是"立即要图"，免得同一件事有两种表达
        val p = ActionParser.parse(
            """{"actions":[{"action":"screenshot"}]}""",
            w, h,
        )
        assertEquals(1, p.actions.size)
        assertEquals(TouchKind.CAPTURE, p.actions[0].kind)
        assertFalse(p.needImage)
    }

    @Test
    fun screenshotAtTopLevel_isNeedImage() {
        // 顶层老格式仍然是"立即要图"：那一轮不算一步，会带上截图重问
        val p = ActionParser.parse("""{"thought":"看不懂","action":"screenshot"}""", w, h)
        assertTrue(p.needImage)
        assertTrue(p.actions.isEmpty())
    }

    // ---- screenshot_after：兼容别名 → 末尾追加一个 capture ----

    @Test
    fun screenshotAfter_withActions_appendsCapture() {
        val p = ActionParser.parse(
            """{"thought":"点通知后会弹框","screenshot_after":true,
               "actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertEquals(2, p.actions.size)
        assertEquals(TouchKind.TAP, p.actions[0].kind)
        assertEquals(TouchKind.CAPTURE, p.actions[1].kind)
        assertFalse(p.needImage)
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
        // 同给了就以 need_image 为准，不该再多出一个 capture
        assertEquals(1, p.actions.size)
        assertEquals(TouchKind.TAP, p.actions[0].kind)
    }

    @Test
    fun screenshotAfter_withFinished_doesNotAppend() {
        // 这批就是收尾：任务马上结束，追加的 capture 没人消费
        val p = ActionParser.parse(
            """{"thought":"做完就完成","screenshot_after":true,"finished":true,
               "actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertTrue(p.finished)
        assertEquals(1, p.actions.size)
        assertEquals(TouchKind.TAP, p.actions[0].kind)
    }

    @Test
    fun screenshotAfter_whenActionsFull_doesNotDropAction() {
        // 动作已经占满 MAX_ACTIONS 时，不为了"顺手看一眼"挤掉模型给的动作 ——
        // 挤掉是静默丢动作，比少一张图严重
        val many = (1..ActionParser.MAX_ACTIONS)
            .joinToString(",") { """{"action":"tap","index":$it}""" }
        val p = ActionParser.parse("""{"screenshot_after":true,"actions":[$many]}""", w, h)
        assertEquals(ActionParser.MAX_ACTIONS, p.actions.size)
        assertTrue(p.actions.all { it.kind == TouchKind.TAP })
        assertTrue(p.warning!!.contains("没有追加"))
    }

    @Test
    fun screenshotAfter_withoutActions_fallsBackToNeedImage() {
        // 只说"动作后给图"却没动作：意图就是要图，退化成立即要图，
        // 不该落到"空转"警告
        val p = ActionParser.parse("""{"thought":"想看新画面","screenshot_after":true}""", w, h)
        assertTrue(p.needImage)
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
        assertTrue(p.actions.isEmpty())
    }

    @Test
    fun normalActions_noCaptureNoImage() {
        val p = ActionParser.parse(
            """{"thought":"点一下","actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertEquals(1, p.actions.size)
        assertFalse(p.needImage)
    }

    // ---- 等待时长：默认值 + 长等待必须显式声明 ----

    @Test
    fun sleep_withoutDuration_usesDefault() {
        val p = ActionParser.parse(
            """{"actions":[{"action":"tap","index":1},{"action":"sleep"}]}""",
            w, h,
        )
        assertEquals(2, p.actions.size)
        assertEquals(ActionParser.DEFAULT_SLEEP_MS, p.actions[1].durationMs)
        assertNull(p.warning) // 没写时长不算错，不回灌
    }

    @Test
    fun sleep_longWithoutDeclaration_isClampedAndWarned() {
        val p = ActionParser.parse(
            """{"actions":[{"action":"sleep","duration_ms":25000}]}""",
            w, h,
        )
        assertEquals(1, p.actions.size)
        assertEquals(ActionParser.MAX_SILENT_SLEEP_MS, p.actions[0].durationMs)
        assertTrue(p.warning!!.contains("long_wait"))
    }

    @Test
    fun sleep_longWithDeclaration_isKept() {
        val p = ActionParser.parse(
            """{"actions":[{"action":"sleep","duration_ms":30000,
               "long_wait":true,"note":"等倒计时走完"}]}""",
            w, h,
        )
        assertEquals(30000, p.actions[0].durationMs)
        assertTrue(p.warning!!.contains("等倒计时走完"))
    }

    @Test
    fun sleep_hardCap_stillApplies() {
        // 声明了 long_wait 也不是想睡多久就睡多久
        val p = ActionParser.parse(
            """{"actions":[{"action":"sleep","duration_ms":600000,"long_wait":true}]}""",
            w, h,
        )
        assertEquals(ActionParser.MAX_SLEEP_MS, p.actions[0].durationMs)
    }

    @Test
    fun sleep_msAlias_isAccepted() {
        // 模型爱写 "ms"
        val p = ActionParser.parse(
            """{"actions":[{"action":"sleep","ms":3000}]}""",
            w, h,
        )
        assertEquals(3000, p.actions[0].durationMs)
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
        val p = ActionParser.parse("""{"thought":"无需动作即完成","finished":true}""", w, h)
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
