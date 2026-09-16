package com.aiphone.assistant.record

import com.aiphone.assistant.a11y.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 录制下来的一步。
 *
 * ## 为什么记这么多字段
 *
 * 回放时要在一个**可能已经变化了的界面**上找到同一个按钮。单个字段都不够稳：
 *
 *   - `viewId` 最稳（同一个 App 的同一个控件基本不变），但自绘控件没有
 *   - `text` 很直观，但会变（"未读 3"→"未读 5"）
 *   - `contentDesc` 对纯图标按钮是唯一线索
 *   - 坐标最后兜底（布局变了就失效）
 *
 * 所以全记下来，回放时按优先级打分匹配（见 [ElementMatch]），
 * 而不是赌某一个字段。
 */
data class RecordedStep(
    /** tap / long_press / input */
    val action: String,
    val packageName: String = "",
    val className: String = "",
    val viewId: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val x: Int = 0,
    val y: Int = 0,
    /** action = input 时输入的内容 */
    val inputText: String = "",
    /**
     * 这一步碰的是密码框。
     *
     * 密码**绝不记录原文、也绝不发给模型** —— 录制文件是要给 AI 看的，
     * 把密码写进去等于把密码交给了模型服务商，而且会永久留在文件里。
     */
    val sensitive: Boolean = false,
) {
    /** 给用户和模型看的一行说明 */
    fun label(): String {
        val what = when {
            text.isNotBlank() -> "「${text.take(20)}」"
            contentDesc.isNotBlank() -> "「${contentDesc.take(20)}」"
            viewId.isNotBlank() -> viewId.substringAfterLast('/')
            className.isNotBlank() -> className
            else -> "($x,$y)"
        }
        val verb = when (action) {
            "input" -> "输入"
            "long_press" -> "长按"
            else -> "点击"
        }
        val extra = if (action == "input") {
            if (sensitive) " 密码（已隐藏）" else " 「${inputText.take(24)}」"
        } else ""
        return "$verb $what$extra"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action)
        put("package", packageName)
        put("class", className)
        put("viewId", viewId)
        put("text", text)
        put("desc", contentDesc)
        put("x", x)
        put("y", y)
        // 密码原文压根不写进文件
        put("input", if (sensitive) "" else inputText)
        put("sensitive", sensitive)
    }

    companion object {
        fun fromJson(o: JSONObject): RecordedStep = RecordedStep(
            action = o.optString("action", "tap"),
            packageName = o.optString("package", ""),
            className = o.optString("class", ""),
            viewId = o.optString("viewId", ""),
            text = o.optString("text", ""),
            contentDesc = o.optString("desc", ""),
            x = o.optInt("x", 0),
            y = o.optInt("y", 0),
            inputText = o.optString("input", ""),
            sensitive = o.optBoolean("sensitive", false),
        )
    }
}

/** 一次完整的录制 */
data class Recording(
    val steps: List<RecordedStep>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean get() = steps.isEmpty()

    /** 涉及了哪几个应用，给 AI 和用户看 */
    fun packages(): List<String> =
        steps.map { it.packageName }.filter { it.isNotBlank() }.distinct()

    /** 录制的原始文本，喂给模型"学习"时用 */
    fun toPromptText(): String = buildString {
        steps.forEachIndexed { i, s ->
            append(i + 1).append(". ")
            append("[${s.packageName}] ")
            append(s.label())
            val hints = buildList {
                if (s.viewId.isNotBlank()) add("id=${s.viewId}")
                if (s.className.isNotBlank()) add("class=${s.className}")
            }
            if (hints.isNotEmpty()) append("  (").append(hints.joinToString(", ")).append(')')
            appendLine()
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("createdAt", createdAt)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Recording? = runCatching {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { RecordedStep.fromJson(it) }
            }
            Recording(steps = steps, createdAt = o.optLong("createdAt", System.currentTimeMillis()))
        }.getOrNull()
    }
}

/**
 * 元素匹配：在**当前**界面上找回录制时点的那个控件。
 *
 * ## 为什么用打分而不是一堆 if-else
 *
 * 逐条 `if` 很容易写出"id 没匹配上就直接放弃"这种逻辑，而实际界面上
 * 经常是 id 变了但文字没变。打分能自然地表达"哪个线索更可信"，
 * 也方便调 —— 加一条线索只是加一项权重。
 */
object ElementMatch {

    private const val W_VIEW_ID = 100
    private const val W_TEXT = 60
    private const val W_DESC = 50
    private const val W_CLASS = 8
    private const val W_POSITION = 25

    /** 坐标兜底的容差（像素）。大约一个手指的宽度 */
    private const val POSITION_TOLERANCE = 48

    /** 低于这个分就认为没找到 —— 宁可停下问用户，也不要乱点 */
    private const val MIN_SCORE = 50

    fun score(step: RecordedStep, node: UiNode): Int {
        var s = 0
        if (step.viewId.isNotBlank() && node.viewId == step.viewId) s += W_VIEW_ID
        if (step.text.isNotBlank() && node.text == step.text) s += W_TEXT
        if (step.contentDesc.isNotBlank() && node.contentDesc == step.contentDesc) s += W_DESC
        if (step.className.isNotBlank() && node.className == step.className) s += W_CLASS

        if (step.x > 0 && step.y > 0) {
            val dx = node.centerX - step.x
            val dy = node.centerY - step.y
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
            if (dist <= POSITION_TOLERANCE) s += W_POSITION
        }
        return s
    }

    /** 找出最匹配的节点；没有够格的返回 null */
    fun best(step: RecordedStep, nodes: List<UiNode>): UiNode? =
        nodes.maxByOrNull { score(step, it) }?.takeIf { score(step, it) >= MIN_SCORE }
}
