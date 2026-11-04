package com.aiphone.assistant.a11y

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.aiphone.assistant.agent.AgentLimits

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

    /** 这一行的身份（文字 + 位置）。上下两帧对比"谁是新出现的"用它 */
    internal fun identityKey(): String =
        "$text|$contentDesc|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"

    /**
     * 给模型看的一行描述。
     *
     * ROADMAP §0 语义结构：中文角色（按钮/输入框/开关…）替代生硬的类名；
     * 命中敏感词表的标签带 ⚠（让模型在动手前多想一下，也方便复盘）。
     * 分区表头、缩进、`+`（相对上一帧新增）在 [UiTreeParser.render] 里按行拼。
     */
    fun describe(): String {
        val label = text.ifBlank { contentDesc }
        val sb = StringBuilder()
        sb.append('[').append(index).append("] ")
        if (label.isNotBlank() && AgentLimits.labelSensitive(label)) sb.append("⚠ ")
        sb.append(roleOf(className))
        sb.append(" \"").append(label.take(40)).append('"')
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

    /** 类名 → 中文角色。认不出的控件保留原类名，硬猜更误导 */
    internal fun roleOf(raw: String): String = when (raw) {
        "Button", "MaterialButton", "FloatingActionButton", "AppCompatButton" -> "按钮"
        "ImageButton" -> "图标按钮"
        "EditText", "TextField" -> "输入框"
        "TextView" -> "文本"
        "CheckBox" -> "复选框"
        "RadioButton" -> "单选"
        "Switch", "ToggleButton" -> "开关"
        "SeekBar", "Slider" -> "滑块"
        "ProgressBar" -> "进度条"
        "Spinner" -> "下拉框"
        "ImageView" -> "图标"
        "VideoView" -> "视频"
        "WebView" -> "网页容器"
        "CardView" -> "卡片"
        "RecyclerView", "ListView", "ScrollView" -> "滚动容器"
        else -> raw
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

    /** 原始节点的硬上限：防止极端界面把排序/吸收的内存和耗时撑爆 */
    private const val MAX_WALK_NODES = 3000

    /** 可点击行最多吸收几个后代的文字 */
    private const val ABSORB_MAX_TEXTS = 5

    /** 吸收进一行的总字数上限（一行太长也会把提示词撑爆） */
    private const val ABSORB_MAX_CHARS = 60

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
        /** 截断后没列出来的元素个数（0 = 全列出来了） */
        val remaining: Int = 0,
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
            // 可交互行吸收来的后代文字（③）：拼在自己文字后面 ——
            // "点这一行"点到的信息要一起给模型看。密码行不吸收
            val absorbed = walk.absorbedText[i]
            UiNode(
                index = i + 1,
                className = simplifyClass(node.className?.toString().orEmpty()),
                // 密码框：文本在解析这一步就换成定长掩码 —— 不进提示词、
                // 不进日志，也绝不能从任何导出文件里复原
                text = when {
                    isPassword -> UiNode.PASSWORD_MASK
                    absorbed.isNotBlank() -> "$rawText · $absorbed"
                    else -> rawText
                },
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
        return ParseResult(
            nodes,
            walk.visited,
            walk.truncated,
            walk.budgetExceeded,
            walk.remaining,
        )
    }

    /** RangeInfo → 给模型看的一句当前值（"40%" / "0.5"） */
    private fun formatRange(r: AccessibilityNodeInfo.RangeInfo): String =
        when (r.type) {
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT -> "${r.current.toInt()}%"
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
        /** 与 [nodes] 一一对应：从可交互后代那里吸收来的补充文字（"" = 没有） */
        val absorbedText: List<String> = List(nodes.size) { "" },
        /** 时间预算超了，树没走完（见 [TREE_BUDGET_MS]） */
        val budgetExceeded: Boolean = false,
        /** 结构预算截掉、没发给模型的元素个数 */
        val remaining: Int = 0,
    )

    /**
     * 只做遍历和过滤，返回**原始节点**（不做后代吸收、不给吸收文本），
     * 顺序和 [parse] 的编号一一对应。按编号找节点（findNodeByIndex）走这里。
     */
    fun keepNodes(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        limit: Int = 60,
        excludePackage: String? = null,
    ): List<AccessibilityNodeInfo> =
        keepNodesDetailed(root, screenWidth, screenHeight, limit, excludePackage).nodes

    /**
     * 只做遍历和过滤，返回**原始节点**，顺序和 [parse] 的编号一一对应。
     *
     * 为什么必须共用这一份：编号是模型定位的唯一依据，而"按编号点"的时候
     * 要重新解析一遍树（节点对象不能跨帧复用）。如果两处各写一套过滤规则，
     * 编号就会错位 —— 模型说点 3 号，实际点到了 5 号，而且完全看不出来。
     *
     * ## ROADMAP §0：空间排序 + 结构优先预算 + 后代吸收
     *
     *  - **空间排序**：以前是逆序深度优先（`addLast`/`removeLast`），编号顺序
     *    跟阅读顺序毫无关系，撞上限时先丢的还可能是屏幕顶部。现在全部按
     *    "从上到下、从左到右"排序后再编号。
     *  - **结构优先**：可交互元素（可点/可长按/可滚/可输入）**全保留**，
     *    纯文字元素只填剩余名额；被挤掉的个数如实在 [NodeWalk.remaining] 报告。
     *  - **后代吸收**：可点击/可输入行把非交互后代的文字并进自己那行
     *    （TalkBack 的朗读单元，也是模型的语义单元）；被吸收的后代不再单列，
     *    不然同一句话出现两遍。
     *
     * @return 保留下来的节点，第 n 个对应编号 n+1
     */
    fun keepNodesDetailed(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        limit: Int = 60,
        excludePackage: String? = null,
        budgetMs: Long = TREE_BUDGET_MS,
    ): NodeWalk {
        if (root == null) return NodeWalk(emptyList(), 0, false)

        val kept = ArrayList<AccessibilityNodeInfo>(limit)
        val scrollBits = java.util.IdentityHashMap<AccessibilityNodeInfo, BooleanArray>()
        val absorbedText = java.util.IdentityHashMap<AccessibilityNodeInfo, String>()
        // 被可交互祖先吸收的后代：不再单列（同一句话出现两遍只会浪费 token）
        val absorbed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        )
        val seen = HashSet<String>()
        val rect = Rect()
        var visited = 0
        var budgetExceeded = false
        val startAt = System.nanoTime()

        // 深度优先遍历。用显式栈避免深层界面把递归栈打爆
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty() && visited < MAX_WALK_NODES) {
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
            // 已被外层可交互行吸收：跳过（也不再走它的子树 —— 那已经走过了）
            if (absorbed.contains(node)) continue

            if (isWorthKeeping(node, rect, screenWidth, screenHeight)) {
                val text = node.text?.toString().orEmpty().trim()
                val desc = node.contentDescription?.toString().orEmpty().trim()

                // 同样的位置 + 同样的文字，通常是重复节点，只留一个
                val key = "${text}|${desc}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
                if (seen.add(key)) {
                    kept.add(node)
                    scrollBits[node] = scrollFlagsOf(node)
                    // 吸收非交互后代的文字（③）：可点击/可输入行是语义单元
                    if (node.isClickable || node.isEditable) {
                        absorbedText[node] = absorbDescendants(node, absorbed)
                    }
                }
            }

            // 子节点入栈。注意回收中间节点的做法这里没做，
            // 因为 API 33+ 才有 recycle()，而 minSdk 是 28
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        // ---- 空间排序：从上到下、从左到右（行容差按屏高切 24 行）----
        val ordered = readingOrder(kept, screenHeight)

        // ---- 结构优先预算：可交互全留，纯文字填空位，最后重新按空间序展示 ----
        val out: List<AccessibilityNodeInfo> = if (ordered.size <= limit) {
            ordered
        } else {
            val structural = ordered.filter { isStructural(it) }
            val textual = ordered.filterNot { isStructural(it) }
            val keepStruct = structural.take(limit)
            val slotsLeft = limit - keepStruct.size
            val keepText = if (slotsLeft > 0) textual.take(slotsLeft) else emptyList()
            readingOrder(keepStruct + keepText, screenHeight)
        }
        val remaining = (ordered.size - out.size).coerceAtLeast(0)

        return NodeWalk(
            nodes = out,
            visited = visited,
            truncated = ordered.size > out.size,
            scrollVertical = out.map { scrollBits[it]?.get(0) ?: false },
            scrollHorizontal = out.map { scrollBits[it]?.get(1) ?: false },
            absorbedText = out.map { absorbedText[it].orEmpty() },
            budgetExceeded = budgetExceeded,
            remaining = remaining,
        )
    }

    /** 结构元素：模型必须能看到的（可点 / 可长按 / 可滚 / 可输入） */
    private fun isStructural(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isLongClickable || node.isScrollable || node.isEditable

    /** 从 accessibility action 推滚动方向；老式列表只有 isScrollable 时按纵向 */
    private fun scrollFlagsOf(node: AccessibilityNodeInfo): BooleanArray {
        var v = false
        var h = false
        for (a in node.actionList) {
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
        if (node.isScrollable && !v && !h) v = true
        return booleanArrayOf(v, h)
    }

    /**
     * 把 [parent] 的非交互后代文字吸收进一行；返回拼好的补充文本。
     *
     * 吸收走的节点记进 [sink]，调用方保证它们不再单列。上限
     * [ABSORB_MAX_TEXTS] 条 / [ABSORB_MAX_CHARS] 字：吸收是补语义的，
     * 不是把整棵子树抄一遍。
     */
    private fun absorbDescendants(
        parent: AccessibilityNodeInfo,
        sink: MutableSet<AccessibilityNodeInfo>,
    ): String {
        val parts = ArrayList<String>(ABSORB_MAX_TEXTS)
        var chars = 0
        var depth = 0
        var level = listOf(parent)
        while (depth < 3 && parts.size < ABSORB_MAX_TEXTS) {
            val next = ArrayList<AccessibilityNodeInfo>()
            for (n in level) {
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    next.add(c)
                    val interactive = c.isClickable || c.isLongClickable ||
                        c.isScrollable || c.isEditable
                    if (interactive || !c.isVisibleToUser) continue
                    val t = (c.text?.toString()?.trim().orEmpty())
                        .ifBlank { c.contentDescription?.toString()?.trim().orEmpty() }
                    if (t.isBlank() || t.length < 2) continue
                    // 已有文字里就有的不重复吸收
                    if (parts.any { it.contains(t) }) continue
                    val take = t.take(30)
                    if (chars + take.length > ABSORB_MAX_CHARS) continue
                    parts.add(take)
                    chars += take.length
                    sink.add(c)
                    if (parts.size >= ABSORB_MAX_TEXTS) break
                }
                if (parts.size >= ABSORB_MAX_TEXTS) break
            }
            level = next
            depth++
        }
        return parts.joinToString(" / ")
    }

    /** 从上到下、从左到右的阅读顺序；行容差按屏高切 24 行 */
    private fun readingOrder(
        nodes: List<AccessibilityNodeInfo>,
        screenHeight: Int,
    ): List<AccessibilityNodeInfo> {
        if (nodes.size <= 1) return nodes
        val rect = Rect()
        val centers = nodes.map { n ->
            n.getBoundsInScreen(rect)
            rect.centerX() to rect.centerY()
        }
        val band = maxOf(64, screenHeight / 24)
        return nodes.indices
            .sortedWith(compareBy({ centers[it].second / band }, { centers[it].first }))
            .map { nodes[it] }
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
     *
     * ## ROADMAP §0 语义结构
     *
     *  - **分区**：[screenHeight] > 0 时插入 顶部/中部/底部 表头（0-20% / 20-80% / 80%+），
     *    模型一眼知道"返回键在顶部"这类空间常识；
     *  - **缩进**：被更大的可滚容器包住的行缩进两格，列表的归属可见；
     *  - **`+` 标记**：给了 [previous]（上一帧）时，新出现的元素带 `+` ——
     *    "点了之后多了什么"是模型确认动作生效最快的线索；
     *  - 每行的 ⚠ / 中文角色在 [UiNode.describe] 里。
     */
    fun render(
        nodes: List<UiNode>,
        screenHeight: Int = 0,
        previous: List<UiNode>? = null,
    ): String {
        if (nodes.isEmpty()) return EMPTY_TEXT
        val prevKeys = previous?.map { it.identityKey() }?.toHashSet()
        val scrollables = nodes.filter {
            (it.scrollable || it.canScrollVertically || it.canScrollHorizontally) &&
                it.bounds.width() * it.bounds.height() > 0
        }
        val sb = StringBuilder()
        var lastZone = -1
        for (n in nodes) {
            if (screenHeight > 0) {
                val zone = zoneOf(n.centerY, screenHeight)
                if (zone != lastZone) {
                    if (lastZone != -1) sb.append('\n')
                    sb.append(zoneHeader(zone)).append('\n')
                    lastZone = zone
                }
            }
            val insideScroller = scrollables.any {
                it !== n &&
                    it.bounds.width() * it.bounds.height() >
                    n.bounds.width() * n.bounds.height() &&
                    it.bounds.contains(n.centerX, n.centerY)
            }
            if (insideScroller) sb.append("  ")
            if (prevKeys != null && n.identityKey() !in prevKeys) sb.append('+')
            sb.append(n.describe()).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** 0 = 顶部（0-20%），1 = 中部，2 = 底部（80%+） */
    private fun zoneOf(centerY: Int, screenHeight: Int): Int = when {
        centerY < screenHeight * 0.2 -> 0
        centerY > screenHeight * 0.8 -> 2
        else -> 1
    }

    private fun zoneHeader(zone: Int): String = when (zone) {
        0 -> "── 顶部 ──"
        2 -> "── 底部 ──"
        else -> "── 中部 ──"
    }
}
