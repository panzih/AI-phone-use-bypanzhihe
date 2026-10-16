package com.aiphone.assistant.agent

import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.log.RunLogger

/**
 * 「用户回到桌面 → 自动把目标应用迁到副屏」的**推断状态机**（0.8.3）。
 *
 * ## 为什么它值得单独一个类
 *
 * 这组状态（目标包名 / 任务栈 id / 新鲜度 / 自己刚按过 HOME / 失败冷却）
 * 原来散在 [Agent] 里，靠注释里"它们够不到、所以留个信标"的补丁维持 ——
 * 那是职责没拆干净的信号。这里的规则又完全是**推断**：
 * 用户回桌面**可能**意味着"想让我在副屏继续跑"，也可能只是想干别的。
 * 推断错了的代价是凭空切屏、把用户正在用的东西搬走，所以条件从严、
 * 宁可漏不可错。这套"从严的判据"应该有一个能被单独审视的位置。
 *
 * ## 线程约定
 *
 * [arm] 由循环每步刷新，[ready] 被 `interruption()` 每 100ms 读一次 ——
 * 后者只读内存，**零 binder**（迁移按钮点击路径的实时性全靠这条）。
 * 写状态的都是循环所在的那条协程；跨协程可见的只有 [armed]，标了 @Volatile。
 */
class AutoSwitchWatcher(
    /** 本应用包名 —— 目标不能是纸盒自己 */
    private val selfPackage: String,
    private val probe: ActivityProbe,
    private val logger: RunLogger?,
) {

    /** AI 最近在操作的目标 app 包名（过滤纸盒/桌面） */
    var targetPkg: String? = null
        private set

    /** 目标 app 的 taskId（包名变化时 dumpsys 一次缓存） */
    var targetTaskId: Int = -1
        private set

    /** 目标 app 最近在前台的时间，用于"够不够新"判定 */
    private var lastTargetAtMs: Long = 0L

    /** Agent 自己按 HOME 的时间，之后短时间内不自动触发 */
    private var selfHomeAtMs: Long = 0L

    /** 自动迁移失败/被压制的冷却截止时间 */
    private var autoMoveCooldownUntilMs: Long = 0L

    /** 自动切副屏使能（主屏 + Shizuku READY），每步刷新一次、interruption 只读它 */
    @Volatile
    private var armed = false

    /** 新任务开头把推断状态清干净（上一轮的目标和冷却不该影响这一轮） */
    fun reset() {
        targetPkg = null
        targetTaskId = -1
        lastTargetAtMs = 0L
        selfHomeAtMs = 0L
        autoMoveCooldownUntilMs = 0L
    }

    /** 每步开头刷新：主屏 + Shizuku READY 才可能自动迁移 */
    fun arm(enabled: Boolean) {
        armed = enabled
    }

    /**
     * 每步记录"AI 正在操作的目标 app"。
     *
     * 包名便宜（一次 rootInActiveWindow）；包名变化时才 dumpsys 拿 taskId。
     * 过滤纸盒自己和桌面（"目标"不能是它们）。
     */
    suspend fun observeForeground(pkg: String?) {
        if (pkg.isNullOrBlank() || pkg == selfPackage) return
        if (AutoService.get()?.isHomePackage(pkg) == true) return
        if (pkg != targetPkg) {
            targetPkg = pkg
            targetTaskId = probe.resolveTaskIdForPackage(pkg)
        }
        lastTargetAtMs = System.currentTimeMillis()
    }

    /** Agent 自己按过 HOME（让开 / 补按）—— 之后一小段时间不许自动迁移 */
    fun noteSelfHome() {
        selfHomeAtMs = System.currentTimeMillis()
    }

    /**
     * 自动迁移没成：记日志 + 冷却，避免停在桌面被反复尝试。
     * （触发检测在 [ready] 里；这里是"检测到该迁但迁不了"的善后）
     */
    fun markFailed(reason: String) {
        logger?.line("检测到回到桌面，但$reason，本次不自动迁移", "副屏")
        autoMoveCooldownUntilMs = System.currentTimeMillis() + AUTO_FAIL_COOLDOWN_MS
    }

    /**
     * 压住自动迁移一段时间。用户接管（把应用拉回主屏）后调用 ——
     * 不压的话"前台是目标应用 + 后来用户回桌面"会立刻把它搬回副屏，
     * 用户一拉回来又被搬走，来回打乒乓。
     */
    fun suppressAutoMoveFor(ms: Long) {
        autoMoveCooldownUntilMs = System.currentTimeMillis() + ms
    }

    /**
     * 是否满足"自动切副屏"（0.8.3，Q3 六条）。
     *
     * 每 100ms 被 `interruption()` 调一次 —— **只读内存、零 binder**，
     * 所以这里绝不能加任何查询调用；binder 数据由 [observeForeground]
     * 和 AutoService 的事件侧准备好。
     */
    fun ready(): Boolean {
        val now = System.currentTimeMillis()
        // ① 主屏 + Shizuku READY（arm() 每步刷新）
        if (!armed) return false
        // ② 目标包有效、非纸盒（桌面已在 observeForeground 过滤）
        val pkg = targetPkg ?: return false
        if (pkg == selfPackage) return false
        // ③ 目标 app 最近还在前台（够新）。
        // R4：长 sleep 期间没有步边界刷新时间戳，30s 新鲜度会误挡；补一条因果
        // 证明——这次回桌面就是从目标 app 切走的（packageBeforeDesktop==pkg）也成立。
        val fresh = now - lastTargetAtMs <= TARGET_FRESH_MS
        val fromTarget = AutoService.get()?.packageBeforeDesktop() == pkg
        if (!fresh && !fromTarget) return false
        // ④ 不在"自己刚按 HOME"的抑制窗口
        if (selfHomeAtMs != 0L && now - selfHomeAtMs < SELF_HOME_SUPPRESS_MS) return false
        // 失败冷却
        if (now < autoMoveCooldownUntilMs) return false
        // ⑤ 回桌面已持续 400ms 防抖（时间戳由无障碍事件写入）
        val since = AutoService.get()?.desktopSinceMs() ?: 0L
        if (since == 0L || now - since < DESKTOP_DEBOUNCE_MS) return false
        return true
    }

    companion object {
        /** 回到桌面后持续多久才认定是"用户真的回桌面"（防抖） */
        const val DESKTOP_DEBOUNCE_MS = 900L

        /** 目标 app 多少毫秒内还在前台才算"够新"，防止拿陈旧目标乱迁 */
        const val TARGET_FRESH_MS = 30_000L

        /** Agent 自己按 HOME 后多久内不自动触发（覆盖让开/补 HOME 过渡） */
        const val SELF_HOME_SUPPRESS_MS = 1_500L

        /** 自动迁移失败后的冷却，避免停在桌面被反复尝试 */
        const val AUTO_FAIL_COOLDOWN_MS = 10_000L
    }
}
