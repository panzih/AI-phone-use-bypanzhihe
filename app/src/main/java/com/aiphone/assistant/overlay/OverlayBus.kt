package com.aiphone.assistant.overlay

/**
 * 悬浮窗与 Agent 之间的桥。
 *
 * 为什么要这一层：Agent 跑在 Activity 的协程里，悬浮窗跑在 Service 里，
 * 但两者在**同一个进程**。用一个单例对象传话，比 bindService + 回调简单得多，
 * 而且不需要跨进程的 AIDL。
 *
 * 三个职责：
 *   1. Agent 告诉悬浮窗"现在第几步、下一步要做什么"
 *   2. Agent 在截图/注入前喊一声"藏一下"，事后喊"出来"
 *   3. 悬浮窗上的急停按钮，把停止请求传回 Agent
 */
object OverlayBus {

    /** 服务实例。没起悬浮窗时是 null，所有方法都做了空判断 */
    @Volatile
    var service: OverlayService? = null

    /** 悬浮窗上按过急停。Agent 每步都会检查它 */
    @Volatile
    var stopRequested: Boolean = false
        private set

    /** 任务结束时由调用方清掉，否则下一次任务一开始就被判定为"已停止" */
    fun clearStop() {
        stopRequested = false
    }

    fun requestStop() {
        stopRequested = true
    }

    val isShowing: Boolean get() = service != null

    /**
     * 更新左上角的状态卡。
     *
     * @param current 这一步正在做什么
     * @param nextHint 模型预测的**下一步**要做什么（给用户预判用）
     */
    fun update(step: Int, maxSteps: Int, current: String, nextHint: String) {
        service?.updateStatus(step, maxSteps, current, nextHint)
    }

    /**
     * 截图/注入之前把悬浮窗藏起来。
     *
     * 必须藏，两个原因：
     *   1. 无障碍截图抓的是整块屏幕，悬浮窗会出现在图里，
     *      模型会把它当成界面元素去点
     *   2. 底部那个按钮是可触摸窗口。不藏的话，注入的点击如果
     *      正好落在它上面，会被它吃掉 —— 甚至点到"急停"把自己停掉
     */
    fun hide() {
        service?.setVisible(false)
    }

    fun show() {
        service?.setVisible(true)
    }
}
