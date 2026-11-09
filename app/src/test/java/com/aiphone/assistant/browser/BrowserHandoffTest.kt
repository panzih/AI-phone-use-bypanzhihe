package com.aiphone.assistant.browser

import android.content.Context
import com.aiphone.assistant.ChannelController
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 钉住「浏览器通道里能走出来」。
 *
 * 实机日志（2026-11-09 蓝叠，任务「帮我在网页上下载这个客户端，然后安装」）：
 * 模型进入内置浏览器后发现下载不了，连续下发
 *   open_app(com.android.chrome) → 「浏览器通道不负责打开应用」
 *   key_home                    → 「浏览器通道不支持主页键，请关闭浏览器回到桌面」
 * 两条路都被拒，模型没有别的退出动作，最后只能 failed 放弃任务。
 *
 * 根因不是模型选错动作，而是**浏览器通道把离开的出口焊死了**：
 * open_app / 主页键 / 多任务键的目标本来就是「离开当前页面、回到系统」，
 * 它们必须先关掉浏览器、切回主通道再执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserHandoffTest {

    // ---------------------------------------------------------------
    // 策略本身：哪些动作在浏览器里必须先交接
    // ---------------------------------------------------------------

    @Test
    fun `浏览器里的 open_app 应当先退出浏览器`() {
        assertTrue(BrowserHandoff.shouldLeaveBrowser(true, TouchKind.OPEN_APP))
    }

    @Test
    fun `浏览器里的主页键和多任务键也应当先退出浏览器`() {
        assertTrue(BrowserHandoff.shouldLeaveBrowser(true, TouchKind.KEY_HOME))
        assertTrue(BrowserHandoff.shouldLeaveBrowser(true, TouchKind.KEY_RECENTS))
    }

    @Test
    fun `网页内操作不触发退出`() {
        listOf(
            TouchKind.TAP,
            TouchKind.SCROLL,
            TouchKind.KEY_BACK,
            TouchKind.INPUT_TEXT,
            TouchKind.BROWSER_OPEN,
        ).forEach { kind ->
            assertFalse("$kind 应当留在浏览器里继续操作页面", BrowserHandoff.shouldLeaveBrowser(true, kind))
        }
    }

    @Test
    fun `不在浏览器通道时一律不触发退出`() {
        assertFalse(BrowserHandoff.shouldLeaveBrowser(false, TouchKind.OPEN_APP))
        assertFalse(BrowserHandoff.shouldLeaveBrowser(false, TouchKind.KEY_HOME))
    }

    // ---------------------------------------------------------------
    // 接到 ChannelController 上：执行后确实离开了浏览器模式
    // ---------------------------------------------------------------

    private fun controller(): ChannelController =
        ChannelController(RuntimeEnvironment.getApplication() as Context)

    @Test
    fun `浏览器模式下 open_app 执行后切回主通道而不是报浏览器不负责`() = runBlocking {
        val c = controller()
        c.enterBrowser()
        assertTrue("前置：应当处于浏览器模式", c.isBrowser)

        val err = c.execute(TouchAction(TouchKind.OPEN_APP, packageName = "com.android.chrome"))

        assertFalse("open_app 之后不能还留在浏览器模式", c.isBrowser)
        assertNotEquals("浏览器通道不负责打开应用", err)
    }

    @Test
    fun `浏览器模式下主页键执行后切回主通道`() = runBlocking {
        val c = controller()
        c.enterBrowser()

        val err = c.execute(TouchAction(TouchKind.KEY_HOME))

        assertFalse("主页键之后不能还留在浏览器模式", c.isBrowser)
        assertNotEquals("浏览器通道不支持主页键，请关闭浏览器回到桌面", err)
    }

    @Test
    fun `浏览器模式下普通点击仍然留在浏览器`() = runBlocking {
        val c = controller()
        c.enterBrowser()

        c.execute(TouchAction(TouchKind.TAP, x = 10, y = 10))

        assertTrue("点击网页元素不该把浏览器关掉", c.isBrowser)
    }
}
