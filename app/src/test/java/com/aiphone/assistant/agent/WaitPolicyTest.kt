package com.aiphone.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「界面稳了没有」的判据。
 *
 * 这段逻辑的收益是"快了"，但风险是"把**还没开始动**误判成**已经稳了**" ——
 * 后果是模型拿到半渲染的界面去点元素，真机上表现为"AI 突然乱点"，很难回溯。
 * 所以两种情形必须分开钉死：**看到变化**才敢提前放行，**没看到变化**必须守保守值。
 */
class WaitPolicyTest {

    private val minFloor = 400L

    private fun settled(
        samplesEqual: Boolean,
        seenChange: Boolean,
        msSinceChange: Long,
        elapsedMs: Long,
        settleMs: Long = 200L,
    ) = WaitPolicy.settled(
        samplesEqual = samplesEqual,
        seenChange = seenChange,
        msSinceChange = msSinceChange,
        elapsedMs = elapsedMs,
        minFloorMs = minFloor,
        settleMs = settleMs,
    )

    // ---- 界面上一次就看到它动了：变化 + 静止够 → 提前放行（这是省时间的那条路）----

    @Test
    fun `看到变化且静止够了_不必等满最小起步`() {
        // 关键断言：elapsed 只有 250ms（远小于 minFloor 400）也要放行。
        // 这就是"点一下就跳页"从 600ms 降到 ~300ms 的全部秘密。
        assertTrue(settled(samplesEqual = true, seenChange = true, msSinceChange = 200, elapsedMs = 250))
    }

    // ---- 保守的那条路：没观测到变化，只能等满最小起步 ----

    @Test
    fun `没看到变化_到点之前绝不放行`() {
        // 动作刚发出、界面还没来得及开始动。这时候两次采样也会相同 ——
        // 如果只看"两次相同"就放行，等于把"还没开始"当成"已经停住"
        assertFalse(settled(samplesEqual = true, seenChange = false, msSinceChange = 0, elapsedMs = 100))
        assertFalse(settled(samplesEqual = true, seenChange = false, msSinceChange = 0, elapsedMs = 399))
    }

    @Test
    fun `没看到变化_等满最小起步才放行`() {
        assertTrue(settled(samplesEqual = true, seenChange = false, msSinceChange = 0, elapsedMs = 400))
    }

    // ---- 还在动就绝不能放行，哪怕已经超时 ----

    @Test
    fun `两次采样不同_任何情况都不放行`() {
        assertFalse(settled(samplesEqual = false, seenChange = true, msSinceChange = 9_999, elapsedMs = 9_999))
        assertFalse(settled(samplesEqual = false, seenChange = false, msSinceChange = 0, elapsedMs = 9_999))
    }

    // ---- 看到变化但刚变完、还没静住 ----

    @Test
    fun `看到变化但刚变完_还没静住就先不放行`() {
        // 变化发生在 199ms 前、总共才过了 250ms：它可能还在动，再等一拍。
        // 注意 elapsed 必须也小于 minFloor —— 已经等过 minFloor 的情况
        // 走下面那条兜底，见下一条用例
        assertFalse(
            settled(samplesEqual = true, seenChange = true, msSinceChange = 199, elapsedMs = 250)
        )
    }

    @Test
    fun `已经等过最小起步_刚变过也放行`() {
        // 自洽性：samplesEqual 之后每多一拍 msSinceChange 就涨，
        // 下一拍自然满足静止要求；而这里总共已经等了 400ms 了，
        // 再为差 1ms 多等一个采样间隔没有意义
        assertTrue(
            settled(samplesEqual = true, seenChange = true, msSinceChange = 199, elapsedMs = 400)
        )
    }

    @Test
    fun `静止正好等于阈值_放行`() {
        assertTrue(
            settled(samplesEqual = true, seenChange = true, msSinceChange = 200, elapsedMs = 200)
        )
    }

    // ---- open_app 的本地判据：目标应用到前台了没有 ----

    @Test
    fun `目标应用到前台且界面静住_放行`() {
        assertTrue(
            WaitPolicy.arrived(
                targetPkg = "com.android.settings",
                foregroundPkg = "com.android.settings",
                samplesEqual = true,
                msSinceArrival = 300,
                arrivedSettleMs = 300,
            )
        )
    }

    @Test
    fun `前台还是别人_不放行`() {
        // 包名错 / 启动失败就是这条：不能因为"等了 300ms"就当到了
        assertFalse(
            WaitPolicy.arrived(
                targetPkg = "com.android.settings",
                foregroundPkg = "com.android.launcher3",
                samplesEqual = true,
                msSinceArrival = 5_000,
                arrivedSettleMs = 300,
            )
        )
    }

    @Test
    fun `没给目标包名_退回常规判据`() {
        // 模型没写包名时不能瞎猜"到了"
        assertFalse(WaitPolicy.arrived(null, "com.android.settings", true, 5_000, 300))
        assertFalse(WaitPolicy.arrived("", "com.android.settings", true, 5_000, 300))
        assertFalse(WaitPolicy.arrived("   ", "com.android.settings", true, 5_000, 300))
    }

    @Test
    fun `到了但界面还在动或还没静够_不放行`() {
        // 启动页通常也会静止一瞬间，所以"包名对了"本身不够
        assertFalse(
            WaitPolicy.arrived("com.a", "com.a", samplesEqual = false, msSinceArrival = 9_999, arrivedSettleMs = 300)
        )
        assertFalse(
            WaitPolicy.arrived("com.a", "com.a", samplesEqual = true, msSinceArrival = 299, arrivedSettleMs = 300)
        )
    }
}
