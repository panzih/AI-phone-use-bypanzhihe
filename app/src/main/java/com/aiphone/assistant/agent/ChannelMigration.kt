package com.aiphone.assistant.agent

import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.display.MirrorActivity
import com.aiphone.assistant.log.RunLogger
import com.aiphone.assistant.overlay.AgentPhase
import com.aiphone.assistant.overlay.OverlayBus
import com.aiphone.assistant.shell.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 主屏 ↔ 副屏的通道迁移（0.8.0 引入，0.8.1 补回迁、批 3 补接管检测）。
 *
 * ## 职责边界
 *
 * 这个类只做一件事：**把「用户/AI 想换屏」变成实际的动作** ——
 * 建/销毁虚拟屏、整栈搬迁、失败时降级重开、校验、切换读取通道。
 * 它**不碰**决策循环的判据（指纹、重复计数那些由调用方在
 * [switched] 回调里清），也不自己按 HOME（让开前台是循环的事，
 * 通过 [pressHome] 注进来）。
 *
 * ## 为什么要有它
 *
 * 这几段逻辑原先长在 `Agent.run()` 的旁边，和循环状态互相勾着：
 * 换通道要清的那套判据是 `run()` 的**局部变量**，外面够不到，
 * 于是留了个"信标"字段由循环开头消费 —— 那是职责没拆开的信号。
 * 拆出来之后，迁移的每个出口（迁出/回迁/用户接管）都走同一个
 * [switched] 回调，清理这件事只有一处实现，不会再漏。
 *
 * ## 调用时机（这是通道切换的安全前提）
 *
 * 全部调用点都在**动作间隙**：循环每步读控件树之前、或等待
 * 界面稳定的轮询里。此刻没有注入在进行，release 旧通道不会打断
 * 正在注入的手势。
 */
class ChannelMigration(
    private val controller: ChannelController,
    private val probe: ActivityProbe,
    private val watcher: AutoSwitchWatcher,
    /** 本应用包名（前台是纸盒时，目标取"上次操作的应用"） */
    private val selfPackage: String,
    private val logger: RunLogger?,
    /** 给用户看的消息（走 Agent 的 Listener） */
    private val onEvent: (Agent.EventKind, String, String?) -> Unit,
    /**
     * 换通道成功后的回调（有序）：调用方在这里把"界面变了没有"的判据清零、
     * 给模型留一行"编号作废"的说明。**每个迁移出口都必须走到它**，
     * 否则会出现"换屏后第一次指纹比对必然不同"引发的误报。
     */
    private val switched: (to: String) -> Unit,
    /** 把纸盒让到后台（Agent 的让位逻辑；含"自己按了 HOME"的抑制记录） */
    private val pressHome: suspend () -> Unit,
) {

    /**
     * 本次任务里是否已经成功建过副屏（建屏成功即置位，销毁后清位）。
     *
     * 兜底失败路径：建屏成功、但整栈迁移和副屏重开都失败、直接返回时，
     * 通道还没切（isVirtualDisplay 仍 false），收尾时也不能漏销毁副屏。
     */
    private var vdCreatedByAgent = false

    /**
     * 「用户把应用挪回主屏自己用了」的连续命中次数（副屏模式）。
     *
     * 要连续 [TAKEOVER_STRIKES] 次都成立才认定 —— 切通道那一瞬间
     * `dumpsys` 会出现"两边都没有"的中间态，只看一次会误判成用户接管，
     * 而误判的后果是平白把还在正常跑的任务掐掉。
     */
    private var takeoverStrikes = 0

    // ------------------------------------------------------------------
    // 手动：用户点了悬浮按钮
    // ------------------------------------------------------------------

    /**
     * 手动「切到副屏」：从主屏顶部任务拿目标，再走 [performMoveToVirtualDisplay]。
     *
     * @return true 已切到副屏；false 没切（原因已给用户看，留在主屏继续）
     */
    suspend fun moveRequestedByUser(): Boolean {
        if (controller.isVirtualDisplay) {
            logger?.line("已经在副屏运行，忽略重复的切换请求", "副屏")
            return false
        }
        val top = probe.currentTaskOnDisplay(0) ?: run {
            // 0.9.18：启动/切屏空窗期 dumpsys 可能短暂读不到 topResumed
            // （蓝叠实证：open_app 刚发出、B站冷启动中 → 读不到 taskId →
            // 一次点击白白拒绝）。等 800ms 重试一次再放弃。
            delay(800)
            probe.currentTaskOnDisplay(0)
        }
        if (top == null) {
            val msg = "迁移失败：没能从系统读到当前前台任务（taskId）。"
            logger?.error(msg, "副屏")
            onEvent(Agent.EventKind.ERROR, msg, "副屏")
            return false
        }
        val (taskId, pkg) = top
        // 桌面/启动器**不能搬**：搬它 = force-stop 桌面 + 试图在副屏拉启动器
        // （系统不让 home 跑在虚拟屏上，副屏必然是空的，AI 就瞎了 —— 0.9.16 的
        // 线上事故）。这两种情况和"前台是纸盒自己"一样，都改迁 **AI 正在操作的
        // 那个应用**；真没目标就拒绝，把话说清楚。
        val isHome = isHomePackage(pkg)
        if (pkg == selfPackage || isHome) {
            val t = watcher.targetPkg
            if (t.isNullOrBlank() || isHomePackage(t)) {
                val msg = if (isHome)
                    "现在前台是桌面/启动器，没有可搬到副屏的应用 —— 先让 AI 打开要操作的应用，再切到副屏。"
                else
                    "请先让 AI 打开要操作的应用，再切到副屏（现在前台还是纸盒自己，也没记录到目标应用）。"
                logger?.line(msg, "副屏")
                onEvent(Agent.EventKind.THOUGHT, msg, "副屏")
                return false
            }
            val tid = watcher.targetTaskId.takeIf { it >= 0 && probe.taskExists(it) }
                ?: probe.resolveTaskIdForPackage(t)
            if (tid < 0) {
                val msg = "没找到「$t」的任务栈，先让 AI 打开它再切副屏（可能已经被关掉了）。"
                logger?.line(msg, "副屏")
                onEvent(Agent.EventKind.THOUGHT, msg, "副屏")
                return false
            }
            logger?.line(
                if (isHome) "前台是桌面，改为迁移 AI 正在操作的应用：$t"
                else "前台是纸盒自己，改为迁移上次操作的目标应用：$t",
                "副屏",
            )
            return performMoveToVirtualDisplay(tid, t, automatic = false).also { moved ->
                if (moved) openMirrorPage()
            }
        }
        return performMoveToVirtualDisplay(taskId, pkg, automatic = false).also { moved ->
            // 切过去之后把镜像页弹出来。以前手动切到副屏，用户**完全看不到**
            // AI 在那块屏上干什么 —— 副屏是虚拟的，切过去主屏就回桌面了，
            // 画面没有任何出口。这是"手动切屏"独有的空缺（定时任务走副屏时
            // MainActivity 已经会弹，那条路不受影响）。
            //
            // 只手动切时弹：自动迁出是**用户回桌面**触发的，这时候弹镜像
            // 等于把他从桌面拽走，是打扰。
            if (moved) openMirrorPage()
        }
    }

    /**
     * 弹出副屏镜像页（拿到副屏画面去看 AI 在干什么）。
     *
     * 从应用进程启动 Activity 必须带 NEW_TASK。后台启动 Activity 在 Android 10+
     * 默认被拦，但纸盒持有悬浮窗权限（SYSTEM_ALERT_WINDOW），属于系统放行的
     * 白名单，所以这条路通。万一拉不起来也只是"看不到画面"，
     * **不影响任务本身**，所以这里只记一行日志、不抛错。
     */
    fun openMirrorPage() {
        val id = controller.virtualDisplayId ?: return
        runCatching {
            controller.appContext.startActivity(
                MirrorActivity.intent(controller.appContext, id)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { logger?.warn("副屏镜像页没能弹出：${it.message}", "副屏") }
    }

    // ------------------------------------------------------------------
    // 自动：用户回桌面，把目标应用迁过去
    // ------------------------------------------------------------------

    /**
     * 自动迁出（0.8.3）：触发判据由 [AutoSwitchWatcher.ready] 给出，
     * 这里负责"动手前复核 + 拿目标任务栈 + 执行迁移"。
     *
     * @return true 已切到副屏；false 没切（原因已记日志/进冷却）
     */
    suspend fun autoMove(): Boolean {
        val pkg = watcher.targetPkg
        if (pkg.isNullOrBlank()) {
            watcher.markFailed("没有记录到要操作的目标应用")
            return false
        }
        // 护栏2：触发后、动手前最后复核——前台已离开桌面（新 app 被切到前台）
        // 就中止本次迁移、进冷却、不建屏。currentPackage 为 null（切换中）则不拦。
        val nowFg = withContext(Dispatchers.IO) { controller.currentPackage() }
        if (nowFg != null && AutoService.get()?.isHomePackage(nowFg) != true) {
            watcher.markFailed("已离开桌面（$nowFg）")
            return false
        }
        var taskId = watcher.targetTaskId
        if (taskId < 0 || !probe.taskExists(taskId)) taskId = probe.resolveTaskIdForPackage(pkg)
        if (taskId < 0) {
            watcher.markFailed("没找到 $pkg 的任务栈（可手动点切到副屏）")
            return false
        }
        return performMoveToVirtualDisplay(taskId, pkg, automatic = true)
    }

    // ------------------------------------------------------------------
    // 手动：用户点了「切回主屏」
    // ------------------------------------------------------------------

    /**
     * 把副屏上正在操作的 app 整栈迁回主屏，切回无障碍通道，再销毁副屏。
     * [moveRequestedByUser] 的反向。
     *
     * @return true 已切回主屏；false 没切（原因已给用户看，留在副屏继续）
     */
    suspend fun returnRequestedByUser(): Boolean {
        if (!controller.isVirtualDisplay) {
            logger?.line("已经在主屏运行，忽略重复的回迁请求", "副屏")
            return false
        }
        // exitVirtualDisplay 会把 display 置 null，先把 id 抓进局部
        val vdId = controller.virtualDisplayId
        if (vdId == null) {
            val msg = "回迁失败：读不到副屏编号。"
            logger?.error(msg, "副屏")
            onEvent(Agent.EventKind.ERROR, msg, "副屏")
            return false
        }

        // 1) 副屏顶部任务的包名 + taskId（在已知 vdId 段里找，不猜"第一个非零段"）
        val top = probe.currentTaskOnDisplay(vdId)
        if (top == null) {
            val msg = "回迁失败：没能从副屏读到当前任务（taskId）。"
            logger?.error(msg, "副屏")
            onEvent(Agent.EventKind.ERROR, msg, "副屏")
            return false
        }
        val (taskId, pkg) = top

        // 2) 先切回主屏无障碍（move-stack 走 shell、与通道无关；先切降级才自洽）
        OverlayBus.setPhase(AgentPhase.ACTING)
        controller.exitVirtualDisplay()

        // 3) 整栈迁回主屏；失败降级为「主屏重开应用」
        val keptStack = moveStack(taskId, 0)
        if (!keptStack) {
            logger?.line("整栈回迁失败，将在主屏重开应用（临时内容可能丢失）", "副屏")
            onEvent(Agent.EventKind.THOUGHT, "回迁失败，应用将被重开（临时内容可能丢失） …", "副屏")
            if (!reopenOnDisplay(pkg, 0)) {
                // 通道已回主屏、应用又被 force-stop：销毁副屏兜底，再报错
                val dr = ShizukuBridge.destroyDisplay(controller.appContext)
                vdCreatedByAgent = false
                val tail = if (dr >= 0) "（副屏已收回）" else "（副屏没收回，可到副屏调试页手动销毁）"
                val msg = "在主屏重开应用也失败了，应用已被关闭，需要重新打开$tail。"
                logger?.error(msg, "副屏")
                onEvent(Agent.EventKind.ERROR, msg, "副屏")
                return false
            }
        }

        // 4) 校验：任务确实回到主屏
        if (!probe.verifyTaskOnDisplay(taskId, 0)) {
            logger?.line("回迁后校验没通过（主屏段没找到该任务）", "副屏")
            onEvent(Agent.EventKind.RESULT, "回迁可能没成功，主屏没找到该应用", "副屏")
        }

        // 5) 任务在主屏但没获焦（顶部还是桌面）：把已有 task 抬到前台，保留深层页
        if (!probe.isTopResumedOnDisplay(taskId, 0)) {
            logger?.line("任务已回主屏但没在前台，尝试把它抬到前台", "副屏")
            bringTaskToFront(taskId, pkg)
        }

        // 6) 任务确认回主屏后，销毁副屏、清位
        val dr = ShizukuBridge.destroyDisplay(controller.appContext)
        vdCreatedByAgent = false
        if (dr < 0) {
            logger?.line("副屏没收回（$dr），可到副屏调试页手动销毁", "副屏")
        }

        val why = OverlayBus.returnFromVdReason
        logger?.line("已切回主屏（display=0），AI 恢复无障碍操作（原因：$why）", "副屏")
        onEvent(Agent.EventKind.ACTION, "已切回主屏运行", "副屏")
        // 自动触发的那次要进 30s 静默期，否则会和"按 HOME 自动切副屏"打成乒乓球
        if (why != RETURN_REASON_MANUAL) OverlayBus.suppressAutoReturnFor(AUTO_RETURN_COOLDOWN_MS)
        switchChannel("主屏")
        return true
    }

    // ------------------------------------------------------------------
    // 用户接管：应用被从副屏拉回主屏
    // ------------------------------------------------------------------

    /**
     * 用户可能把应用从副屏**挪回主屏**自己去用。这时副屏是空的，
     * 再跑下去就是对着一块空屏烧 token、而且每一步都在"看不到东西"的
     * 前提下瞎猜。
     *
     * 判据要两条同时成立：副屏上没有前台任务 + 目标应用出现在主屏。
     * 连查两次都成立才认定（切通道瞬间会有短暂的"两边都没有"）。
     *
     * 认定后**切回主屏继续跑**，而不是停下 —— 依据是项目自己定的方案
     * （else/工作汇报-0.8.5.md §附加触发源）："若被用户从主屏或最近任务
     * 列表拉回主屏，则判定用户接管，自动切回主屏模式继续"。外部项目
     * ShadowAuto 也把"用户把被自动化的应用拉回主屏"列为会打断自动化的已知情况。
     *
     * @return true 刚完成一次接管处理（调用方应 `continue` 进下一步）；
     *         false 没发生/还在确认中，继续正常流程
     */
    suspend fun detectTakeover(): Boolean {
        if (!controller.isVirtualDisplay) return false
        val vdId = controller.virtualDisplayId
        val tPkg = watcher.targetPkg
        if (vdId == null || tPkg.isNullOrBlank() || probe.currentTaskOnDisplay(vdId) != null) {
            takeoverStrikes = 0
            return false
        }
        val onMain = probe.currentTaskOnDisplay(0)
        if (onMain?.second != tPkg) {
            takeoverStrikes = 0
            return false
        }
        takeoverStrikes++
        if (takeoverStrikes < TAKEOVER_STRIKES) {
            logger?.warn("副屏上没有前台应用，且「$tPkg」在主屏 —— 再确认一次", "接管")
            return false
        }
        takeoverStrikes = 0
        // 这里不用 returnRequestedByUser()：那套是给"应用还在副屏"写的，
        // 要搬栈；而此刻应用**已经在主屏**了，只需要换读取通道。
        controller.exitVirtualDisplay()
        switchChannel("主屏")
        watcher.suppressAutoMoveFor(TAKEOVER_COOLDOWN_MS)
        val msg = "检测到你把「$tPkg」挪回主屏了，AI 已切回主屏模式继续" +
            "（${TAKEOVER_COOLDOWN_MS / 1000} 秒内不会再自动搬去副屏）。"
        logger?.line(msg, "接管")
        onEvent(Agent.EventKind.THOUGHT, msg, "接管")
        return true
    }

    // ------------------------------------------------------------------
    // 收尾与悬浮按钮
    // ------------------------------------------------------------------

    /** 任务收尾：只要还在副屏、或建过副屏（哪怕迁移失败提前返回），都销毁，不留残屏（0.8.0 验收⑤） */
    suspend fun destroyOnFinish() {
        if (!controller.isVirtualDisplay && !vdCreatedByAgent) return
        if (controller.isVirtualDisplay) controller.exitVirtualDisplay()
        val dr = ShizukuBridge.destroyDisplay(controller.appContext)
        vdCreatedByAgent = false
        logger?.line(
            if (dr >= 0) "副屏已随任务结束销毁" else "副屏销毁没成功（$dr），可到副屏调试页手动销毁",
            "副屏",
        )
    }

    /**
     * 刷新悬浮按钮：
     * 副屏模式 → 绿「切回主屏」可点；主屏模式 → 蓝「切到副屏」、仅 Shizuku READY 可点。
     */
    suspend fun refreshMoveButton() {
        if (controller.isVirtualDisplay) {
            OverlayBus.updateBackgroundButton(true, "回前台", backMode = true)
            return
        }
        when (ShizukuBridge.state(controller.appContext)) {
            ShizukuBridge.State.READY ->
                OverlayBus.updateBackgroundButton(true, "后台")
            ShizukuBridge.State.NOT_INSTALLED ->
                OverlayBus.updateBackgroundButton(false, "后台（安装）")
            ShizukuBridge.State.NOT_RUNNING ->
                OverlayBus.updateBackgroundButton(false, "后台（启动）")
            ShizukuBridge.State.NO_PERMISSION ->
                OverlayBus.updateBackgroundButton(false, "后台（授权）")
        }
    }

    // ------------------------------------------------------------------
    // 内部：迁移的执行体与 shell 操作
    // ------------------------------------------------------------------

    /** 换通道成功的统一出口：先让调用方清判据，再刷新按钮（顺序与原实现一致） */
    private suspend fun switchChannel(to: String) {
        switched(to)
        refreshMoveButton()
    }

    /**
     * 建副屏 → 整栈迁移（失败重开）→ 校验 → 主屏回桌面 → 切副屏通道。
     * 手动（[moveRequestedByUser]）和自动（[autoMove]）共用。
     */
    private suspend fun performMoveToVirtualDisplay(
        taskId: Int,
        pkg: String,
        automatic: Boolean,
    ): Boolean {
        val ctx = controller.appContext
        // 0.9.18：迁移全程应用会被系统短暂拉前台（建屏、切通道、弹镜像页），
        // 这些 self-inflicted 的 resume 不能被当成"用户要回主屏"去触发自动回迁
        // —— 蓝叠实证：手动切副屏成功 0.8 秒后被自动回迁弹回主屏。
        // 迁移一开始就压 10 秒，盖住整个窗口；期间用户要回主屏随时可以按
        // 悬浮窗上的「切回主屏」（手动按钮不走这条压制）。
        OverlayBus.suppressAutoReturnFor(MIGRATION_SUPPRESS_AUTO_RETURN_MS)
        // 切之前抓主屏物理尺寸（副屏照抄它）
        val mainSize = controller.screenSize()?.takeIf { it.first > 0 && it.second > 0 }
        if (mainSize == null) {
            logger?.error("迁移失败：拿不到主屏分辨率。", "副屏")
            return false
        }

        // 1) 建副屏（ShellService 内部已有则复用）
        OverlayBus.setPhase(AgentPhase.ACTING)
        val vdId = ShizukuBridge.createDisplay(ctx)
        if (vdId < 0) {
            val why = ShizukuBridge.lastError ?: "未知原因"
            val msg = "建副屏失败，留在主屏继续：$why"
            logger?.error(msg, "副屏")
            onEvent(Agent.EventKind.ERROR, msg, "副屏")
            return false
        }
        vdCreatedByAgent = true

        // 2) 整栈迁移；失败则降级为「重开应用」
        val keptStack = moveStack(taskId, vdId)
        if (!keptStack) {
            logger?.line("整栈迁移失败，将重开应用（临时内容可能丢失）", "副屏")
            onEvent(Agent.EventKind.THOUGHT, "迁移失败，正在副屏重开应用 …", "副屏")
            if (!reopenOnDisplay(pkg, vdId)) {
                // 副屏已建好、应用又被 force-stop：立即销毁副屏，不必等任务结束
                val dr = ShizukuBridge.destroyDisplay(ctx)
                vdCreatedByAgent = false
                val tail = if (dr >= 0) "（副屏已收回）" else "（副屏没收回，可到副屏调试页手动销毁）"
                val msg = "在副屏重开应用也失败了，应用已被关闭，需要重新打开$tail。"
                logger?.error(msg, "副屏")
                onEvent(Agent.EventKind.ERROR, msg, "副屏")
                watcher.suppressAutoMoveFor(AutoSwitchWatcher.AUTO_FAIL_COOLDOWN_MS)
                return false
            }
            // 重开命令返回成功 ≠ 应用真的在副屏上画出来了。以前不看结果就切过去，
            // 遇到拉不起来的应用，AI 面对一块空屏、第一步就是「抓不到画面」。
            // 等它出现（冷启动要几秒），等不到就收手、留在主屏 —— 空屏绝不上。
            if (!awaitTaskOnDisplay(pkg, vdId, timeoutMs = 5_000)) {
                val dr = ShizukuBridge.destroyDisplay(ctx)
                vdCreatedByAgent = false
                val tail = if (dr >= 0) "（副屏已收回）" else "（副屏没收回，可到副屏调试页手动销毁）"
                val msg = "「$pkg」在副屏上等了 5 秒也没出现，已留在主屏$tail。" +
                    "可能这个应用被限制后台启动，或者它在这台设备上拉不起来。"
                logger?.error(msg, "副屏")
                onEvent(Agent.EventKind.ERROR, msg, "副屏")
                watcher.suppressAutoMoveFor(AutoSwitchWatcher.AUTO_FAIL_COOLDOWN_MS)
                return false
            }
        }

        // 3) 校验：整栈迁移时该 task 确实到了新 display
        if (keptStack && !probe.verifyTaskOnDisplay(taskId, vdId)) {
            logger?.line("迁移后校验没通过，继续按副屏模式跑", "副屏")
            onEvent(Agent.EventKind.RESULT, "没在副屏找到该应用，可能显示为空屏", "副屏")
        }

        // 4) 主屏回桌面（必须在切通道前，HOME 走主屏无障碍）。
        // R3：前台已是桌面就不再按 HOME——自动场景用户本就在桌面，再按会把
        // “正在冷启动、还没发窗口事件”的 app 压回（反例2 真因）；手动场景才按。
        val fgBeforeHome = withContext(Dispatchers.IO) { controller.currentPackage() }
        if (AutoService.get()?.isHomePackage(fgBeforeHome) != true) {
            pressHome()
        }

        // 5) 切通道（此刻无注入在进行）
        controller.enterVirtualDisplay(vdId, mainSize)

        // 5.5) 0.9.18 首帧预检：「应用在副屏上活着」≠「画得出来」。蓝叠实证：
        // B站 START 后半秒被搬上副屏，任务在、activity 也在，但窗口永远没画
        // 出来 —— AI 面对一块读不到树、截不了图的死屏，只能止损。抓得到一帧
        // 才承认迁移成功；抓不到就整体回滚，绝不让 AI 盲跑。
        if (awaitVdFirstFrame() == null) {
            return rollBackMigration(taskId, vdId, pkg)
        }

        val lead = if (automatic) "已检测到你回到桌面，自动" else "已"
        logger?.line("${lead}切到副屏（display=$vdId），AI 改用截图 + 坐标操作", "副屏")
        onEvent(Agent.EventKind.ACTION, "${lead}切到副屏运行", "副屏")
        switchChannel("副屏")
        return true
    }

    /**
     * 副屏首帧预检（0.9.18）：抓到一帧才算迁移成功。
     *
     * `captureFrame` 内部自带 ~1 秒的首帧重试；这里再给几轮机会 ——
     * 冷启动的应用画出第一帧要几秒。健康路径第一轮就过（帧已经在），
     * 全部失败才认定这块副屏救不回来。
     */
    private suspend fun awaitVdFirstFrame(): ByteArray? {
        repeat(FRAME_PROBE_ATTEMPTS) { attempt ->
            val shot = runCatching {
                withContext(Dispatchers.IO) { controller.captureFrame() }
            }.getOrNull()
            if (shot != null && shot.isNotEmpty()) return shot
            if (attempt < FRAME_PROBE_ATTEMPTS - 1) delay(400)
        }
        logger?.error(
            "副屏首帧预检失败：${controller.lastScreenshotError() ?: "抓不到画面"}",
            "副屏",
        )
        return null
    }

    /**
     * 迁移验收不过关的统一回滚（0.9.18）：副屏通道切回主屏、应用搬回主屏并抬到
     * 前台、销毁副屏、如实报错。`vdCreatedByAgent` 一并清位，收尾不重销。
     */
    private suspend fun rollBackMigration(taskId: Int, vdId: Int, pkg: String): Boolean {
        val ctx = controller.appContext
        controller.exitVirtualDisplay()
        moveStack(taskId, 0)
        bringTaskToFront(taskId, pkg)
        val dr = ShizukuBridge.destroyDisplay(ctx)
        vdCreatedByAgent = false
        switchChannel("主屏")
        val tail = if (dr >= 0) "（副屏已收回）" else "（副屏没收回，可到副屏调试页手动销毁）"
        val msg = "「$pkg」搬到副屏后没能真正跑起来（抓不到画面），已整体搬回主屏继续$tail。" +
            "可能是应用刚启动就被搬、或它在这台设备的虚拟屏上画不出来。"
        logger?.error(msg, "副屏")
        onEvent(Agent.EventKind.ERROR, msg, "副屏")
        watcher.suppressAutoMoveFor(AutoSwitchWatcher.AUTO_FAIL_COOLDOWN_MS)
        return false
    }

    /**
     * 把已存在的 task 抬到主屏前台、尽量不重建页面。
     * 先试 `am task focus`；不行再 resolve launcher 组件、用 NEW_TASK 把现有 task 带上来
     * （不带 CLEAR_TOP，投递给现有实例、保留深层页）。
     */
    private suspend fun bringTaskToFront(taskId: Int, pkg: String) {
        val ctx = controller.appContext
        val focusOut = ShizukuBridge.run(ctx, "am task focus $taskId")
        delay(300)
        if (focusOut.isBlank() && probe.isTopResumedOnDisplay(taskId, 0)) return

        val resolved = ShizukuBridge.run(ctx, "cmd package resolve-activity --brief $pkg")
        val component = resolved.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("$pkg/") } ?: return
        ShizukuBridge.run(ctx, "am start --display 0 -n $component -f 0x10000000")
    }

    /**
     * 整栈迁移：`am display move-stack <根任务id> <displayId>`。成功无输出。
     *
     * ⚠️ Android 13 上这条命令要的是**根任务** id —— 拿子任务 id 去发会得到
     * `Unknown rootTaskId=<id>`（桌面/启动器的活动几乎总在子任务里）。
     * 所以先用 [ActivityProbe.rootTaskIdOf] 把根 id 查出来。
     */
    private suspend fun moveStack(taskId: Int, displayId: Int): Boolean {
        val rootId = probe.rootTaskIdOf(taskId)
        if (rootId != taskId) {
            logger?.line("任务 $taskId 是子任务，按根任务 $rootId 迁移", "副屏")
        }
        val out = ShizukuBridge.run(
            controller.appContext,
            "am display move-stack $rootId $displayId",
        )
        val ok = out.isBlank()
        if (!ok) logger?.line("move-stack 输出：${out.trim()}", "副屏")
        return ok
    }

    /**
     * 这个包是不是桌面/启动器。
     *
     * 优先用无障碍服务收集的集合（实测准）；服务没连上时退回 PackageManager
     * 解析默认桌面（ADB 模式下无障碍也可能没连，别让"认不出桌面"把 force-stop
     * 桌面的事故再演一遍）。
     */
    private fun isHomePackage(pkg: String): Boolean {
        if (AutoService.get()?.isHomePackage(pkg) == true) return true
        if (homePkgFallback.isEmpty()) {
            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            val info = runCatching {
                controller.appContext.packageManager.resolveActivity(homeIntent, 0)
            }.getOrNull()
            info?.activityInfo?.packageName?.let { homePkgFallback.add(it) }
        }
        return pkg in homePkgFallback
    }

    /** PackageManager 兜底解析出来的桌面包名（懒加载，可能为空 = 解析不到） */
    private val homePkgFallback = mutableSetOf<String>()

    /**
     * 等「pkg 的任务出现在 displayId 上」。
     *
     * 重开命令返回成功 ≠ 窗口已经画出来（冷启动要几秒）。轮询到出现或超时。
     */
    private suspend fun awaitTaskOnDisplay(pkg: String, displayId: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe.currentTaskOnDisplay(displayId)?.second == pkg) return true
            delay(400)
        }
        return false
    }

    /**
     * 降级：force-stop 后在指定屏重开应用。
     * 先 `cmd package resolve-activity` 拿启动组件，再 start 到目标屏。
     */
    private suspend fun reopenOnDisplay(pkg: String, displayId: Int): Boolean {
        val ctx = controller.appContext
        ShizukuBridge.run(ctx, "am force-stop $pkg")
        val resolved = ShizukuBridge.run(ctx, "cmd package resolve-activity --brief $pkg")
        val component = resolved.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$pkg/") }
            ?: return false
        val out = ShizukuBridge.startOnDisplay(ctx, component, displayId)
        return out.isBlank() ||
            (!out.contains("Error", true) && !out.contains("Exception", true))
    }

    companion object {
        /**
         * 首帧预检的轮数（0.9.18）。
         *
         * 每轮一次 `captureFrame`（内部自带 ~1s 首帧重试）+ 轮间 400ms，
         * 全部失败 = 这块副屏救不回来，走回滚。健康路径第一轮就过，
         * 不增加正常迁移的耗时。
         */
        const val FRAME_PROBE_ATTEMPTS = 4

        /**
         * 弹镜像页后压制「自动回迁」的时长（0.9.18）。
         *
         * 迁移全程应用会被系统短暂拉前台（建屏、切通道、弹镜像页），这些
         * self-inflicted 的 resume 不该被当成"用户要回主屏"。10 秒盖住整个
         * 迁移窗口 + 镜像页弹出；用户真要回主屏，随时可以按悬浮窗上的
         * 「切回主屏」（手动按钮不走这条压制）。
         */
        const val MIGRATION_SUPPRESS_AUTO_RETURN_MS = 10_000L

        /**
         * 「用户把应用挪回主屏」要连续命中几次才认定（副屏模式，批 3 附加项）。
         *
         * 2 次是为了滤掉切通道瞬间"两边都没有"的中间态；代价是最多白跑一步。
         */
        const val TAKEOVER_STRIKES = 2

        /**
         * 判定「用户接管」之后，多久内不再自动把应用搬去副屏。
         *
         * 一拉回来就被搬走、再拉回来再被搬走，来回打乒乓比不搬更烦人。
         * 一分钟够用户把手上那点事做完；真要搬，悬浮窗上的「切到副屏」随时能按
         * （那个按钮不受这条冷却影响）。
         */
        const val TAKEOVER_COOLDOWN_MS = 60_000L

        /** `OverlayBus.returnFromVdReason` 的"用户手动"取值，用来区分要不要进冷却（批 3） */
        val RETURN_REASON_MANUAL = OverlayBus.REASON_MANUAL

        /**
         * 「自动回迁」成功后的静默期（批 3，防乒乓）。
         *
         * 30 秒是 HANDOFF §9.4 定的：足够长到用户点开纸盒看一眼、再自己按 HOME
         * 回桌面时不会立刻又被搬回去；又短到"用户真的想继续在副屏上跑"时
         * 不至于等太久。**只压自动**，手动按悬浮钮不受影响。
         */
        const val AUTO_RETURN_COOLDOWN_MS = 30_000L
    }
}
