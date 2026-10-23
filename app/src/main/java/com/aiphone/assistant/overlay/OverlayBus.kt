package com.aiphone.assistant.overlay

/**
 * 悬浮窗与 Agent 之间的桥。
 *
 * 为什么要这一层：Agent 跑在 Activity 的协程里，悬浮窗跑在 Service 里，
 * 但两者在**同一个进程**。用一个单例对象传话，比 bindService + 回调简单得多，
 * 而且不需要跨进程的 AIDL。
 *
 * 三个职责：
 *   1. Agent 告诉悬浮窗"现在第几步、下一步要做什么"
 *   2. Agent 在截图/注入前喊一声"藏一下"，事后喊"出来"
 *   3. 悬浮窗上的急停按钮，把停止请求传回 Agent
 */
/**
 * AI 当前处在循环的哪一段。
 *
 * 用户光看"第几步"其实不知道它在忙什么 —— 一次截图要几百毫秒，
 * 一次模型调用要一两秒，中间还有等待。把阶段显示出来，
 * 用户才知道"它没卡住，是在等模型"。
 */
enum class AgentPhase(val label: String, val color: Int) {
    IDLE("待命", 0xFF9E9E9E.toInt()),
    SCREENSHOT("截图中", 0xFF29B6F6.toInt()),
    UPLOADING("上传中", 0xFFFFA726.toInt()),
    WAITING_MODEL("等待大模型返回结果", 0xFFAB47BC.toInt()),

    /** 点击前复核：正在把落点局部图发给模型做二次确认 */
    REVIEWING("点击前复核", 0xFF64B5F6.toInt()),
    /**
     * 上一次请求没成，正在等一会儿重发。
     *
     * 单独一档而不复用「上传中」：用户最怕的就是"它是不是死了"，
     * 而"重发"这个词本身就说明了"它知道出问题了，正在自己处理"。
     */
    RETRYING("模型没响应，准备重发", 0xFFFF7043.toInt()),
    ACTING("正在操作手机", 0xFFEF5350.toInt()),

    /**
     * 正在拉起目标（开应用 / 开内置浏览器）。
     *
     * 和 [ACTING] 分开是因为这段时间**什么都还没点**：用户看到"正在操作手机"
     * 却半天没动静会以为坏了；写"正在启动"就知道是在等应用/网页起来。
     */
    LAUNCHING("正在启动", 0xFF42A5F5.toInt()),
    WAITING_SYSTEM("等待系统响应", 0xFF66BB6A.toInt()),

    /**
     * 用户按了暂停。和「待命」分开：待命是任务没跑，
     * 暂停是任务还在、只是停在半路等人，界面上一眼要能分清。
     */
    PAUSED("已暂停（可继续补充指令）", 0xFFFFB300.toInt()),
}

object OverlayBus {

    /**
     * 回迁原因的两个取值。定义在这里而不是 Agent 里 —— 存的是这边的字段，
     * 两边各写一份字面量迟早会对不上（而"对不上"的表现是冷却期静默失效，
     * 只会在真机上表现为"屏幕自己来回搬"，很难查）。
     */
    const val REASON_MANUAL = "用户手动"
    const val REASON_AUTO_RETURN = "纸盒回到前台"

    /** 服务实例。没起悬浮窗时是 null，所有方法都做了空判断 */
    @Volatile
    var service: OverlayService? = null

    /**
     * 悬浮条要不要背景模糊。任务开始时由界面按设置同步一次
     * （默认 false —— 原因见 AppSettings.overlayBlur）。
     */
    @Volatile
    var overlayBlurEnabled: Boolean = false

    /** 悬浮窗上按过急停。Agent 每步都会检查它 */
    @Volatile
    var stopRequested: Boolean = false
        private set

    /** 任务结束时由调用方清掉，否则下一次任务一开始就被判定为"已停止" */
    fun clearStop() {
        stopRequested = false
    }

    /**
     * 底部悬浮条的指标行：缓存命中率 / 首字节耗时 / 累计 token / 上下文占用。
     *
     * 由 Agent 在每次拿到模型结果后刷一次。**不是每帧都刷** ——
     * 这些数字只在"一次请求回来"时才有新值，中途刷新只是自欺欺人。
     *
     * @param contextPercent 上下文占用百分比（当前 prompt_tokens 占模型窗口的比例）。
     *                      null = 还没拿到过 usage（免费通道不一定给）。
     * @param totalTokens 本次任务**累计** token（输入+输出），不是本轮 ——
     *                   用户问的是"这一趟花了多少"，只看本轮看不出量级。
     */
    fun updateMetrics(
        hitPercent: Double?,
        ttftMs: Long,
        contextPercent: Int? = null,
        totalTokens: Int? = null,
    ) {
        service?.updateMetrics(hitPercent, ttftMs, contextPercent, totalTokens)
    }

    fun requestStop() {
        stopRequested = true
        // 停止优先于暂停：挂着"暂停"时按停止，要让等待循环立刻退出
        pauseRequested = false
    }

    // ------------------------------------------------------------------
    // 底部悬浮条上的三个按钮：补充 / 接管 / 后台（0.10.0）
    // ------------------------------------------------------------------

    /*
     * 用户 2026-10-20 定的映射（照抄，别自己改）：
     *
     *   补充 ＝ 原来的「暂停」（AI 停下，用户补一句话再继续）
     *   接管 ＝ 原来的「急停」（真的不要了，这趟结束）
     *   后台 ＝ 原来的「切到副屏」（AI 去副屏干，主屏还给用户）
     *
     * 所以这三个键**没有新状态**，全是已有语义的重新命名：
     *   补充 → [requestPause] / [togglePause]
     *   接管 → [requestStop]
     *   后台 → [requestMoveToVirtualDisplay] / [requestReturnFromVd]
     *
     * 保留命名映射而不是直接改旧 API 的名字，是为了不牵动 Agent 主循环
     * （那里 [pauseRequested] / [stopRequested] 出现几十处，改名风险大）。
     * 悬浮窗这一层负责把「补充/接管/后台」翻译成旧语义。
     */

    /**
     * 「补充」按钮按了。
     *
     * 语义 = 原来的「暂停」：AI 在下一个动作间隙停下，用户可以在主界面
     * 的输入框里补一句话，补完继续。悬浮窗上没法打字，所以这一步要靠
     * 主界面接手 —— 这里发一个**一次性信号**，主界面收到后把输入框亮起来
     * 等用户打字（真正的暂停由 [requestPause] 承担）。
     *
     * 做成 StateFlow 而不是普通 Boolean：主界面要的是"**又**有人按了补充"
     * 这个事件（同一个值 false→true→false 循环），普通 Boolean 观察不到
     * 重复的 true。
     */
    private val _supplementSignal = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** 主界面收集这个流：每次收到新值就把输入框亮起来 */
    val supplementSignal: kotlinx.coroutines.flow.StateFlow<Long> = _supplementSignal

    /** 悬浮窗按了「补充」：先暂停，再让界面把输入框亮起来等用户打字 */
    fun requestSupplement() {
        requestPause()
        // 用时间戳当值，并保证**严格递增**：同一毫秒内连按两次（用户猛戳）
        // 如果值相同，主界面那个 LaunchedEffect 不会重跑，第二次就白按了。
        _supplementSignal.value = maxOf(System.currentTimeMillis(), _supplementSignal.value + 1)
    }

    fun clearSupplement() {
        _supplementSignal.value = 0L
    }

    /**
     * 「接管」按钮按了 —— = 原来的「急停」。
     *
     * 语义：真的不要了，这趟结束。按下去之后 AI 不再读界面、不再发请求，
     * 任务正常收尾（历史完整保留，之后还能「复盘」）。
     *
     * 刻意**只转发 [requestStop]**、不另立一个标志：接管与急停本来就是同一件事
     * （用户 2026-10-20 的映射），多一个没人读的布尔只会让人以为两者有区别。
     */
    fun requestTakeover() {
        requestStop()
    }

    /**
     * 「后台」按钮按了 —— = 原来的「切到副屏」。
     *
     * 副屏模式下同一个按钮变成「回前台」（[requestReturnFromVd]）。
     * 状态复用 [moveToVdRequested] / [returnFromVdRequested]，不另开一套。
     */


    /**
     * 用户按了「暂停」。
     *
     * 和 [stopRequested] 是两件事：暂停**可恢复**，而且暂停期间用户还能往输入框里
     * 补一句话（见 [pushUserNote]）。Agent 在动作间隙把循环停在这里，
     * 恢复前不读控件树、不发请求 —— 所以它不是"假暂停"，是真的不花钱。
     */
    @Volatile
    var pauseRequested: Boolean = false
        private set

    /** Agent 真的停下来了（界面按钮与提示看它，不看 pauseRequested） */
    private val _paused = kotlinx.coroutines.flow.MutableStateFlow(false)
    val paused: kotlinx.coroutines.flow.StateFlow<Boolean> = _paused

    fun requestPause() {
        pauseRequested = true
    }

    fun requestResume() {
        pauseRequested = false
    }

    fun togglePause() {
        pauseRequested = !pauseRequested
    }

    /** 只有 Agent 调：把"真的停下来了"这件事广播出去（悬浮窗按钮文案跟着变） */
    fun markPaused(value: Boolean) {
        _paused.value = value
        service?.updateSupplementButton(value)
    }

    fun clearPause() {
        pauseRequested = false
        _paused.value = false
        // 按钮文案也要跟着回「暂停」：任务结束时不刷的话，悬浮窗会停在
        // 「继续」上，下一次任务看着像还没开始
        service?.updateSupplementButton(false)
    }

    /**
     * 暂停期间用户补充的指令。Agent 在组装下一条 user 消息时取走。
     *
     * 用队列而不是单个变量：用户可能连发两句（"顺便"补一句、"再"补一句），
     * 后一句盖掉前一句等于白说。
     */
    private val userNotes = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun pushUserNote(text: String) {
        if (text.isNotBlank()) userNotes.add(text.trim())
    }

    fun drainUserNotes(): List<String> = buildList {
        while (true) {
            val note = userNotes.poll() ?: break
            add(note)
        }
    }

    fun clearUserNotes() {
        userNotes.clear()
    }

    /**
     * 悬浮窗上按了「切到副屏」。Agent 在下一步开头（动作间隙）处理它。
     */
    @Volatile
    var moveToVdRequested: Boolean = false
        private set

    fun requestMoveToVirtualDisplay() {
        moveToVdRequested = true
    }

    /** Agent 处理完（或判定不能处理）后清掉 */
    fun clearMoveToVirtualDisplay() {
        moveToVdRequested = false
    }

    /**
     * 悬浮窗上按了「切回主屏」（副屏模式下那个绿按钮）。
     * Agent 在下一步开头（动作间隙）处理它，和 moveToVdRequested 同一位置。
     */
    @Volatile
    var returnFromVdRequested: Boolean = false
        private set

    /**
     * 这次回迁是谁要的 —— 只用于日志。
     *
     * 手动（悬浮钮）和自动（纸盒回前台）走的是同一条路，但排查问题时
     * 必须能分清"是用户按的"还是"我们自己搬的"，否则日志里只有一串
     * 「已切回主屏」看不出因果。
     */
    @Volatile
    var returnFromVdReason: String = REASON_MANUAL
        private set

    fun requestReturnFromVd(reason: String = REASON_MANUAL) {
        returnFromVdReason = reason
        returnFromVdRequested = true
    }

    /** Agent 处理完（或判定不能处理）后清掉 */
    fun clearReturnFromVd() {
        returnFromVdRequested = false
    }

    /**
     * 当前有没有任务在跑。
     *
     * 「纸盒回到前台 → 自动回迁」这条判据要有它：镜像页（MirrorActivity）是另一个
     * Activity，拿不到主界面 Compose 里的 `isRunning`。没有这个标志的话，
     * 用户随手开一下纸盒就会置一个回迁请求，等下一条任务第一步被消费掉 ——
     * 明明没进过后台，却被搬了一次。
     *
     * 由 MainActivity 在任务开始/结束时维护。
     */
    @Volatile
    var taskRunning: Boolean = false

    /**
     * 「自动回迁」的冷却截止时刻（epoch ms）。
     *
     * 为什么需要它（防乒乓，HANDOFF §9.4）：
     *
     *   用户按 HOME 回桌面 → 自动切副屏 → 用户点开纸盒看进度 → 自动回迁主屏
     *   → 用户又按 HOME → 又切副屏 → ……
     *
     * 每一次单看都合理，连起来就是自己搬来搬去、还在日志里刷屏。
     * 所以自动回迁成功后设一个 30 秒的静默期，期间不再自动触发。
     * **只压自动**，用户手动按悬浮钮任何时候都有效。
     *
     * 放在 OverlayBus 而不是 Agent 里，是因为触发方（MainActivity 的
     * ON_RESUME）和执行方（Agent）互相拿不到对方的实例，而这两个都是
     * 单例可达的。
     */
    @Volatile
    private var autoReturnSuppressUntilMs: Long = 0L

    fun suppressAutoReturnFor(ms: Long) {
        autoReturnSuppressUntilMs = System.currentTimeMillis() + ms
    }

    fun autoReturnSuppressed(): Boolean =
        System.currentTimeMillis() < autoReturnSuppressUntilMs

    /** 任务开始时清掉冷却，否则上一轮留下的静默期会压住这一轮 */
    fun clearAutoReturnSuppression() {
        autoReturnSuppressUntilMs = 0L
    }

    /**
     * 更新悬浮按钮：方向、文案、可点与否都由 Agent 决定，Service 不自己猜。
     *
     * @param enabled 可点为 true
     * @param text 按钮上完整文案（"切到副屏"、"切到副屏（需 Shizuku）"、"切回主屏"）
     * @param backMode true = 副屏模式（绿「切回主屏」）；false = 主屏模式（蓝「切到副屏」）
     */
    fun updateBackgroundButton(enabled: Boolean, text: String, backMode: Boolean = false) {
        service?.updateBackgroundButton(enabled, text, backMode)
    }

    /**
     * 正在录制「操作记录」。
     *
     * 录制期间没有 Agent 在跑，悬浮窗上那个按钮的含义变成了"停止录制"，
     * 所以要把两件事分开 —— 否则按下去会去置 Agent 的停止标志，
     * 而 Agent 根本没在跑，用户会觉得按钮坏了。
     */
    @Volatile
    var recordingMode: Boolean = false
        private set

    /** 录制模式下按了悬浮窗按钮 */
    @Volatile
    var recordingStopRequested: Boolean = false
        private set

    fun enterRecording() {
        recordingMode = true
        recordingStopRequested = false
    }

    fun exitRecording() {
        recordingMode = false
        recordingStopRequested = false
    }

    fun requestRecordingStop() {
        recordingStopRequested = true
    }

    /** 录制进度：显示"录制中 · 已记录 N 步" */
    fun updateRecording(count: Int) {
        service?.updateRecording(count)
    }

    val isShowing: Boolean get() = service != null

    /** 切换阶段，左上角那行状态会跟着变 */
    fun setPhase(phase: AgentPhase) {
        service?.updatePhase(phase)
    }

    /**
     * 某个坐标是不是落在底部按钮面板（「切到副屏」+「急停」）上。
     *
     * 注入**点击**之前用得上：面板是可触摸窗口，如果模型给的坐标正好
     * 落在它上面，这一下会被吃掉 —— 点到急停会停掉任务、点到切副屏会
     * 中途迁移，两个按钮都要算。只有真重叠时才藏悬浮窗，其余时候让它留着，
     * 用户才能看到"正在操作手机"这个状态。
     */
    fun overlapsOverlayButtons(x: Int, y: Int): Boolean =
        service?.overlapsOverlayButtons(x, y) ?: false

    /**
     * 更新左上角的状态卡。
     *
     * @param current 这一步正在做什么
     * @param nextHint 模型预测的**下一步**要做什么（给用户预判用）
     */
    fun update(step: Int, maxSteps: Int, current: String, nextHint: String) {
        service?.updateStatus(step, maxSteps, current, nextHint)
    }

    /**
     * 截图/注入之前把悬浮窗藏起来。
     *
     * 必须藏，两个原因：
     *   1. 无障碍截图抓的是整块屏幕，悬浮窗会出现在图里，
     *      模型会把它当成界面元素去点
     *   2. 底部那个按钮是可触摸窗口。不藏的话，注入的点击如果
     *      正好落在它上面，会被它吃掉 —— 甚至点到"急停"把自己停掉
     */
    /**
     * 在某个屏幕坐标闪一圈水波，让用户看见 AI 点在哪。
     *
     * 没有悬浮窗时就什么也不做 —— 这是纯视觉反馈，不该影响任务本身。
     */
    fun pulse(x: Int, y: Int) {
        service?.pulse(x, y)
    }

    fun hide() {
        service?.setVisible(false)
    }

    fun show() {
        service?.setVisible(true)
    }
}
