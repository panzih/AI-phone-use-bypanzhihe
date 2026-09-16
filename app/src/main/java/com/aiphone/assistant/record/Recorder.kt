package com.aiphone.assistant.record

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 录制器：把用户手动操作手机的过程记下来。
 *
 * ## 为什么不拦触摸，而是听事件
 *
 * 用户描述这个功能时说的是"悬浮窗默认用户点击是点到这个悬浮窗上的"——
 * 那需要做一层透明窗口去截触摸，麻烦且容易挡到用户。
 *
 * 而 App 已经有 AccessibilityService：**监听 `TYPE_VIEW_CLICKED` 就能知道
 * 用户点了哪个控件**，而且拿到的正是控件树里的那个节点 —— 和 AI 自己
 * 操作时用的是同一套定位信息（viewId / text / bounds）。
 * 也就是说录下来的东西天然就能被回放，不需要额外对齐坐标系。
 *
 * ## 三个必须处理的现实问题
 *
 * **1. 会录到自己。** 用户在我们自己的界面里点"停止录制"也是一次点击。
 * 所以按包名过滤掉本应用的事件。
 *
 * **2. 输入框是逐字符报事件的。** `TYPE_VIEW_TEXT_CHANGED` 每敲一个字都触发。
 * 处理办法是：同一个输入框的连续变化**就地覆盖**上一条，而不是追加 ——
 * 最后一条事件里的文本就是最终内容。
 *
 * **3. 密码框必须特殊对待。** 密码原文既不落盘也不发给模型，
 * 只记一个"这里输入过密码"的占位。录制文件是要给 AI 看的，
 * 把密码写进去等于交给模型服务商，而且会永久留在文件里。
 */
object Recorder {

    private const val TAG = "Recorder"

    /** 连续两次点击之间小于这个间隔的，认为是误触/连击的重复上报 */
    private const val DEDUP_WINDOW_MS = 250L

    @Volatile
    var isRecording: Boolean = false
        private set

    private val steps = mutableListOf<RecordedStep>()
    private var lastEventAt = 0L
    private var lastKey = ""

    /** 录制期间要不要连输入内容一起记（用户可以在界面上关掉） */
    @Volatile
    var recordText: Boolean = true

    /** 本应用的包名，用来过滤掉"用户点了我们自己的界面" */
    @Volatile
    var selfPackage: String = ""

    val count: Int get() = synchronized(steps) { steps.size }

    fun start() {
        synchronized(steps) { steps.clear() }
        lastEventAt = 0L
        lastKey = ""
        isRecording = true
        Log.i(TAG, "开始录制")
    }

    fun stop(): Recording {
        isRecording = false
        val snapshot = synchronized(steps) { steps.toList() }
        Log.i(TAG, "停止录制，共 ${snapshot.size} 步")
        return Recording(snapshot)
    }

    fun snapshot(): Recording = synchronized(steps) { Recording(steps.toList()) }

    fun clear() = synchronized(steps) { steps.clear() }

    /** 界面上的清单，一行一步 */
    fun labels(): List<String> = synchronized(steps) { steps.map { it.label() } }

    /**
     * 无障碍事件入口。由 [com.aiphone.assistant.a11y.AutoService] 转发。
     *
     * 这个方法会被系统在**无障碍服务的线程**上高频调用，所以：
     * 不做 IO、不分配大对象、任何异常都吞掉（录制失败不该影响服务本身）。
     */
    fun onEvent(event: AccessibilityEvent) {
        if (!isRecording) return
        try {
            handle(event)
        } catch (t: Throwable) {
            Log.w(TAG, "录制事件失败：${t.message}")
        }
    }

    private fun handle(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        // 自己家的界面不录 —— 包括悬浮窗和"停止录制"那个按钮
        if (selfPackage.isNotEmpty() && pkg == selfPackage) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            -> {
                val node = event.source ?: return
                val step = fromNode(
                    node = node,
                    action = if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                        "long_press"
                    } else {
                        "tap"
                    },
                    pkg = pkg,
                ) ?: return
                if (isDuplicate(step)) return
                synchronized(steps) { steps.add(step) }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (!recordText) return
                val node = event.source ?: return
                val newText = event.text.joinToString("").trim()
                val sensitive = node.isPassword
                val step = fromNode(node, "input", pkg) ?: return
                val stepWithText = step.copy(
                    inputText = if (sensitive) "" else newText,
                    sensitive = sensitive,
                )

                // 同一个输入框的连续变化就地覆盖：敲一个字报一次，
                // 追加的话"你好"会变成三条记录
                synchronized(steps) {
                    val last = steps.lastOrNull()
                    if (last != null && last.action == "input" && sameElement(last, stepWithText)) {
                        steps[steps.size - 1] = stepWithText
                    } else {
                        steps.add(stepWithText)
                    }
                }
                // 输入不参与"重复点击"去抖，否则连续打字会被吞掉
                lastEventAt = 0L
                lastKey = ""
            }
        }
    }

    private fun fromNode(
        node: AccessibilityNodeInfo,
        action: String,
        pkg: String,
    ): RecordedStep? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""

        // 什么都没拿到的节点（纯容器、自绘区域）记下来也没意义
        if (text.isEmpty() && desc.isEmpty() && viewId.isEmpty() && rect.isEmpty) return null

        return RecordedStep(
            action = action,
            packageName = pkg,
            className = cls,
            viewId = viewId,
            text = text,
            contentDesc = desc,
            x = rect.centerX(),
            y = rect.centerY(),
        )
    }

    private fun sameElement(a: RecordedStep, b: RecordedStep): Boolean = when {
        a.viewId.isNotBlank() && b.viewId.isNotBlank() -> a.viewId == b.viewId
        else -> a.className == b.className && a.x == b.x && a.y == b.y
    }

    /**
     * 短时间内的同一个元素，是重复上报而不是用户真的点了两下。
     *
     * 阈值取 250ms：真人双击的间隔通常在 100~300ms，但那是有意的操作；
     * 而系统对一次点击报两次事件的情况也都挤在这个窗口的最前面。
     * 拿不准的时候宁可多记一条 —— 回放时多点一次还能看见，漏记就断了。
     */
    private fun isDuplicate(step: RecordedStep): Boolean {
        val now = System.currentTimeMillis()
        val key = "${step.packageName}|${step.viewId}|${step.text}|${step.x},${step.y}"
        val dup = key == lastKey && (now - lastEventAt) < DEDUP_WINDOW_MS
        lastKey = key
        lastEventAt = now
        return dup
    }
}
