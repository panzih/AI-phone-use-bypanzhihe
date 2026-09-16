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
/**
 * AI 当前处在循环的哪一段。
 *
 * 用户光看"第几步"其实不知道它在忙什么 —— 一次截图要几百毫秒，
 * 一次模型调用要一两秒，中间还有等待。把阶段显示出来，
 * 用户才知道"它没卡住，是在等模型"。
 */
enum class AgentPhase(val label: String, val color: Int) {
    IDLE("待命", 0xFF9E9E9E.toInt()),
    SCREENSHOT("截图中", 0xFF29B6F6.toInt()),
    UPLOADING("上传中", 0xFFFFA726.toInt()),
    WAITING_MODEL("等待大模型返回结果", 0xFFAB47BC.toInt()),
    ACTING("正在操作手机", 0xFFEF5350.toInt()),
    WAITING_SYSTEM("等待系统响应", 0xFF66BB6A.toInt()),
}

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

    /**
     * 正在录制「操作记录」。
     *
     * 录制期间没有 Agent 在跑，悬浮窗上那个按钮的含义变成了"停止录制"，
     * 所以要把两件事分开 —— 否则按下去会去置 Agent 的停止标志，
     * 而 Agent 根本没在跑，用户会觉得按钮坏了。
     */
    @Volatile
    var recordingMode: Boolean = false
        private set

    /** 录制模式下按了悬浮窗按钮 */
    @Volatile
    var recordingStopRequested: Boolean = false
        private set

    fun enterRecording() {
        recordingMode = true
        recordingStopRequested = false
    }

    fun exitRecording() {
        recordingMode = false
        recordingStopRequested = false
    }

    fun requestRecordingStop() {
        recordingStopRequested = true
    }

    /** 录制进度：显示"录制中 · 已记录 N 步" */
    fun updateRecording(count: Int) {
        service?.updateRecording(count)
    }

    val isShowing: Boolean get() = service != null

    /** 切换阶段，左上角那行状态会跟着变 */
    fun setPhase(phase: AgentPhase) {
        service?.updatePhase(phase)
    }

    /**
     * 某个坐标是不是落在底部那个急停按钮上。
     *
     * 注入**点击**之前用得上：按钮是可触摸窗口，如果模型给的坐标正好
     * 落在它上面，这一下会被按钮吃掉 —— 甚至点到"急停"把自己的任务停掉。
     * 只有真重叠时才需要把悬浮窗藏起来，其余时候让它留着，
     * 用户才能看到"正在操作手机"这个状态。
     */
    fun overlapsStopButton(x: Int, y: Int): Boolean =
        service?.overlapsStopButton(x, y) ?: false

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
    /**
     * 在某个屏幕坐标闪一圈水波，让用户看见 AI 点在哪。
     *
     * 没有悬浮窗时就什么也不做 —— 这是纯视觉反馈，不该影响任务本身。
     */
    fun pulse(x: Int, y: Int) {
        service?.pulse(x, y)
    }

    fun hide() {
        service?.setVisible(false)
    }

    fun show() {
        service?.setVisible(true)
    }
}
