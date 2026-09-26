package com.aiphone.assistant.agent

import com.aiphone.assistant.a11y.UiNode

/**
 * 端侧意图执行器：把「关掉当前弹窗」这个意图，在当前页面转译成一次具体点击。
 *
 * ## 两种触发方式，同一套安全闸
 *
 *   1. **模型显式下发** `dismiss_dialog` —— 云端的意图，端侧负责落地；
 *   2. **端侧每步主动清场** —— 读界面之前先看一眼有没有挡路的无副作用弹窗，
 *      有就当场关掉（见 `Agent.dismissBlockingDialogLocally`）。
 *
 * 第 2 条是后来加的：第 1 条要「模型看到弹窗 → 下发 → 再看一遍」，
 * 白花整整一轮网络 + 推理（通常 1~3 秒），而这个判断本身不需要模型 ——
 * 词表和安全闸一直就在本地。两种触发**走的是同一个函数**，不是两套逻辑，
 * 所以放宽的只是"谁来决定何时调用"，判定标准一个字没动。
 *
 * ## 端侧仍然不自主推进任务
 *
 * 它只负责"把挡路的东西挪开"，不产生任何任务进展：找到安全的关闭按钮就点、
 * 找不到（或页面涉及授权/支付）就不操作，结果一律交回云端。
 * 做什么、下一步点哪，仍旧由模型决定。
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
