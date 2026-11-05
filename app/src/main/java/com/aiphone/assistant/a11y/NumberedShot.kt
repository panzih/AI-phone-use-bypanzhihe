package com.aiphone.assistant.a11y

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 截图编号（ROADMAP §0，感知层最后一项）。
 *
 * 把元素列表的编号**画到截图上**（和文本同一套编号）—— 模型在图上看到的
 * [12] 就是列表里的 [12]，"图片上一个按钮"和"编号"从此不用来回对。
 *
 * 前提（ROADMAP 写明的）：空间截断和语义结构先做对 —— 编号是阅读顺序、
 * 截断不先丢顶部，画到图上的编号才有意义。这两项本轮已完成。
 *
 * 只在**元素列表随图一起发**时编号（列表就是编号的出处）；view:image 那种
 * "只给图不给列表"的轮次编了也没用（模型不知道 [n] 是谁），直接跳过。
 */
object NumberedShot {

    /** 编号圆标的数量上限：和控件树的单屏上限同量级 */
    private const val MAX_MARKS = 60

    /** 一个要画到图上的编号：图内坐标（已按截图分辨率换算） */
    data class Entry(val index: Int, val cx: Int, val cy: Int)

    /**
     * 编号落点规划（纯函数，可单测）。
     *
     * @param nodes 元素列表（编号 = index，屏幕坐标 bounds）
     * @param shotW/shotH 截图的实际分辨率（DisplayChannel 会缩边，不等于屏幕）
     * @param screenW/screenH 屏幕分辨率（bounds 的坐标系）
     */
    fun plan(
        nodes: List<UiNode>,
        shotW: Int,
        shotH: Int,
        screenW: Int,
        screenH: Int,
    ): List<Entry> {
        if (shotW <= 0 || shotH <= 0 || screenW <= 0 || screenH <= 0) return emptyList()
        val sx = shotW.toFloat() / screenW
        val sy = shotH.toFloat() / screenH
        return nodes.asSequence()
            .filter { it.centerX in 0 until screenW && it.centerY in 0 until screenH }
            .take(MAX_MARKS)
            .map {
                Entry(
                    index = it.index,
                    cx = (it.centerX * sx).toInt().coerceIn(0, shotW - 1),
                    cy = (it.centerY * sy).toInt().coerceIn(0, shotH - 1),
                )
            }
            .toList()
    }

    /**
     * 把编号画到截图上。
     *
     * @return 画好的 JPEG；**任何一步失败都返回 null**，调用方退回原图 ——
     *         编号是增益，绝不能因为画不上而把截图弄丢
     */
    fun annotate(
        imageBytes: ByteArray,
        nodes: List<UiNode>,
        screenW: Int,
        screenH: Int,
    ): ByteArray? = runCatching {
        val src = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return@runCatching null
        val entries = plan(nodes, src.width, src.height, screenW, screenH)
        if (entries.isEmpty()) {
            src.recycle()
            return@runCatching null
        }
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: run {
            src.recycle()
            return@runCatching null
        }
        src.recycle()
        val canvas = Canvas(bmp)
        // 半径随图缩放：小图（降质兜底路径）也要画得下
        val r = (bmp.width * 0.014f).coerceIn(10f, 30f)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x18, 0x6A, 0x56) // 主题松墨绿：和界面的视觉语言一致
            style = Paint.Style.FILL
            alpha = 210
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = r * 1.15f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        for (e in entries) {
            canvas.drawCircle(e.cx.toFloat(), e.cy.toFloat(), r, circle)
            val ty = e.cy.toFloat() - (text.ascent() + text.descent()) / 2f
            canvas.drawText(e.index.toString(), e.cx.toFloat(), ty, text)
        }
        val out = java.io.ByteArrayOutputStream(imageBytes.size)
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bmp.recycle()
        out.toByteArray()
    }.getOrNull()
}
