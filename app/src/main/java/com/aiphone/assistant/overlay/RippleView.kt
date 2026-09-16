package com.aiphone.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.View

/**
 * 点击水波。
 *
 * ## 为什么值得做
 *
 * AI 操作手机时，用户看到的是"界面自己动了一下" —— 点在哪、点了几下，
 * 完全靠猜。这不是装饰，是**可解释性**：闪一下的位置就是 AI 真正注入的
 * 坐标，它是"点错了地方"还是"点对了但没反应"的第一手证据。
 *
 * ## 画的是什么
 *
 * 一圈由内向外扩散的同心圆（大圆减小圆的那种环），颜色用系统那个蓝。
 * 内圈跟着外圈一起走，让它是"水波"而不是"一个空心圈"。
 *
 * ## 实现上的两个选择
 *
 * **不用 ValueAnimator，用 `postInvalidateOnAnimation`。**
 * 每次脉冲创建一个动画对象、结束再销毁，在"每步可能点好几次"的场景下
 * 开销不划算。这里只维护一个 `(x, y, 开始时间)` 的列表，`onDraw` 里按
 * 当前时间算半径和透明度，画完还有活的就再要一帧。没有对象创建，
 * 也没有动画取消不干净的问题。
 *
 * **坐标是屏幕坐标。** 这个 View 是全屏的，所以注入点的屏幕坐标可以
 * 直接当 View 坐标用，不需要换算。
 */
class RippleView(context: Context) : View(context) {

    private class Ripple(val x: Float, val y: Float, val startAt: Long)

    private val ripples = ArrayList<Ripple>(4)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 新的脉冲，坐标是**这个 View 自己的坐标系**。可以从任何线程调 */
    fun pulse(x: Float, y: Float) {
        if (ripples.size >= MAX_CONCURRENT) ripples.removeAt(0)
        ripples.add(Ripple(x, y, SystemClock.uptimeMillis()))
        invalidate()
    }

    /**
     * 新的脉冲，坐标是**屏幕坐标**。
     *
     * 通道层上报的是屏幕坐标，而这个 View 的原点不一定在屏幕原点 ——
     * 状态栏、挖孔、乃至某些 ROM 的窗口内边距都会让窗口整体下移或内缩。
     * 差多少就直接用 `getLocationOnScreen` 量出来减掉，比去猜各种
     * WindowManager flag 组合的效果靠谱得多。
     */
    fun pulseScreen(x: Int, y: Int) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        pulse(x - loc[0].toFloat(), y - loc[1].toFloat())
    }

    /** 悬浮窗要藏起来时把没画完的清掉 —— 否则再显示出来会接着画一半的圈 */
    fun clear() {
        if (ripples.isEmpty()) return
        ripples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ripples.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val t = (now - r.startAt).toFloat() / DURATION_MS
            if (t >= 1f) {
                it.remove()
                continue
            }

            // easeOutQuad：一开始扩得快，后面慢下来，像水面扩散
            val eased = 1f - (1f - t) * (1f - t)
            val radius = MIN_RADIUS_PX + (MAX_RADIUS_PX - MIN_RADIUS_PX) * eased
            val fade = (1f - t)

            // 外圈：实线环
            ringPaint.color = COLOR
            ringPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(r.x, r.y, radius, ringPaint)

            // 内圈：同心，跟着一起扩散
            ringPaint.alpha = (140 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(r.x, r.y, radius * 0.62f, ringPaint)

            // 淡淡的填充，让圈以内的区域也有颜色 —— 就是"大圆减小圆"看着的那块
            fillPaint.color = COLOR
            fillPaint.alpha = (48 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(r.x, r.y, radius, fillPaint)
        }

        // 还有活的就再要一帧。列表空了自然停下，不需要额外的启停逻辑
        if (ripples.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val MIN_RADIUS_PX = dp(6f)
    private val MAX_RADIUS_PX = dp(72f)

    private companion object {
        /** 一次水波持续多久。500ms 左右刚好看得清又不拖沓 */
        const val DURATION_MS = 520f

        /** 最多同时画几个。超过就丢最早的，防止狂点把列表堆起来 */
        const val MAX_CONCURRENT = 6

        /**
         * 系统那个蓝。
         *
         * 悬浮窗是 Service 里的原生 View，拿不到 Compose 的动态取色，
         * 所以写死一个接近系统强调色的蓝。想跟着主题走的话，
         * 要在这里读一次 ColorScheme 再传进来，成本不划算。
         */
        const val COLOR = 0xFF2F7CF6.toInt()
    }
}
