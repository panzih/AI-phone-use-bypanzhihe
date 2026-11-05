package com.aiphone.assistant.a11y

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * 截图编号（ROADMAP §0）：编号落点规划（缩放/过滤/上限）与画图兜底。
 *
 * 契约：annotate **任何一步失败都返回 null**，调用方退回原图 ——
 * 编号是增益，绝不能因为画不上而把截图弄丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumberedShotTest {

    private fun node(index: Int, cx: Int, cy: Int, w: Int = 200, h: Int = 80) = UiNode(
        index = index,
        className = "Button",
        text = "项$index",
        contentDesc = "",
        viewId = "",
        bounds = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
        clickable = true,
        longClickable = false,
        scrollable = false,
        editable = false,
        enabled = true,
        checked = false,
    )

    @Test
    fun `plan 按截图分辨率缩放坐标`() {
        // 屏幕 1080x2400，截图缩到 540x1200：坐标减半
        val entries = NumberedShot.plan(listOf(node(1, 108, 240)), 540, 1200, 1080, 2400)
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].index)
        assertEquals(54, entries[0].cx)
        assertEquals(120, entries[0].cy)
    }

    @Test
    fun `plan 过滤屏幕外的元素`() {
        val entries = NumberedShot.plan(
            listOf(node(1, -50, 100), node(2, 2000, 100), node(3, 500, 1000)),
            1080, 2400, 1080, 2400,
        )
        assertEquals(listOf(3), entries.map { it.index })
    }

    @Test
    fun `plan 最多画 60 个编号`() {
        val many = (1..100).map { node(it, it * 8, 100) }
        val entries = NumberedShot.plan(many, 1080, 2400, 1080, 2400)
        assertEquals(60, entries.size)
    }

    @Test
    fun `plan 对非法尺寸返回空`() {
        assertTrue(NumberedShot.plan(listOf(node(1, 100, 100)), 0, 100, 1080, 2400).isEmpty())
        assertTrue(NumberedShot.plan(listOf(node(1, 100, 100)), 100, 100, 0, 2400).isEmpty())
    }

    @Test
    fun `annotate 在图上画出编号且失败时退 null`() {
        // 白底 200x400 图，屏幕坐标 400x800（缩放 1/2）
        val bmp = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val png = out.toByteArray()

        val nodes = listOf(node(1, 100, 100), node(2, 100, 600))
        val annotated = NumberedShot.annotate(png, nodes, 400, 800)
        assertNotNull("画编号失败不该发生", annotated)
        val decoded = BitmapFactory.decodeByteArray(annotated, 0, annotated!!.size)
        assertNotNull(decoded)
        // 编号圆标是深绿：原图纯白，画过的位置必然不再是白色
        val px = decoded.getPixel(100 / 2, 100 / 2)
        assertFalse("圆标处不该还是白底：$px", px == android.graphics.Color.WHITE)

        // 垃圾数据解不出图 → null（调用方退回原图）
        assertNull(NumberedShot.annotate(byteArrayOf(1, 2, 3), nodes, 400, 800))
        // 没有可画的元素 → null
        assertNull(NumberedShot.annotate(png, listOf(node(1, -10, -10)), 400, 800))
    }
}
