package com.aiphone.assistant.agent

import com.aiphone.assistant.a11y.UiNode

/**
 * 端侧弹窗规则：在当前页面里找一个**无副作用的关闭按钮**。
 *
 * ## 两种触发方式，两种权责
 *
 *   1. **模型显式下发** `dismiss_dialog` —— 云端的意图，端侧负责落地：
 *      `AgentSession.handleDismissDialog()` → `dismissByLocalRules()`，
 *      找到安全按钮才点。
 *   2. **端侧每步先看一眼** —— 读界面之前调用 `localDismissAdvice()`，
 *      发现挡路的无副作用弹窗**只给建议，不自己点**。点不点由云端决定
 *      （0.10.1 起；以前端侧会当场关掉，词表一旦误判，云端连发生过什么
 *      都不知道）。
 *
 * 两条路共用 [findSafeDismiss] 和同一套安全闸，不是两套判定。
 *
 * ## 端侧仍然不自主推进任务
 *
 * 它只负责识别"挡路的东西"，不产生任何任务进展：找到安全的关闭按钮就报告
 * （模型显式要求时才点）、找不到（或页面涉及授权/支付）就不操作，结果一律
 * 交回云端。做什么、下一步点哪，仍旧由模型决定。
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
        // 一票否决：会**杀掉应用**或清数据的按钮，任何情况都不自动点
        if (NEVER_DISMISS.any { containsWord(label, it) }) return false
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

        /**
         * **一票否决**：点了会杀掉应用或清数据的按钮，永远不自动点。
         *
         * 这一条是 2026-09-26 在模拟器上真撞出来的：当时屏幕上是系统弹的
         * **ANR（应用无响应）对话框**，按钮是「Close app / Wait」。端侧按通用
         * 关闭词匹配到了 `close` → 把那个无响应的应用关掉了。
         *
         * 那次关的是 SystemUI，无害；但**同一个弹窗可能长在 AI 正在操作的目标
         * 应用头上** —— 关掉它等于把用户的应用杀了、任务进度全丢，而且这是
         * 端侧自作主张干的，用户不会知道为什么应用没了。
         *
         * ⚠️ 顺序很关键：必须在通用关闭词**之前**判。`close app` 里的 `close`
         * 本身在 [DISMISS_GENERIC] 里，只靠词表顺序拦不住。
         */
        private val NEVER_DISMISS = listOf(
            "结束应用", "关闭应用", "强行关闭", "强制关闭", "停止应用", "杀掉", "终止应用",
            "清除数据", "卸载", "close app", "force close", "stop app", "kill app",
            "end app", "uninstall", "clear data", "force stop",
        )
    }
}
