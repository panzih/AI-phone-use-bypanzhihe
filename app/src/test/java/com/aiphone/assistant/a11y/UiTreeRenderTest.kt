package com.aiphone.assistant.a11y

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 控件树语义渲染（ROADMAP §0）：分区表头、缩进、`+` 新增标记、⚠ 敏感标记、
 * 中文角色。这些行是模型"点哪个"的唯一依据，格式改坏会直接伤命中率。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiTreeRenderTest {

    private fun node(
        index: Int,
        text: String,
        cx: Int,
        cy: Int,
        className: String = "TextView",
        clickable: Boolean = false,
        scrollable: Boolean = false,
        w: Int = 200,
        h: Int = 80,
    ) = UiNode(
        index = index,
        className = className,
        text = text,
        contentDesc = "",
        viewId = "",
        bounds = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
        clickable = clickable,
        longClickable = false,
        scrollable = scrollable,
        editable = false,
        enabled = true,
        checked = false,
    )

    @Test
    fun `分区表头按 顶部-中部-底部 出现`() {
        val out = UiTreeParser.render(
            listOf(
                node(1, "返回", 100, 100),      // 顶部（<20% of 1000）
                node(2, "内容", 100, 500),      // 中部
                node(3, "发送", 100, 900),      // 底部（>80%）
            ),
            screenHeight = 1000,
        )
        val lines = out.lines()
        assertEquals("── 顶部 ──", lines[0])
        assertTrue(lines.any { it.contains("[1]") && it.contains("返回") })
        assertTrue(lines.any { it == "── 中部 ──" })
        assertTrue(lines.any { it == "── 底部 ──" })
    }

    @Test
    fun `不传屏高就不插分区表头`() {
        val out = UiTreeParser.render(listOf(node(1, "返回", 100, 100)))
        assertFalse(out.contains("顶部"))
    }

    @Test
    fun `相对上一帧新增的元素带加号`() {
        val a = node(1, "旧的", 100, 300)
        val b = node(2, "新出现的", 100, 500)
        val out = UiTreeParser.render(listOf(a, b), screenHeight = 0, previous = listOf(a))
        val bLine = out.lines().first { it.contains("新出现的") }
        val aLine = out.lines().first { it.contains("旧的") }
        assertTrue("新元素要带 +：$bLine", bLine.startsWith("+"))
        assertFalse("旧元素不能带 +：$aLine", aLine.startsWith("+"))
    }

    @Test
    fun `敏感标签带警告记号`() {
        val risky = node(1, "删除订单", 100, 300).describe()
        val plain = node(2, "设置", 100, 500).describe()
        assertTrue("删除订单 要带 ⚠：$risky", risky.contains("⚠"))
        assertFalse("设置 不该带 ⚠：$plain", plain.contains("⚠"))
    }

    @Test
    fun `类名渲染成中文角色`() {
        assertEquals("按钮", node(1, "确定", 0, 0, className = "Button").describe().substringAfter("] ").take(2))
        val input = node(2, "搜索", 0, 0, className = "EditText").describe()
        assertTrue(input.contains("输入框"))
        // 认不出的保留原名
        assertTrue(node(3, "x", 0, 0, className = "WeirdWidget").describe().contains("WeirdWidget"))
    }

    @Test
    fun `可滚容器里的行有缩进`() {
        val list = node(1, "列表", 100, 500, scrollable = true, w = 400, h = 800)
        val item = node(2, "第一项", 100, 450, w = 300, h = 60)
        val out = UiTreeParser.render(listOf(list, item), screenHeight = 1000)
        val itemLine = out.lines().first { it.contains("第一项") }
        assertTrue("容器内的行要缩进：$itemLine", itemLine.startsWith("  ["))
        val listLine = out.lines().first { it.contains("「列表」") || it.contains("列表") }
        assertFalse("容器自己不缩进：$listLine", listLine.startsWith("  ["))
    }

    @Test
    fun `中文角色的开关和滑块`() {
        assertTrue(node(1, "WiFi", 0, 0, className = "Switch").describe().contains("开关"))
        val slider = node(2, "亮度", 0, 0, className = "SeekBar").describe()
        assertTrue(slider.contains("滑块"))
    }
}
