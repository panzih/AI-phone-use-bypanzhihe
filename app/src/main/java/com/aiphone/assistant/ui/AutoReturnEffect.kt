package com.aiphone.assistant.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiphone.assistant.agent.Agent
import com.aiphone.assistant.overlay.OverlayBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 需求 F 的触发源：「纸盒被带回前台 → 自动从副屏回迁主屏」。
 *
 * ## 为什么要单独抽出来（2026-09-26 实测发现的坑）
 *
 * 这个效果原先只挂在 `MainActivity` 上。但真机/标准模拟器上实测：
 * **用户点纸盒图标，落在的是 `MirrorActivity`（副屏镜像页），不是主界面** ——
 * 因为它是在主界面之后启动的，位于任务栈顶。
 *
 * 结果就是：需求 F 最核心的那个场景（"用户打开纸盒"）**根本不触发**。
 * 只有用 CLEAR_TOP 把主界面强行拉到前台才触发，那不是真实用户路径。
 *
 * 所以这里抽成一个共享效果，**主界面和镜像页都挂一份**，两者共用同一套判据。
 *
 * ## 判据（和 HANDOFF §9.4 一致）
 *
 * 1. **必须真的进过后台**：同一个 Activity 先 ON_STOP 再 ON_RESUME 才算。
 *    只看 ON_RESUME 不行 —— 界面第一次创建、配置变化都会来一次 resume。
 *    这条也顺带排除了"任务刚启动、镜像页第一次出现"这个必然的误触发点。
 * 2. 有任务在跑（[OverlayBus.taskRunning]）
 * 3. resume 后等 `AUTO_RETURN_DEBOUNCE_MS`，届时仍满足条件才算 —— 排除瞬态 resume
 * 4. 不在 30 秒冷却里（防乒乓）
 *
 * 真正的迁移不在这里做：只置一个请求，由 Agent 在**动作间隙**执行
 * （注入中途换通道会把一次点击劈成两半）。
 */
@Composable
fun AutoReturnOnForegroundEffect() {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val scope = rememberCoroutineScope()

    // 本 Activity 最近一次被切到后台的时刻；0 = 没进过后台 / 已消费
    var stoppedAtMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stoppedAtMs = System.currentTimeMillis()
                }
                Lifecycle.Event.ON_RESUME -> {
                    val leftAt = stoppedAtMs
                    stoppedAtMs = 0L
                    if (leftAt > 0) requestAutoReturnIfNeeded(scope)
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
}

/**
 * 发一个「自动回迁」请求。可以放在界面层，所以两个 Activity 都能调。
 *
 * 这里只做**能在这里判断**的条件（有没有任务、在不在冷却、防抖），
 * 「当前是不是副屏模式」交给 Agent 判断 —— 真执行时
 * `handleReturnToMainScreen()` 自己会检查，不是副屏就直接忽略。
 */
private fun requestAutoReturnIfNeeded(scope: CoroutineScope) {
    // 没任务在跑：不能置请求，否则会被下一条任务的第一步消费掉
    if (!OverlayBus.taskRunning) return
    if (OverlayBus.autoReturnSuppressed()) return
    scope.launch {
        delay(Agent.AUTO_RETURN_DEBOUNCE_MS)
        // 等完还满足才算数 —— 中途任务结束、或进了冷却就放弃
        if (!OverlayBus.taskRunning) return@launch
        if (OverlayBus.autoReturnSuppressed()) return@launch
        OverlayBus.requestReturnFromVd(OverlayBus.REASON_AUTO_RETURN)
    }
}
