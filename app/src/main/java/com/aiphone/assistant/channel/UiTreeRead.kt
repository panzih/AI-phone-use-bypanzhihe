package com.aiphone.assistant.channel

import com.aiphone.assistant.a11y.UiNode

/**
 * 一次"读控件树"的完整结果。
 *
 * ## 为什么不能只返回一段文本
 *
 * 旧接口是 `dumpUiTree(): String?`，而 [com.aiphone.assistant.a11y.UiTreeParser.render]
 * 在**没有元素**时会返回一句占位文本（`EMPTY_TEXT`）。于是：
 *
 *   - 无障碍服务根本没生效、`rootInActiveWindow` 返回 null 时，接口拿到的
 *     不是 null 而是一句占位语 → 上层那句 `tree == null`（读不到就走截图）
 *     **永远不会命中**，模型既没有元素列表也没有图，纯瞎猜；
 *   - 日志把占位语按行数成"控件树：1 个元素"，导出包里完全看不出
 *     "其实是 0 个元素 / 根节点都没读到"。0.8.5 那份日志里一整批失败
 *     （微信、蓝牙、中信证券第一次）全是这个根因，却被这行日志盖住了。
 *
 * 所以这里把三件事分开：
 *   - [rootAvailable]：无障碍**到底读没读到根节点**。false 说明服务没真正工作；
 *   - [nodes]：真正发给模型的元素个数（空就是空，不用数文本行）；
 *   - [text]：给模型看的文本，**没有元素时为 null** —— 让"读不到树"这件事
 *     在类型上就是一个明确的分支，而不是靠字符串内容去猜。
 *
 * @param text 给模型看的元素列表；null = 这一屏没有元素列表可用
 * @param rawNodeCount 遍历到的原始节点数（含被过滤掉的容器），0 表示根节点都没拿到
 * @param truncated 是否撞到了上限（界面元素比发给模型的更多）
 */
data class UiTreeRead(
    val text: String?,
    val nodes: List<UiNode> = emptyList(),
    val rootAvailable: Boolean = false,
    val rawNodeCount: Int = 0,
    val truncated: Boolean = false,
) {
    /** 这一屏有没有真正可用的元素列表 */
    val hasElements: Boolean get() = nodes.isNotEmpty()

    companion object {
        /** 读不到（副屏通道、或无障碍服务没工作） */
        fun unavailable(): UiTreeRead = UiTreeRead(text = null)
    }
}
