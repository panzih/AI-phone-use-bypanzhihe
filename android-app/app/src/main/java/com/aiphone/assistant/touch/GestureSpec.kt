package com.aiphone.assistant.touch

/**
 * 单指手势的统一模型。
 *
 * ## 核心思路：所有单指动作都是同一个三段式的参数变体
 *
 * ```
 * 按下 ──┬── 移动 ──┬── 停顿 ── 松手
 *        │          │
 *        │      滑动有停顿，甩动没有
 *        └── 拖拽在这里加长按
 * ```
 *
 * 用这三个参数就能表达全部单指动作：
 *
 * | 动作 | 按下后停顿 | 移动 | 松手前停顿 |
 * |---|---|---|---|
 * | 单击   | 无   | 无   | 无（快速按下抬起） |
 * | 双击   | 无   | 无   | 两次单击，中间隔一小段 |
 * | 长按   | 无   | 无   | 无（只是按住的时间长） |
 * | 滑动   | 无   | 有   | **有**（停一下再松） |
 * | 甩动   | 无   | 有   | **无**（划完立刻松手） |
 * | 拖拽   | **有** | 有   | 视情况 |
 *
 * ## 为什么"松手前停顿"能区分滑动和甩动
 *
 * 惯性滑动（fling）靠的是**松手瞬间的速度**。系统根据手指抬起前的移动速度
 * 决定是否继续滚一段。
 *
 *   - 划完停顿一下再松 → 停顿期间速度归零 → 系统认为手指"停住了" → 不甩
 *   - 划完立刻松 → 保持速度 → 系统继续滚一段 → 甩动
 *
 * 所以这一个参数就能把两者分开，不需要两套实现。
 */
data class GestureSpec(
    /**
     * 按下后、开始移动前的停留时长（毫秒）。
     *
     * **拖拽靠这个**：系统判定"按下后多久开始移动"来决定这是拖拽还是滑动，
     * 必须超过长按阈值才会进入拖拽模式。用 ViewConfiguration.getLongPressTimeout()
     * 拿设备真实值，再加一点余量。
     */
    val holdBeforeMoveMs: Long = 0,

    /** 移动过程时长 */
    val moveMs: Long = 0,

    /**
     * 移动到终点后、松手前的停顿（毫秒）。
     * 这是**滑动和甩动的唯一区别**：滑动有停顿，甩动没有。
     */
    val restBeforeUpMs: Long = 0,
) {
    /** 整个手势的总时长 */
    val totalMs: Long get() = holdBeforeMoveMs + moveMs + restBeforeUpMs

    companion object {
        // ---------------- 单指动作的预设参数 ----------------

        /**
         * 单击。
         *
         * 按下就抬起，时长要**明显小于**长按阈值，否则会被识别成长按。
         * 也不能太短 —— 部分控件对瞬时点击不敏感。30ms 是稳妥值。
         */
        val TAP = GestureSpec(moveMs = 30)

        /**
         * 长按。
         *
         * 原地按住不放。时长由调用方决定（用户可能想按 1 秒或 3 秒），
         * 但下限应该略超过长按阈值。
         */
        fun longPress(durationMs: Long = 800) = GestureSpec(moveMs = durationMs)

        /**
         * 滑动：有停顿，不触发惯性。
         *
         * 移动过程要"够慢"—— 太快的话系统会认为这是甩动。
         */
        fun swipe(moveMs: Long = 400) = GestureSpec(
            moveMs = moveMs.coerceAtLeast(200),
            restBeforeUpMs = 100,   // ← 关键：停顿让速度归零
        )

        /**
         * 甩动（惯性滑动）：划完立刻松手。
         *
         * 和滑动的唯一区别就是**没有停顿**，而且要移动得快。
         * 系统会在手指抬起时继续滚一段。
         */
        fun flick(moveMs: Long = 100) = GestureSpec(
            moveMs = moveMs.coerceAtMost(200),
            restBeforeUpMs = 0,     // ← 关键：不停顿，保持速度
        )

        /**
         * 拖拽：按住一会儿再移动。
         *
         * @param longPressTimeout 设备的真实长按阈值，
         *        由 ViewConfiguration.getLongPressTimeout() 提供。
         *        加 100ms 余量确保系统确实进入了拖拽模式。
         */
        fun drag(
            longPressTimeout: Long,
            moveMs: Long = 500,
        ) = GestureSpec(
            holdBeforeMoveMs = longPressTimeout + 100,
            moveMs = moveMs,
        )
    }
}
