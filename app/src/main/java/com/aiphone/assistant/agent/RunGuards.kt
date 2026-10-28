package com.aiphone.assistant.agent

/**
 * 运行期的几条"什么时候该拦、什么时候该停"的判据。
 *
 * ## 为什么要单独抽出来
 *
 * 这几条全部来自 0.8.5 那份日志里的真实失败，而且**每一条都能被一个纯函数
 * 描述**。放在 [Agent] 里内联的话，只能靠真机重跑才能验证；抽出来之后
 * 可以用单测钉住，回归的时候改坏了会立刻红（见 RunGuardsTest）。
 *
 * 抽出来还顺带把三件事分清楚了：
 *   - "界面没变化"（动作没生效）
 *   - "读不到控件树"（无障碍没工作，是环境问题）
 *   - "没看一眼就宣布完成"（模型的判断没有依据）
 *
 * 这三件事混在一起，正是 0.8.5 日志里给出错误止损理由的原因。
 */
internal object RunGuards {

    /**
     * 能不能拿"界面和上一步一样"当判据。
     *
     * 读不到根节点、或这一屏压根没有元素时，指纹是一个**恒定值**，
     * 拿它比就是在比空气：连续几步必然"一模一样"，第 6 步一定撞上
     * "卡住了：界面连续 6 步没有任何变化"。0.8.5 里两次微信任务、
     * 一次「给李四发」全都死在这一条上，而界面其实一直在变。
     */
    fun treeObservable(rootAvailable: Boolean, elementCount: Int): Boolean =
        rootAvailable && elementCount > 0

    /**
     * 这一轮是不是"缓存断了"。
     *
     * 判据：上一轮的输入 `prevPromptTokens` 这一段，这一轮理应全部命中
     * （对话是严格追加的），实际命中却明显少了。
     *
     * @param slack 容差。官方的最小缓存单元是 64 token —— 尾部不到一个单元的
     *        那截本来就不会命中，所以留这么点余量，免得每步都误报
     */
    fun cacheBroke(prevPromptTokens: Int, hitTokens: Int, slack: Int = 64): Boolean =
        prevPromptTokens > slack && hitTokens + slack < prevPromptTokens

    /** 累加"界面没变化"的步数；不可观测时直接归零，不把空气算进去 */
    fun nextSameTreeCount(previous: Int, treeHash: Int, lastTreeHash: Int): Int =
        if (treeHash != 0 && treeHash == lastTreeHash) previous + 1 else 0

    /** 累加"连续读不到根节点"的步数；读到就清零 */
    fun nextUnreadableStreak(previous: Int, rootAvailable: Boolean): Int =
        if (rootAvailable) 0 else previous + 1

    /**
     * 该不该拦下这次"任务已完成"。
     *
     * 0.8.5 的现场：新任务「打开蓝牙」发出后，模型一个动作都没做就回
     * "上一批已点击蓝牙开关……任务完成"，summary 还是**上一个任务**
     * （关上蓝牙）的结论 —— 因为这一段接着旧上下文跑，历史里那条
     * assistant 已经 `finished=true`，而这一步它连界面元素都没拿到。
     *
     * 触发条件是三条同时成立（缺一条都会误伤正常情况）：
     *   1. 新任务的**第一步**；
     *   2. 带着上一段历史（历史里有旧任务的结论可复述）；
     *   3. 一个动作都没给就要收尾。
     *
     * @param alreadyRefused 已经拦过一次就不再拦 —— 模型第二次仍然坚持
     *        就该放行，避免和"任务其实已经完成"的正常情况来回拉锯
     */
    fun shouldRefuseBlindFinish(
        step: Int,
        carriedHistoryCount: Int,
        finished: Boolean,
        actionCount: Int,
        alreadyRefused: Boolean,
    ): Boolean = finished && actionCount == 0 && step == 1 &&
        carriedHistoryCount > 0 && !alreadyRefused

    /**
     * 收尾闸：模型说"完成"，但这一步**没有任何端侧证据**。
     *
     * 和 [shouldRefuseBlindFinish] 的分工：那条只管"新任务第一步 + 带旧历史"
     * 这一种最贵的复述，条件很窄；这条管的是所有"空口收尾"——
     * 包括跑到第 8 步突然说完成、而界面一步没动过的情形。
     *
     * "证据"只有两种，都由端侧产生：
     *   1. 这一步**真的执行过**动作（`executedActions > 0`）；
     *   2. 界面相对上一步**变了**（可观测的树、且指纹不同）。
     *
     * 模型自己的 `thought` / `summary` 不算证据 —— 那正是幻觉的来源。
     *
     * @param refusals 已经拦过几次。默认只拦一次：模型第二次仍然坚持就放行，
     *        避免和"任务其实已经完成"的正常情况来回拉锯（多一轮的代价
     *        比死循环小得多）
     */
    fun shouldRefuseUnverifiedFinish(
        finished: Boolean,
        executedActions: Int,
        interfaceChanged: Boolean,
        refusals: Int,
        maxRefusals: Int = 1,
    ): Boolean = finished &&
        executedActions == 0 &&
        !interfaceChanged &&
        refusals < maxRefusals

    /**
     * 端侧判定"可能卡住了"要**提醒**云端什么；null = 还没到该说的程度。
     *
     * ## 为什么从"止损"改成"提醒"（0.10.1）
     *
     * 用户 2026-10-21 定的权责：**云端模型拥有最终决定权**，端侧不如云端。
     * 以前这里是 `stuckReason`：连续 5 步没变化就直接 finish(false) ——
     * 那是端侧替云端判了死刑。实际上"界面没变化"可能只是自动等待没等到，
     * 模型换个做法（换元素、先要图、open_app 重开）就活了。
     *
     * 现在端侧只陈述事实，怎么处置由云端决定；硬停由
     * `AgentLimits.STUCK_ADVICE_LIMIT` 兜底（提醒几次仍无进展才停）。
     *
     * @param sameTree 连续"界面和上一步一样"的步数（只应在 [treeObservable] 为真时累加）
     * @param repeatAction 连续给出同一批动作的次数
     */
    fun stuckAdvice(sameTree: Int, repeatAction: Int, limit: Int): String? = when {
        sameTree >= limit -> "界面已经连续 ${sameTree + 1} 步没有任何变化"
        repeatAction >= limit -> "你连续 ${repeatAction + 1} 次给出了同一批动作"
        else -> null
    }

    /**
     * 老接口（等同 [stuckAdvice]，只是措辞是"判决"口吻）。
     *
     * 保留它是因为 **单测已按这个名字钉过**；调用方全部换成 [stuckAdvice]。
     * 真要删得改测试，没必要 —— 两个名字指向同一件事更省事。
     */
    fun stuckReason(sameTree: Int, repeatAction: Int, limit: Int): String? =
        stuckAdvice(sameTree, repeatAction, limit)

    /**
     * 连续读不到控件树要不要止损；返回给用户的原因，null = 继续。
     *
     * 这类失败**不是模型的错**：无障碍服务被系统停用了（有些 ROM 会在后台
     * 清掉），模型既看不到元素也点不准坐标。以前它会被报成"这个界面点不动"，
     * 把用户引到完全错的方向 —— 他去看那个界面，界面明明是好的。
     */
    fun unreadableTreeStop(consecutive: Int, limit: Int): String? {
        if (consecutive < limit) return null
        return "连续 $consecutive 步读不到控件树：无障碍服务很可能被系统停用了" +
            "（有些 ROM 会在后台清掉它），我看不到界面元素，只能靠截图猜坐标，" +
            "再试下去也不会准。请到「权限」页里把纸盒的无障碍" +
            "关掉再打开，然后重新发一次任务。"
    }
}
