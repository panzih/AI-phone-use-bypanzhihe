package com.aiphone.assistant.agent

/**
 * 动作之后「界面稳了没有」的判定规则。
 *
 * 抽成纯函数是因为这段逻辑**只能靠单测钉住**：真机上跑一遍只能看到"这次快了点"，
 * 看不出"是不是把'还没开始动'误判成了'已经稳了'" —— 而那个错误的后果是
 * 模型拿到半渲染的界面、点错元素，表现为"AI 突然乱点"，很难回溯到这里。
 *
 * ## 两种判据，证据强度不一样
 *
 * **看到变化过**（[seenChange]）：动作发出之后，我们**亲眼观测到**界面指纹
 * 变过。这直接证明动作生效了、界面开始响应了。此时只要它随后静止够
 * [settleMs]，就可以放行 —— 证据比盲等强。
 *
 * **没看到变化**：分不清是"动作没生效"还是"界面还没来得及开始动"。
 * 这时候只能退回原来的保守做法 —— 等满 [minFloorMs] 再说话。
 * 原来的实现是**任何情况**都先盲等 [minFloorMs]，所以这一步是纯赚的：
 * 常见情况下（点一下就跳页）不用再盲等。
 */
internal object WaitPolicy {

    /**
     * 现在可以判定"界面已稳定"了吗？
     *
     * @param samplesEqual   最近两次采样的指纹是否相同（稳定的必要条件）
     * @param seenChange     从动作发出到现在，是否观测到界面指纹变化过
     * @param msSinceChange  距**最后一次**观测到变化过了多久
     * @param elapsedMs      从动作发出到现在总共过了多久
     * @param minFloorMs     保守兜底：没观测到变化时必须等满这么久
     * @param settleMs       观测到变化之后，还需要静止这么久才算稳
     */
    fun settled(
        samplesEqual: Boolean,
        seenChange: Boolean,
        msSinceChange: Long,
        elapsedMs: Long,
        minFloorMs: Long,
        settleMs: Long,
    ): Boolean {
        // 两次采样不一样 = 界面还在动，没得谈
        if (!samplesEqual) return false
        // 看到过变化：变化之后静止够了就放行。这里刻意不要求等满 minFloor ——
        // 我们的证据（看见它动过又停住）比盲等更强
        if (seenChange && msSinceChange >= settleMs) return true
        // 没看到变化：只能按保守值来
        return elapsedMs >= minFloorMs
    }

    /**
     * 是否已经到达想去的应用（open_app 的本地判据）。
     *
     * 冷启动的固定等待（原 [Agent.OPEN_STABLE_MIN_MS] 1500ms）对"秒开"的应用
     * 是纯浪费。本地能直接问系统"现在前台是谁" —— 目标包名到了前台、
     * 且界面静止够 [arrivedSettleMs]，就可以放行，不必等满 1.5 秒。
     *
     * 为什么敢比常规判据更激进：open_app 那一支**不使用**返回的指纹
     * （动作后验证对它不适用），提前放行的唯一后果是"早一点进入下一步"，
     * 而下一步会重新读界面。
     */
    fun arrived(
        targetPkg: String?,
        foregroundPkg: String?,
        samplesEqual: Boolean,
        msSinceArrival: Long,
        arrivedSettleMs: Long,
    ): Boolean {
        if (targetPkg.isNullOrBlank() || foregroundPkg != targetPkg) return false
        return samplesEqual && msSinceArrival >= arrivedSettleMs
    }
}
