package com.aiphone.assistant.agent

import com.aiphone.assistant.touch.ScrollDirection
import com.aiphone.assistant.touch.TouchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ROADMAP §0 新动作的解析与参数校验：press_enter / collect / move_app /
 * set_alarm / sms_draft / open_nav / share_text / clipboard_*。
 *
 * 这些动作不做不可逆的事（短信是草稿、分享由用户点），但参数错了会
 * 打到系统应用头上 —— 校验必须在这里钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionParserIntentTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun `press_enter 无参数解析`() {
        val p = ActionParser.parse(
            """{"thought":"搜","actions":[{"action":"press_enter"}]}""",
            w, h,
        )
        assertEquals(TouchKind.PRESS_ENTER, p.actions[0].kind)
    }

    @Test
    fun `collect 默认向下 0 页_执行层会用默认值`() {
        val p = ActionParser.parse(
            """{"thought":"收列表","actions":[{"action":"collect"}]}""",
            w, h,
        )
        val a = p.actions[0]
        assertEquals(TouchKind.COLLECT, a.kind)
        assertEquals(ScrollDirection.DOWN, a.direction)
        assertEquals(0, a.pages)
    }

    @Test
    fun `collect 认 pages 与 direction`() {
        val p = ActionParser.parse(
            """{"thought":"收列表","actions":[{"action":"collect","direction":"up","pages":8}]}""",
            w, h,
        )
        assertEquals(ScrollDirection.UP, p.actions[0].direction)
        assertEquals(8, p.actions[0].pages)
    }

    @Test
    fun `move_app 的 to 只认 background 和 foreground`() {
        val ok1 = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"move_app","to":"background"}]}""", w, h,
        )
        assertEquals("background", ok1.actions[0].moveTarget)

        val ok2 = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"move_app","to":"foreground"}]}""", w, h,
        )
        assertEquals("foreground", ok2.actions[0].moveTarget)

        val bad = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"move_app","to":"left"}]}""", w, h,
        )
        assertTrue("错误的 to 要丢弃动作并回灌原因", bad.actions.isEmpty())
        assertNotNull(bad.warning)
        assertTrue(bad.warning!!.contains("background"))
    }

    @Test
    fun `set_alarm 的 hour 和 minute 有范围校验`() {
        val ok = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"set_alarm","hour":7,"minute":30,"note":"起床"}]}""",
            w, h,
        )
        assertEquals(TouchKind.SET_ALARM, ok.actions[0].kind)
        assertEquals(7, ok.actions[0].hour)
        assertEquals(30, ok.actions[0].minute)
        assertEquals("起床", ok.actions[0].text)

        val badHour = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"set_alarm","hour":25,"minute":30}]}""",
            w, h,
        )
        assertTrue(badHour.actions.isEmpty())
        assertTrue(badHour.warning!!.contains("0-23"))
    }

    @Test
    fun `sms_draft 必须有收件人和内容`() {
        val ok = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"sms_draft","target":"13800000000","text":"晚点到家"}]}""",
            w, h,
        )
        assertEquals("13800000000", ok.actions[0].target)
        assertEquals("晚点到家", ok.actions[0].text)

        val noTarget = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"sms_draft","text":"hi"}]}""",
            w, h,
        )
        assertTrue(noTarget.actions.isEmpty())
        assertTrue(noTarget.warning!!.contains("target"))
    }

    @Test
    fun `open_nav 与 share_text 的 text 必填`() {
        val nav = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"open_nav","text":"人民广场"}]}""",
            w, h,
        )
        assertEquals(TouchKind.OPEN_NAV, nav.actions[0].kind)
        assertEquals("人民广场", nav.actions[0].text)

        val badNav = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"open_nav"}]}""",
            w, h,
        )
        assertTrue(badNav.actions.isEmpty())

        val badShare = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"share_text"}]}""",
            w, h,
        )
        assertTrue(badShare.actions.isEmpty())
    }

    @Test
    fun `clipboard_set 的 text 必填_get 无参数`() {
        val get = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"clipboard_get"}]}""",
            w, h,
        )
        assertEquals(TouchKind.CLIPBOARD_GET, get.actions[0].kind)

        val set = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"clipboard_set","text":"hello"}]}""",
            w, h,
        )
        assertEquals("hello", set.actions[0].text)

        val bad = ActionParser.parse(
            """{"thought":"x","actions":[{"action":"clipboard_set"}]}""",
            w, h,
        )
        assertTrue(bad.actions.isEmpty())
    }
}
