package com.aiphone.assistant.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UI 控件树里的一个元素。
 *
 * ## 为什么要有这个层
 *
 * 无障碍原始给的是 AccessibilityNodeInfo 树，又深又吵，还有大量无意义的容器节点。
 * 直接丢给模型既浪费 token 又干扰判断。所以做一次压缩：
 *   - 只留可交互的（可点击 / 可滚动 / 可编辑 / 有文字）
 *   - 丢掉面积过小的噪声节点
 *   - 编号，让模型报编号而不是报像素坐标
 */
data class UiNode(
    /** 编号，从 1 开始。模型就报这个 */
    val index: Int,
    /** 控件类名，简写过的，比如 Button / EditText */
    val className: String,
    /** 屏幕上的文字 */
    val text: String,
    /** 无障碍描述（纯图标按钮常常只有这个） */
    val contentDesc: String,
    /** 资源 id，调试时有用 */
    val viewId: String,
    /** 控件在屏幕上的实际范围，中心点就是点击位置 */
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    /** 勾选状态，复选框之类用得上 */
    val checked: Boolean,
) {
    /** 点击位置：控件中心 */
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    /**
     * 日志和报错里指代这个控件的一句话。
     *
     * 为什么必须有：调试点错问题时，日志里只有"编号[33] 点不动"是查不出
     * 究竟点到谁的。带上文字/描述之后，"编号[33] 其实点到了『切换』"这种
     * 结论可以直接从日志读出来，不用再去猜。
     */
    fun labelForLog(): String =
        text.ifBlank { contentDesc }.ifBlank { className }.take(20)

    /** 给模型看的一行描述 */
    fun describe(): String {
        val sb = StringBuilder()
        sb.append('[').append(index).append("] ")

        // 控件类型：把 android.widget.Button 简写成 Button
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

/**
 * 把无障碍节点树压缩成 UiNode 列表。
 *
 * 过滤规则（都是实打实踩出来的）：
 *   1. 只保留有意义的节点 —— 可交互，或者有文字/描述
 *   2. 面积太小的丢掉 —— 装饰性分割线、1px 占位符全是噪声
 *   3. 完全在屏幕外的丢掉 —— 列表滚动后会有大量这类残留
 *   4. 没有文字也没有描述、又不可交互的容器节点丢掉 —— 它们只会占地方
 */
object UiTreeParser {

    /** 小于这个面积的节点当噪声丢掉 */
    private const val MIN_AREA = 24 * 24

    /**
     * 控件树**读到了、但一个可交互元素都没有**时，给模型看的那一行。
     *
     * ⚠️ 这是"空列表"的占位文本，**不是**一个元素。历史上调用方用
     * `文本行数` 当元素个数，于是这一行被数成了「控件树：1 个元素」，
     * 导出日志里完全看不出"其实是 0 个 / 根本读不到"。要用元素个数就
     * 用 [ParseResult.nodes]`.size`，不要数文本行。
     */
    const val EMPTY_TEXT = "（当前界面没有可交互元素，可能是游戏、视频或自定义绘制的页面）"

    /**
     * 一次解析的结果。
     *
     * [rawNodeCount] / [truncated] 是为了**复盘**加的：以前日志里只有
     * "控件树：N 个元素"，看不出这 N 个是完整的还是被 `limit` 砍过的，
     * 也看不出无障碍到底遍历到了多少节点。
     */
    data class ParseResult(
        val nodes: List<UiNode>,
        /** 遍历到的原始节点数（含被过滤掉的容器/噪声），<= limit 时不代表截断 */
        val rawNodeCount: Int,
        /** 是否撞到了 limit（界面元素比发给模型的还多） */
        val truncated: Boolean,
    )

    /**
     * 解析整棵树。
     *
     * @param root 无障碍给的根节点（通常是 getRootInActiveWindow()）
     * @param screenWidth 屏幕宽，用于过滤屏幕外节点
     * @param screenHeight 屏幕高
     * @param limit 最多返回多少个，防止极端界面撑爆提示词
     * @param excludePackage 整个子树都跳过的包名。传控制应用自己的包名 ——
     *        悬浮面板上的「急停」也长得像个按钮，混进列表里模型真会去点，
     *        而按下去就是把用户自己的任务停了。
     */
    fun parse(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        limit: Int = 60,
        excludePackage: String? = null,
    ): List<UiNode> = parseDetailed(root, screenWidth, screenHeight, limit, excludePackage).nodes

    /** 和 [parse] 一样，只是把"截断了没有 / 遍历了多少"一起带出来，供日志复盘 */
    fun parseDetailed(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        limit: Int = 60,
        excludePackage: String? = null,
    ): ParseResult {
        val walk = keepNodesDetailed(root, screenWidth, screenHeight, limit, excludePackage)
        val rect = Rect()
        val nodes = walk.nodes.mapIndexed { i, node ->
            node.getBoundsInScreen(rect)
            UiNode(
                index = i + 1,
                className = simplifyClass(node.className?.toString().orEmpty()),
                text = node.text?.toString().orEmpty().trim(),
                contentDesc = node.contentDescription?.toString().orEmpty().trim(),
                viewId = node.viewIdResourceName.orEmpty(),
                bounds = Rect(rect),
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                enabled = node.isEnabled,
                checked = node.isChecked,
            )
        }
        return ParseResult(nodes, walk.visited, walk.truncated)
    }

    /** [keepNodesDetailed] 的结果 */
    data class NodeWalk(
        val nodes: List<AccessibilityNodeInfo>,
        val visited: Int,
        val truncated: Boolean,
    )

    /**
     * 只做遍历和过滤，返回**原始节点**，顺序和 [parse] 的编号一一对应。
     *
     * 为什么必须共用这一份：编号是模型定位的唯一依据，而"按编号点"的时候
     * 要重新解析一遍树（节点对象不能跨帧复用）。如果两处各写一套过滤规则，
     * 编号就会错位 —— 模型说点 3 号，实际点到了 5 号，而且完全看不出来。
     *
     * @return 保留下来的节点，第 n 个对应编号 n+1
     */
    fun keepNodes(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        limit: Int = 60,
        excludePackage: String? = null,
    ): List<AccessibilityNodeInfo> =
        keepNodesDetailed(root, screenWidth, screenHeight, limit, excludePackage).nodes

    /** 和 [keepNodes] 一样，额外回报遍历了多少个原始节点、有没有被 limit 截断 */
    fun keepNodesDetailed(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        limit: Int = 60,
        excludePackage: String? = null,
    ): NodeWalk {
        if (root == null) return NodeWalk(emptyList(), 0, false)

        val out = ArrayList<AccessibilityNodeInfo>(limit)
        val seen = HashSet<String>()
        val rect = Rect()
        var visited = 0

        // 深度优先遍历。用显式栈避免深层界面把递归栈打爆
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty() && out.size < limit) {
            val node = stack.removeLast()
            visited++

            // 整个子树跳过：自己家的悬浮面板不该出现在给模型的列表里。
            // continue 之后不会再把子节点入栈，等于剪掉整棵子树。
            if (excludePackage != null && node.packageName?.toString() == excludePackage) {
                continue
            }

            if (isWorthKeeping(node, rect, screenWidth, screenHeight)) {
                val text = node.text?.toString().orEmpty().trim()
                val desc = node.contentDescription?.toString().orEmpty().trim()

                // 同样的位置 + 同样的文字，通常是重复节点，只留一个
                val key = "${text}|${desc}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
                if (seen.add(key)) out.add(node)
            }

            // 子节点入栈。注意回收中间节点的做法这里没做，
            // 因为 API 33+ 才有 recycle()，而 minSdk 是 28
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        // out.size 撞到 limit 就认为"可能还有没发出去的" —— 保守表述，
        // 日志里写的是"已达上限"，不断言一定被砍了
        return NodeWalk(out, visited, out.size >= limit)
    }

    /** 这个节点值不值得留给模型看 */
    private fun isWorthKeeping(
        node: AccessibilityNodeInfo,
        outRect: Rect,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        // 不可见的直接跳过
        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()

        // 既不能交互又没内容 → 纯容器，跳过
        val interactive = node.isClickable || node.isLongClickable ||
            node.isScrollable || node.isEditable
        if (!interactive && text.isEmpty() && desc.isEmpty()) return false

        node.getBoundsInScreen(outRect)

        // 面积太小 → 噪声
        if (outRect.width() * outRect.height() < MIN_AREA) return false

        // 完全在屏幕外 → 列表滚走的残留
        if (outRect.right <= 0 || outRect.bottom <= 0) return false
        if (outRect.left >= screenWidth || outRect.top >= screenHeight) return false

        // 被禁用的可点击控件仍然保留 —— 模型需要知道"有个按钮但现在点不了"，
        // 这比让它以为界面上没这东西要好

        return true
    }

    /** android.widget.Button → Button；自定义控件保留全名以免混淆 */
    private fun simplifyClass(raw: String): String {
        if (raw.isEmpty()) return "?"
        val simple = raw.substringAfterLast('.')
        return if (raw.startsWith("android.widget.") || raw.startsWith("android.view.")) {
            simple
        } else {
            simplifyCustomNames(simple)
        }
    }

    /** 常见自定义控件的名字太啰嗦，截短一点省 token */
    private fun simplifyCustomNames(name: String): String {
        // 去掉常见的后缀噪声
        return name
            .removeSuffix("Layout")
            .removeSuffix("ViewGroup")
            .take(24)
    }

    /**
     * 把节点列表渲染成给模型的文本。
     *
     * 这是提示词里最关键的一段 —— 模型靠它决定"点哪个"。
     */
    fun render(nodes: List<UiNode>): String {
        if (nodes.isEmpty()) return EMPTY_TEXT
        return nodes.joinToString("\n") { it.describe() }
    }
}
