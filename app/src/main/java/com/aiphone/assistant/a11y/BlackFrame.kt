package com.aiphone.assistant.a11y

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 「近黑帧」判定（ROADMAP §0）。
 *
 * 某些受保护的窗口（FLAG_SECURE：支付、银行、部分验证码页）截图**不会报错**，
 * 而是给你一张全黑的图。以前这张黑图被原样发给模型 —— 它看着像"空屏"，
 * 模型要么宣布完成、要么开始瞎点。黑图必须按"截不到"处理，和直接被系统
 * 拒绝一个口径。
 *
 * 判据故意保守：**降采样后 ≥ 99% 的采样点亮度 < 8** 才算近黑。
 * 深色主题的界面（抖音夜间模式、终端类 app）最暗也有状态栏图标/文字，
 * 采样里一定有亮点；而 FLAG_SECURE 的黑图是**均匀的 0**，不会误伤。
 */
object BlackFrame {

    /** 降采样到的边长：32×32 = 1024 个采样点，足够区分"均匀黑"和"深色页面" */
    private const val SAMPLE_SIDE = 32

    /** 单个采样点的亮度上限（0-255）。低于它才算"黑点" */
    private const val DARK_LUMINANCE = 8

    /** 黑点占比达到这个值才判定近黑 */
    private const val DARK_RATIO = 0.99

    /**
     * 这张截图是不是"基本全黑"。
     *
     * @return true = 近黑帧（大概率是 FLAG_SECURE 页面）；解码失败按 false 处理
     *         （解码不出来另有报错路径，不能在这里误判）
     */
    fun isMostlyBlack(imageBytes: ByteArray): Boolean {
        if (imageBytes.isEmpty()) return false
        val src = runCatching {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }.getOrNull() ?: return false

        val small = Bitmap.createScaledBitmap(src, SAMPLE_SIDE, SAMPLE_SIDE, true)
        val pixels = IntArray(SAMPLE_SIDE * SAMPLE_SIDE)
        small.getPixels(pixels, 0, SAMPLE_SIDE, 0, 0, SAMPLE_SIDE, SAMPLE_SIDE)
        if (small !== src) small.recycle()
        src.recycle()

        var dark = 0
        for (p in pixels) {
            // 亮度 = 常用加权（人眼对绿最敏感）
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (0.299 * r + 0.587 * g + 0.114 * b < DARK_LUMINANCE) dark++
        }
        return dark.toDouble() / pixels.size >= DARK_RATIO
    }
}
