package com.aiphone.assistant.agent

import com.aiphone.assistant.touch.TouchKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.9.18 死锁护栏判据：目标没记上时，切换请求必须给 open_app 让路。
 *
 * 背景（0.9.17 蓝叠实证）：`watcher.targetPkg` 唯一来源是"应用真的到了前台"，
 * 而把应用拉到前台的正是 open_app。切换请求若把 open_app 掐死（预执行中止
 * 或等待中止），目标永远出不来 → 切换请求永远被拒 → 用户连点也没用。
 */
class AgentSwitchPolicyTest {

    @Test
    fun `open_app without target yields to switch request`() {
        assertTrue(Agent.switchMustYieldToOpenApp(TouchKind.OPEN_APP, null))
        assertTrue(Agent.switchMustYieldToOpenApp(TouchKind.OPEN_APP, ""))
    }

    @Test
    fun `open_app with existing target keeps aborting`() {
        // 已有目标时边界能直接迁那个目标，open_app 维持让路（原行为）
        assertFalse(Agent.switchMustYieldToOpenApp(TouchKind.OPEN_APP, "tv.danmaku.bili"))
    }

    @Test
    fun `non open_app actions never yield`() {
        assertFalse(Agent.switchMustYieldToOpenApp(TouchKind.TAP, null))
        assertFalse(Agent.switchMustYieldToOpenApp(TouchKind.WAIT, null))
        assertFalse(Agent.switchMustYieldToOpenApp(TouchKind.CAPTURE, ""))
    }
}
