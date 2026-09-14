package com.aiphone.assistant.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class UiNode(
    val index: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    fun describe(): String {
        val sb = StringBuilder()
        sb.append('[').append(index).append("] ")
        sb.append(className)
        sb.append(" \"").append(text.ifBlank { contentDesc }.take(40)).append('"')
        sb.append(" @(").append(centerX).append(',').append(centerY).append(')')

        val flags = buildList {
            if (clickable) add("可点")
            if (longClickable) add("可长按")
            if (scrollable) add("可滚")
            if (editable) add("可输入")
            if (checked) add("已选")
            if (!enabled) add("禁用")
        }
        if (flags.isNotEmpty()) {
            sb.append(" [").append(flags.joinToString(",")).append(']')
        }
        return sb.toString()
    }
}

object UiTreeParser {

    private const val MIN_AREA = 24 * 24

    fun parse(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        limit: Int = 60,
    ): List<UiNode> {
        if (root == null) return emptyList()

        val out = ArrayList<UiNode>(limit)
        val seen = HashSet<String>()
        val rect = Rect()
        var index = 1

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty() && out.size < limit) {
            val node = stack.removeLast()

            if (isWorthKeeping(node, rect, screenWidth, screenHeight)) {
                val text = node.text?.toString().orEmpty().trim()
                val desc = node.contentDescription?.toString().orEmpty().trim()
                val id = node.viewIdResourceName.orEmpty()

                val key = "${text}|${desc}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
                if (seen.add(key)) {
                    out.add(
                        UiNode(
                            index = index++,
                            className = simplifyClass(node.className?.toString().orEmpty()),
                            text = text,
                            contentDesc = desc,
                            viewId = id,
                            bounds = Rect(rect),
                            clickable = node.isClickable,
                            longClickable = node.isLongClickable,
                            scrollable = node.isScrollable,
                            editable = node.isEditable,
                            enabled = node.isEnabled,
                            checked = node.isChecked,
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return out
    }

    private fun isWorthKeeping(
        node: AccessibilityNodeInfo,
        outRect: Rect,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()

        val interactive = node.isClickable || node.isLongClickable ||
            node.isScrollable || node.isEditable
        if (!interactive && text.isEmpty() && desc.isEmpty()) return false

        node.getBoundsInScreen(outRect)

        if (outRect.width() * outRect.height() < MIN_AREA) return false

        if (outRect.right <= 0 || outRect.bottom <= 0) return false
        if (outRect.left >= screenWidth || outRect.top >= screenHeight) return false

        return true
    }

    private fun simplifyClass(raw: String): String {
        if (raw.isEmpty()) return "?"
        val simple = raw.substringAfterLast('.')
        return if (raw.startsWith("android.widget.") || raw.startsWith("android.view.")) {
            simple
        } else {
            simplifyCustomNames(simple)
        }
    }

    private fun simplifyCustomNames(name: String): String {
        return name
            .removeSuffix("Layout")
            .removeSuffix("ViewGroup")
            .take(24)
    }

    fun render(nodes: List<UiNode>): String {
        if (nodes.isEmpty()) {
            return "（当前界面没有可交互元素，可能是游戏、视频或自定义绘制的页面）"
        }
        return nodes.joinToString("\n") { it.describe() }
    }
}
