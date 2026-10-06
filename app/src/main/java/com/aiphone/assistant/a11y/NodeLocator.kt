package com.aiphone.assistant.a11y

import kotlin.math.abs
import kotlin.math.max

/**
 * 按"元素身份"重新在**当前这一帧**的控件树里找回同一个控件。
 *
 * ## 解决的是哪一类错点
 *
 * 模型看的是**第 N 步开头**那一份元素列表，但动作真正执行是在
 * 模型往返（1~5 秒）之后。这中间界面可能重排（桌面图标加载完、
 * 列表滚动、页面刷新），于是"编号 30"在新的一帧里已经是**另一个控件**。
 *
 * 旧实现（`AccessibilityChannel.tapByIndex`）在点击的那一刻重新解析树，
 * 然后直接取 `第 30 个` 去点 —— 看起来"点到了"，其实点错了东西，
 * 日志里只会写一句"已执行但界面没变化"，从 0.8.5 的日志里根本查不出来。
 * 高德那一段「时间澔韵华景园」点不动、反复重试，就是这一类。
 *
 * 所以改成：拿模型当时看到的那个节点当**线索**（hint），在新一帧里按
 * 身份匹配；匹配不上就**如实失败**，绝不盲点第 N 个。
 *
 * ## 匹配强度
 *
 * 从强到弱：资源 id 相同 > 文字相同 > 类名 + 位置接近 > 仅位置接近（编号兜底）。
 * 位置容差按屏宽的 2%（最小 24px）算 —— 分辨率、密度、状态栏高度差异
 * 都会让绝对坐标差几十像素，容差太小会把真控件判丢。
 *
 * 纯函数、无 Android 依赖，所以能直接跑 JVM 单测（见 NodeLocatorTest）。
 */
object NodeLocator {

    /** 匹配结果：命中的节点 + 依据（写日志用，复盘时才知道是怎么找回来的） */
    data class Match(val node: UiNode, val reason: String)

    /** 位置容差：屏宽的 2%，至少 24px */
    fun tolerance(screenWidth: Int): Int = max(24, screenWidth / 50)

    /**
     * 在一帧新节点里找回 [hint]。
     *
     * @param hint 模型看的那一份列表里的节点
     * @param nodes 当前这一帧的节点（编号已按新帧重排）
     * @return 命中时带依据；找不到返回 null（调用方必须如实报失败，不能退化成按编号点）
     */
    fun locate(hint: UiNode, nodes: List<UiNode>, screenWidth: Int): Match? {
        if (nodes.isEmpty()) return null
        val tol = tolerance(screenWidth)

        var best: Match? = null
        var bestScore = 0
        for (cand in nodes) {
            val (score, reason) = score(hint, cand, tol)
            // 同分时保留先出现的那个（遍历顺序和编号一致，更接近模型看到的顺序）
            if (score > bestScore) {
                bestScore = score
                best = Match(cand, reason)
            }
        }
        return if (bestScore >= MIN_SCORE) best else null
    }

    /**
     * 打分。返回 (分数, 依据)，分数 0 表示完全不像。
     *
     * 权重是刻意分开的：**能唯一标识控件的东西（资源 id / 文字）压过位置**。
     * 只看位置的话，列表里相邻两行会互相匹配错。
     */
    fun score(hint: UiNode, cand: UiNode, tolerance: Int): Pair<Int, String> {
        // ---- 先看"明显不是同一个控件"的情况 ----
        // 两边都有文字（或都有描述）却不一样 → 直接判不是。
        // 少了这一条，"类名 + 位置接近"会把列表里**相邻的另一行**认成目标，
        // 于是又回到"点错控件"那个老问题上（那正是本类要消灭的行为）。
        val textConflict = hint.text.isNotBlank() && cand.text.isNotBlank() && hint.text != cand.text
        val descConflict = hint.contentDesc.isNotBlank() && cand.contentDesc.isNotBlank() &&
            hint.contentDesc != cand.contentDesc
        if (textConflict || descConflict) return 0 to ""

        val idSame = hint.viewId.isNotBlank() && hint.viewId == cand.viewId
        val textSame = hint.text.isNotBlank() && hint.text == cand.text
        val descSame = hint.contentDesc.isNotBlank() && hint.contentDesc == cand.contentDesc
        val classSame = hint.className == cand.className
        val near = nearCenter(hint, cand, tolerance)
        val sameIndex = hint.index == cand.index

        var s = 0
        val why = mutableListOf<String>()
        if (idSame) { s += 5; why.add("资源id") }
        if (textSame) { s += 4; why.add("文字") }
        else if (descSame) { s += 3; why.add("描述") }
        if (classSame) { s += 2; why.add("类名") }
        if (near) { s += 2; why.add("位置") }
        if (sameIndex) { s += 1; why.add("编号") }

        // 位置接近但"文字/描述/资源 id"一个都不沾的，极可能是列表里相邻的
        // 另一个控件 —— 这种"纯位置匹配"不能算身份匹配，直接判不可靠。
        val identityHit = idSame || textSame || descSame
        if (!identityHit && !(classSame && near)) return 0 to ""

        return s to why.joinToString("+")
    }

    private fun nearCenter(a: UiNode, b: UiNode, tolerance: Int): Boolean =
        abs(a.centerX - b.centerX) <= tolerance && abs(a.centerY - b.centerY) <= tolerance

    /**
     * 认定为"同一个控件"的最低分。
     *
     * 实际能达到这个分的组合是：资源 id（5）、文字（4）、描述（3）、
     * 类名 + 位置（4）、资源 id + 任意（≥6）……
     * 只有编号（1 分）或只有位置（0 分）一律不算 —— 它们正是"点错控件"的来源。
     */
    const val MIN_SCORE = 3
}
