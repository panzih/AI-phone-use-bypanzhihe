package com.aiphone.assistant.agent

import com.aiphone.assistant.a11y.UiNode

/**
 * 端侧意图执行器：把云端模型**显式下发**的“关闭弹窗”意图，在当前页面
 * 转译成一次具体点击。
 *
 * 端侧不自主决策 —— 只在模型明确要求（dismiss_dialog）时才被调用：
 * 找到安全的关闭按钮就点、找不到（或页面涉及授权/支付）就不操作，
 * 结果一律交回云端，由云端继续判断，端侧不会连续自作主张点错东西。
 *
 * 安全口径（与动作后验证一致）：
 *  - 页面上只要有“可点的授权/支付类正向按钮”，就不自动关闭 —— 否则会替
 *    用户点“拒绝/取消”，让需要授权、支付的任务静默失败；
 *  - “稍后/以后再说/暂不/跳过”等强弹窗词优先；“取消/关闭/✕”等通用词
 *    只在元素稀疏的弹窗点，复杂页面不自动点，避免误退页面、丢草稿。
 */
class LocalRuleEngine {

    /** 关闭弹窗的查找结果；[target] 为 null 表示没有可安全点击的关闭按钮 */
    data class DismissResult(
        val target: UiNode?,
        val reason: String,
    )

    /** 在当前页面找一个安全的关闭按钮 */
    fun findSafeDismiss(nodes: List<UiNode>): DismissResult {
        val clickable = nodes.filter { it.enabled && it.clickable }

        // 闸：页面有可点的授权/支付类正向按钮 → 不自动关闭
        val sensitive = clickable.any { n ->
            SENSITIVE_ACTION_WORDS.any { containsWord(labelOf(n), it) }
        }
        if (sensitive) {
            return DismissResult(null, "页面上有授权/支付类按钮，未自动关闭")
        }

        // 强弹窗词：几乎只出现在弹窗，优先点
        val strong = clickable.firstOrNull { n ->
            DISMISS_STRONG.any { containsWord(labelOf(n), it) } && isSafeDismiss(n)
        }
        if (strong != null) {
            return DismissResult(strong, "已点「${labelOf(strong).trim()}」")
        }

        // 通用关闭词：只在元素稀疏的弹窗点
        if (nodes.size <= SPARSE_PAGE_NODES) {
            val generic = clickable.firstOrNull { n ->
                DISMISS_GENERIC.any { containsWord(labelOf(n), it) } && isSafeDismiss(n)
            }
            if (generic != null) {
                return DismissResult(generic, "已点「${labelOf(generic).trim()}」")
            }
        }

        return DismissResult(null, "没找到安全的关闭按钮")
    }

    /** 按钮是否“安全可点”：不含副作用词、也不含正向推进词 */
    private fun isSafeDismiss(node: UiNode): Boolean {
        val label = labelOf(node)
        if (Agent.labelHasSideEffect(node)) return false
        if (POSITIVE_WORDS.any { containsWord(label, it) }) return false
        return true
    }

    private fun labelOf(n: UiNode): String =
        (n.text + " " + n.contentDesc).lowercase()

    /** 中文按子串、英文按整词（与 Agent 的口径一致） */
    private fun containsWord(label: String, word: String): Boolean {
        if (word.all { it in 'a'..'z' }) {
            return label.split(Regex("[^a-z]")).contains(word)
        }
        return label.contains(word)
    }

    companion object {
        /** 整页弹窗的元素数上限（超过就不算“稀疏弹窗”，通用关闭词不自动点） */
        private const val SPARSE_PAGE_NODES = 18

        // 强弹窗词：几乎只出现在弹窗，点了只是关闭，最可靠
        private val DISMISS_STRONG = listOf(
            "稍后", "以后再说", "暂不", "跳过", "不再提示", "暂不开启",
            "not now", "skip", "later", "no thanks", "dismiss",
        )

        // 通用关闭词：普通页面也可能出现，需页面稀疏才自动点
        private val DISMISS_GENERIC = listOf(
            "取消", "关闭", "忽略", "不用了", "拒绝",
            "cancel", "close", "ignore", "deny", "decline", "✕", "×", "✖",
        )

        // 正向/推进类词：出现在按钮上时不自动点（可能是授权/继续流程）
        private val POSITIVE_WORDS = listOf(
            "继续", "下一步", "知道了", "好的", "开启", "立即",
            "continue", "next", "got it", "enable", "start",
        )

        // 授权/支付类正向按钮词：页面上只要有这种可点按钮，就不自动关闭。
        // 故意不含“安装/更新”（否则升级弹窗标题含“安装新版本”会误伤“以后再说”）。
        private val SENSITIVE_ACTION_WORDS = listOf(
            "允许", "同意", "授权", "支付", "付款", "购买", "订阅", "转账",
            "permission", "allow", "grant", "authorize", "pay", "purchase", "subscribe",
        )
    }
}
