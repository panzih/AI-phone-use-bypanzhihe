package com.aiphone.assistant.a11y

import android.graphics.Rect
import android.os.Build
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
 *
 * ## 密码屏蔽（ROADMAP §0，安全项不只是准确率）
 *
 * 密码框的文本在**解析这一步就被替换成掩码** —— 不是渲染时替换。这样它
 * 根本进不了 UiNode、进不了提示词、也进不了导出日志；身份匹配（按编号找回
 * 控件）用的文字两帧掩码一致，不受影响。
 */
data class UiNode(
    /** 编号，从 1 开始。模型就报这个 */
    val index: Int,
    /** 控件类名，简写过的，比如 Button / EditText */
    val className: String,
    /** 屏幕上的文字（密码框固定为掩码，见类注释） */
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
    // ---- ROADMAP §0 补的节点字段（全带默认值，旧构造点不受影响）----
    /** 密码框。文本已按 [PASSWORD_MASK] 屏蔽 */
    val isPassword: Boolean = false,
    /**
     * 选中态（标签页 / 单选的"当前是谁"）。和 [checked]（勾选框）是两回事：
     * checked 回答"开没开"，selected 回答"是不是当前这一个"。
     */
    val selected: Boolean = false,
    /** 标题（heading）：分区锚点，模型靠它识别"进入了哪个区块" */
    val heading: Boolean = false,
    /** 状态描述（API 30+）：「2/5」「已开启」这类系统给的一句话 */
    val stateDesc: String = "",
    /** 滑块类控件的当前值（API 30+ RangeInfo），已格式化（"40%" / "0.5"） */
    val rangeText: String = "",
    /** 纵向可滚（从 ACTION_SCROLL_UP/DOWN/FORWARD/BACKWARD 推出） */
    val canScrollVertically: Boolean = false,
    /** 横向可滚（从 ACTION_SCROLL_LEFT/RIGHT 推出） */
    val canScrollHorizontally: Boolean = false,
) {
    companion object {
        /** 密码框文本在解析层的统一掩码。定长，不泄露真实长度 */
        const val PASSWORD_MASK = "••••"
    }

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
            if (scrollable || canScrollVertically || canScrollHorizontally) add("可滚")
            if (editable) add("可输入")
            if (checked) add("已选")
            if (selected) add("当前")
            if (heading) add("标题")
            if (isPassword) add("密码")
            if (!enabled) add("禁用")
        }
        if (flags.isNotEmpty()) {
            sb.append(" [").append(flags.joinToString(",")).append(']')
        }
        if (stateDesc.isNotBlank()) sb.append(" 状态\"").append(stateDesc.take(20)).append('"')
        if (rangeText.isNotBlank()) sb.append(" 当前值 ").append(rangeText)
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
     * 抓树的时间预算（ROADMAP §0）。
     *
     * 深树（WebView、小程序、大列表）下遍历是**同步阻塞**的：以前只有节点数
     * 上限、没有时间上限，一次慢读能卡住整步（observe / 动作 / 翻页全等它）。
     * 超预算就**停下、如实上报**（[NodeWalk.budgetExceeded]），拿到半棵树
     * 总比整步卡死好 —— 列表顶部的东西通常先遍历到，恰恰是最重要的那部分。
     */
    const val TREE_BUDGET_MS = 500L

    /** 每走多少个节点检查一次时间预算（查时钟也有成本，不能每节点都查） */
    private const val BUDGET_CHECK_EVERY = 32

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
        /** 是否撞到了时间预算（深树没遍历完就被掐了，见 [TREE_BUDGET_MS]） */
        val budgetExceeded: Boolean = false,
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
            val isPassword = node.isPassword
            val rawText = node.text?.toString().orEmpty().trim()
            UiNode(
                index = i + 1,
                className = simplifyClass(node.className?.toString().orEmpty()),
                // 密码框：文本在解析这一步就换成定长掩码 —— 不进提示词、
                // 不进日志，也绝不能从任何导出文件里复原
                text = if (isPassword) UiNode.PASSWORD_MASK else rawText,
                contentDesc = node.contentDescription?.toString().orEmpty().trim(),
                viewId = node.viewIdResourceName.orEmpty(),
                bounds = Rect(rect),
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                enabled = node.isEnabled,
                checked = node.isChecked,
                isPassword = isPassword,
                selected = node.isSelected,
                heading = node.isHeading,
                stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.stateDescription?.toString().orEmpty().trim()
                } else {
                    ""
                },
                rangeText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.rangeInfo?.let { formatRange(it) }.orEmpty()
                } else {
                    ""
                },
                canScrollVertically = walk.scrollVertical[i],
                canScrollHorizontally = walk.scrollHorizontal[i],
            )
        }
        return ParseResult(nodes, walk.visited, walk.truncated, walk.budgetExceeded)
    }

    /** RangeInfo → 给模型看的一句当前值（"40%" / "0.5"） */
    private fun formatRange(r: android.view.accessibility.AccessibilityNodeInfo.RangeInfo): String =
        when (r.type) {
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT ->
                "${r.current.toInt()}%"
            else -> {
                val v = r.current
                if (v == v.toLong().toFloat()) "${v.toLong()}" else "$v"
            }
        }

    /** [keepNodesDetailed] 的结果 */
    data class NodeWalk(
        val nodes: List<AccessibilityNodeInfo>,
        val visited: Int,
        val truncated: Boolean,
        /** 与 [nodes] 一一对应：这个节点有没有纵向/横向滚动能力（从 action 推出） */
        val scrollVertical: List<Boolean> = List(nodes.size) { false },
        val scrollHorizontal: List<Boolean> = List(nodes.size) { false },
        /** 时间预算超了，树没走完（见 [TREE_BUDGET_MS]） */
        val budgetExceeded: Boolean = false,
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
        budgetMs: Long = TREE_BUDGET_MS,
    ): NodeWalk {
        if (root == null) return NodeWalk(emptyList(), 0, false)

        val out = ArrayList<AccessibilityNodeInfo>(limit)
        val scrollV = ArrayList<Boolean>(limit)
        val scrollH = ArrayList<Boolean>(limit)
        val seen = HashSet<String>()
        val rect = Rect()
        var visited = 0
        var budgetExceeded = false
        val startAt = System.nanoTime()

        // 深度优先遍历。用显式栈避免深层界面把递归栈打爆
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty() && out.size < limit) {
            // 时间预算：深树下遍历是同步阻塞，超了就掐断 —— 拿半棵树
            // 总比整步卡死好（"这一步超时、如实上报"，见常量注释）
            if (visited % BUDGET_CHECK_EVERY == 0 &&
                (System.nanoTime() - startAt) / 1_000_000L > budgetMs
            ) {
                budgetExceeded = true
                break
            }
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
                if (seen.add(key)) {
                    out.add(node)
                    val acts = node.actionList
                    var v = false
                    var h = false
                    for (a in acts) {
                        when (a.id) {
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id,
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id,
                            -> v = true

                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
                            -> h = true
                        }
                    }
                    // 老式列表只报 isScrollable、不给方向位：按纵向处理
                    //（RecyclerView/ListView 的 FORWARD/BACKWARD 都是纵向语义）
                    if (node.isScrollable && !v && !h) v = true
                    scrollV.add(v)
                    scrollH.add(h)
                }
            }

            // 子节点入栈。注意回收中间节点的做法这里没做，
            // 因为 API 33+ 才有 recycle()，而 minSdk 是 28
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        // out.size 撞到 limit 就认为"可能还有没发出去的" —— 保守表述，
        // 日志里写的是"已达上限"，不断言一定被砍了
        return NodeWalk(out, visited, out.size >= limit, scrollV, scrollH, budgetExceeded)
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
