package com.aiphone.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 0.8.5 日志里那三条"判断逻辑错了"的规则。
 *
 * 每一条都对应一次真实的失败，见 [RunGuards] 的注释。这些判据以前内联在
 * Agent 的循环里，只能靠真机重跑验证；抽成纯函数之后，改坏了会立刻红。
 */
class RunGuardsTest {

    // ---------------------------------------------------------------
    // 1. 「界面连续 N 步没变化」——不能拿读不到的控件树当判据
    // ---------------------------------------------------------------

    @Test
    fun `读不到控件树时不能判定界面没变化`() {
        // 0.8.5 现场：无障碍没生效 → 元素列表为空 → 指纹恒定 →
        // 第 6 步必然"卡住了：界面连续 6 步没有任何变化"，而界面在正常变化。
        assertFalse(RunGuards.treeObservable(rootAvailable = false, elementCount = 0))
    }

    @Test
    fun `根节点读到但这一屏没有元素时也不能判定`() {
        // 自绘界面 / 前台是纸盒自己：指纹同样恒定，同样会误判成"点不动"
        assertFalse(RunGuards.treeObservable(rootAvailable = true, elementCount = 0))
    }

    @Test
    fun `看得到且有元素时才算可观测`() {
        assertTrue(RunGuards.treeObservable(rootAvailable = true, elementCount = 1))
        assertTrue(RunGuards.treeObservable(rootAvailable = true, elementCount = 60))
    }

    @Test
    fun `不可观测时把没变化的计数清零`() {
        // 前面积了 4 步，这一步读不到 → 必须归零，否则第 6 步照样会停
        assertEquals(0, RunGuards.nextSameTreeCount(previous = 4, treeHash = 0, lastTreeHash = 123))
    }

    @Test
    fun `指纹相同才累加`() {
        assertEquals(3, RunGuards.nextSameTreeCount(previous = 2, treeHash = 7, lastTreeHash = 7))
        assertEquals(0, RunGuards.nextSameTreeCount(previous = 2, treeHash = 8, lastTreeHash = 7))
    }

    // ---------------------------------------------------------------
    // 2. 止损理由要分清楚"界面点不动"和"无障碍没工作"
    // ---------------------------------------------------------------

    @Test
    fun `同屏步数到上限给的是界面没变化`() {
        assertNotNull(RunGuards.stuckReason(sameTree = 5, repeatAction = 0, limit = 5))
    }

    @Test
    fun `重复动作到上限给的是模型重复`() {
        val why = RunGuards.stuckReason(sameTree = 0, repeatAction = 5, limit = 5)
        assertEquals("模型连续 6 次给出同一批动作", why)
    }

    @Test
    fun `没到上限不拦`() {
        assertNull(RunGuards.stuckReason(sameTree = 4, repeatAction = 4, limit = 5))
    }

    @Test
    fun `连续读不到控件树的止损必须点名无障碍`() {
        val msg = RunGuards.unreadableTreeStop(consecutive = 6, limit = 6)
        assertNotNull(msg)
        // 关键：让用户知道该去哪里修，而不是让他去看那个"点不动"的界面
        assertTrue(msg!!.contains("无障碍"))
        assertTrue(msg.contains("重新发一次任务"))
    }

    @Test
    fun `读不到的次数没到上限不拦`() {
        assertNull(RunGuards.unreadableTreeStop(consecutive = 5, limit = 6))
    }

    @Test
    fun `读到树就清零`() {
        assertEquals(0, RunGuards.nextUnreadableStreak(previous = 4, rootAvailable = true))
        assertEquals(5, RunGuards.nextUnreadableStreak(previous = 4, rootAvailable = false))
    }

    // ---------------------------------------------------------------
    // 3. 「打开蓝牙」那一次：新任务第一步、没看界面、就要收尾
    // ---------------------------------------------------------------

    @Test
    fun `带历史的第一步无动作宣布完成要被拦下`() {
        assertTrue(
            RunGuards.shouldRefuseBlindFinish(
                step = 1,
                carriedHistoryCount = 8,
                finished = true,
                actionCount = 0,
                alreadyRefused = false,
            )
        )
    }

    @Test
    fun `只拦一次`() {
        assertFalse(
            RunGuards.shouldRefuseBlindFinish(
                step = 1,
                carriedHistoryCount = 8,
                finished = true,
                actionCount = 0,
                alreadyRefused = true,
            )
        )
    }

    @Test
    fun `新开的上下文不拦`() {
        assertFalse(
            RunGuards.shouldRefuseBlindFinish(
                step = 1,
                carriedHistoryCount = 0,
                finished = true,
                actionCount = 0,
                alreadyRefused = false,
            )
        )
    }

    @Test
    fun `不是第一步不拦`() {
        assertFalse(
            RunGuards.shouldRefuseBlindFinish(
                step = 2,
                carriedHistoryCount = 8,
                finished = true,
                actionCount = 0,
                alreadyRefused = false,
            )
        )
    }

    @Test
    fun `同一批里给了动作就不拦`() {
        // 有动作的情况本来就不会在这一支收尾（要先把动作执行完）
        assertFalse(
            RunGuards.shouldRefuseBlindFinish(
                step = 1,
                carriedHistoryCount = 8,
                finished = true,
                actionCount = 2,
                alreadyRefused = false,
            )
        )
    }

    @Test
    fun `没宣布完成当然不拦`() {
        assertFalse(
            RunGuards.shouldRefuseBlindFinish(
                step = 1,
                carriedHistoryCount = 8,
                finished = false,
                actionCount = 0,
                alreadyRefused = false,
            )
        )
    }
}
