package com.aiphone.assistant.agent

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
 * view 字段解析（ROADMAP §0 / agent-loop.md 5.2）：一次性生效、不写成常驻。
 *
 * 契约：
 *  - "image"/"both" **无动作** → 立刻转 need_image（重问且不计步，复用现成机制）；
 *  - **有动作** → 随 Parsed 带出，下一步生效；
 *  - "tree" 等价不写；收尾轮、need_image 同给时 view 作废。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionParserViewTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun `view_image 无动作时立刻转 need_image`() {
        val p = ActionParser.parse("""{"thought":"看不清","view":"image"}""", w, h)
        assertTrue(p.needImage)
        assertNull("立即可用的 view 不能再带去下一步", p.view)
        assertTrue(p.actions.isEmpty())
    }

    @Test
    fun `view_both 无动作时也立刻转 need_image`() {
        val p = ActionParser.parse("""{"thought":"看不清","view":"both"}""", w, h)
        assertTrue(p.needImage)
        assertNull(p.view)
    }

    @Test
    fun `view_image 带动作时下一步生效`() {
        val p = ActionParser.parse(
            """{"thought":"点完看结果","view":"image",
                "actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertEquals("image", p.view)
        assertFalse(p.needImage)
        assertEquals(1, p.actions.size)
    }

    @Test
    fun `view_both 带动作时下一步生效`() {
        val p = ActionParser.parse(
            """{"thought":"对照着点","view":"both",
                "actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertEquals("both", p.view)
    }

    @Test
    fun `view_tree 等价于不写`() {
        val p = ActionParser.parse(
            """{"thought":"换回列表","view":"tree",
                "actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertNull(p.view)
    }

    @Test
    fun `不认识的 view 值被忽略并回灌提示`() {
        val p = ActionParser.parse(
            """{"thought":"x","view":"screenshot","actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertNull(p.view)
        assertNotNull(p.warning)
        assertTrue(p.warning!!.contains("tree / image / both"))
    }

    @Test
    fun `收尾轮的 view 作废`() {
        val p = ActionParser.parse(
            """{"thought":"做完了","finished":true,"summary":"ok","view":"image"}""",
            w, h,
        )
        assertTrue(p.finished)
        assertNull(p.view)
    }

    @Test
    fun `need_image 和 view 同给时 need_image 优先`() {
        val p = ActionParser.parse(
            """{"thought":"看不清","need_image":true,"view":"image",
                "actions":[{"action":"tap","index":3}]}""",
            w, h,
        )
        assertTrue(p.needImage)
        assertNull("别让模型一轮里要两次图", p.view)
    }
}
