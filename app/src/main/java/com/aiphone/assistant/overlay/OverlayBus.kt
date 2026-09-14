package com.aiphone.assistant.overlay

object OverlayBus {
    @Volatile var service: OverlayService? = null
    @Volatile var stopRequested: Boolean = false
        private set

    fun clearStop() { stopRequested = false }
    fun requestStop() { stopRequested = true }
    val isShowing: Boolean get() = service != null

    fun update(step: Int, maxSteps: Int, current: String, nextHint: String) {
        service?.updateStatus(step, maxSteps, current, nextHint)
    }

    fun hide() { service?.setVisible(false) }
    fun show() { service?.setVisible(true) }
}
