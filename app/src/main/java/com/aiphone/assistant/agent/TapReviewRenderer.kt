package com.aiphone.assistant.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * 把截图裁成复核用的局部图，并在落点上画十字准星。
 *
 * 为什么不用原图 + 让模型自己找坐标：模型对"整张 1080×2400 里那个点"
 * 的空间感很差，尤其是文字密集的设置页。裁掉无关区域、把准星画出来，
 * 复核员只需要回答"这个十字落在什么控件上"，判断难度低得多。
 */
object TapReviewRenderer {

    fun cropAndMark(
        shot: ByteArray,
        crop: TapReview.Crop,
        pointX: Int,
        pointY: Int,
    ): ByteArray? {
        val src = BitmapFactory.decodeByteArray(shot, 0, shot.size) ?: return null
        // 截图尺寸和屏幕尺寸理论上一致；不一致时宁可放弃复核（调用方按原点执行），
        // 也不要裁出错位的一块图 —— 那会让复核员看到"另一个地方"。
        if (crop.size <= 0 || src.width < crop.right || src.height < crop.bottom) {
            src.recycle()
            return null
        }
        var out: Bitmap? = null
        return try {
            val bitmap = Bitmap.createBitmap(crop.size, crop.size, Bitmap.Config.ARGB_8888)
            out = bitmap
            val canvas = Canvas(bitmap)
            val srcRect = Rect(crop.left, crop.top, crop.right, crop.bottom)
            val dstRect = Rect(0, 0, crop.size, crop.size)
            canvas.drawBitmap(src, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))

            val cx = (pointX - crop.left).toFloat()
            val cy = (pointY - crop.top).toFloat()
            val radius = (crop.size * 0.045f).coerceIn(10f, 30f)

            // 先白后红：深色/浅色界面上都看得清
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 7f
            }
            val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val arm = radius * 1.7f
            canvas.drawCircle(cx, cy, radius, white)
            canvas.drawCircle(cx, cy, radius, red)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, white)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, white)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, red)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, red)

            ByteArrayOutputStream().use { bytes ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, bytes)
                bytes.toByteArray()
            }
        } catch (t: Throwable) {
            null
        } finally {
            src.recycle()
            out?.recycle()
        }
    }
}
