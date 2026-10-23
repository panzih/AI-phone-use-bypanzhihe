package com.aiphone.assistant.agent

import com.aiphone.assistant.data.TapReviewMode
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
 * 点击前复核的边界。
 *
 * 这里钉的是**最容易静默错**的三件事：
 *   - 贴边时裁剪框夹回屏内，准星仍然落在图里（否则模型看到的是错位的画面）；
 *   - 模型返回的更正坐标是图内的，换算成屏幕坐标不能多加/少加 left/top；
 *   - "不通过但没给坐标"必须按叫停处理，不能默认放行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TapReviewTest {

    @Test
    fun `裁剪以落点为中心_边长取短边45%`() {
        val c = TapReview.cropFor(300, 400, 1080, 2400)
        assertEquals(486, c.size)
        assertEquals(57, c.left)
        assertEquals(157, c.top)
        assertTrue("准星必须在裁剪框内", 300 in c.left..c.right)
        assertTrue("准星必须在裁剪框内", 400 in c.top..c.bottom)
    }

    @Test
    fun `贴右下角时裁剪框夹回屏内`() {
        val c = TapReview.cropFor(1070, 2390, 1080, 2400)
        assertEquals(486, c.size)
        assertEquals(594, c.left)
        assertEquals(1914, c.top)
        assertTrue(c.right <= 1080)
        assertTrue(c.bottom <= 2400)
        assertTrue(1070 in c.left..c.right)
        assertTrue(2390 in c.top..c.bottom)
    }

    @Test
    fun `小屏时裁剪不超过屏幕短边`() {
        val c = TapReview.cropFor(100, 150, 200, 300)
        assertEquals(200, c.size)
        assertEquals(0, c.left)
        assertEquals(50, c.top)
        assertTrue(c.right <= 200 && c.bottom <= 300)
    }

    @Test
    fun `复核档位_只复核该复核的动作`() {
        assertFalse(TapReview.shouldReview(TapReviewMode.OFF, TouchKind.TAP, 0, false))
        assertTrue(TapReview.shouldReview(TapReviewMode.COORDINATES, TouchKind.TAP, 0, false))
        assertFalse(TapReview.shouldReview(TapReviewMode.COORDINATES, TouchKind.TAP, 3, false))
        assertTrue(TapReview.shouldReview(TapReviewMode.ALL, TouchKind.TAP, 3, false))
        assertTrue(TapReview.shouldReview(TapReviewMode.ALL, TouchKind.LONG_PRESS, 0, false))
        assertFalse(TapReview.shouldReview(TapReviewMode.ALL, TouchKind.SWIPE, 0, false))
        // 浏览器按 DOM 编号点击，位置由盒模型决定，不触发复核
        assertFalse(TapReview.shouldReview(TapReviewMode.ALL, TouchKind.TAP, 1, true))
    }

    @Test
    fun `复核通过_各种字段名都认`() {
        val crop = TapReview.Crop(100, 200, 400)
        listOf(
            """{"ok":true}""",
            """{"approved":true}""",
            """{"confirm":true}""",
            """{"pass":true}""",
        ).forEach {
            val d = TapReview.parse(it, crop, 1080, 2400)
            assertTrue("应该通过：$it", d.approved)
            assertNull(d.correctedX)
        }
    }

    @Test
    fun `复核改点_图内坐标换算回屏幕`() {
        val crop = TapReview.Crop(57, 157, 486)
        val d = TapReview.parse("""{"ok":false,"x":120,"y":130,"why":"偏左"}""", crop, 1080, 2400)
        assertFalse(d.approved)
        assertFalse(d.abort)
        assertEquals(177, d.correctedX)
        assertEquals(287, d.correctedY)
        assertEquals("偏左", d.why)
    }

    @Test
    fun `复核改点_模型直接给全屏坐标也能认`() {
        val crop = TapReview.Crop(57, 157, 486)
        val d = TapReview.parse("""{"ok":false,"x":800,"y":900,"why":"点右一点"}""", crop, 1080, 2400)
        assertEquals(800, d.correctedX)
        assertEquals(900, d.correctedY)
    }

    @Test
    fun `复核叫停_abort优先于ok`() {
        val crop = TapReview.Crop(57, 157, 486)
        val d = TapReview.parse(
            """{"ok":false,"abort":true,"why":"这个按钮会删除数据"}""",
            crop, 1080, 2400,
        )
        assertFalse(d.approved)
        assertTrue(d.abort)
        assertEquals("这个按钮会删除数据", d.why)
    }

    @Test
    fun `只回一句不通过_也按叫停`() {
        val crop = TapReview.Crop(0, 0, 486)
        val d = TapReview.parse("不通过：落点偏到空白处了", crop, 1080, 2400)
        assertFalse(d.approved)
        assertTrue(d.abort)
    }

    @Test
    fun `看不懂的回答_放行但标记未解析`() {
        val crop = TapReview.Crop(0, 0, 486)
        val d = TapReview.parse("我想了想，这个界面挺复杂的", crop, 1080, 2400)
        assertTrue(d.approved)
        assertFalse(d.parsed)
        assertNotNull(d.why)
    }
}
