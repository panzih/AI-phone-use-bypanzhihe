package com.aiphone.assistant.overlay

import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aiphone.assistant.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 悬浮条的设备冒烟测试。
 *
 * 只钉两件事：
 *
 *   1. `OverlayService` 能真的进入前台（常驻通知出现），不会一启动就崩；
 *   2. 有悬浮窗权限时服务会发布实例，没有权限时按设计**不画窗口但继续活着**。
 *
 * 悬浮条长什么样、按钮点下去对不对，属于人工验收，不在冒烟测试里假装覆盖。
 * 运行前请先在系统设置里给「纸盒」打开「显示在其他应用上层」。
 */
@RunWith(AndroidJUnit4::class)
class OverlayServiceSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun overlayService_startsForeground_andPublishesInstance() {
        grantOverlayPermission()

        // 先把应用拉到前台，避免 Android 12+ 的「后台不能启动前台服务」限制。
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            OverlayService.start(context)

            val nm = context.getSystemService(NotificationManager::class.java)
            eventually(5_000) {
                nm.notificationChannels.any { it.id == CHANNEL_ID }
            }
            assertTrue(
                "前台服务通知渠道没有创建，OverlayService 可能没走到 startForeground",
                nm.notificationChannels.any { it.id == CHANNEL_ID },
            )

            eventually(5_000) {
                nm.activeNotifications.any { it.id == NOTIFICATION_ID }
            }
            assertTrue(
                "常驻通知没有出现：服务没有真正进入前台",
                nm.activeNotifications.any { it.id == NOTIFICATION_ID },
            )

            if (Settings.canDrawOverlays(context)) {
                eventually(5_000) { OverlayBus.service != null }
                assertNotNull(
                    "有悬浮窗权限时 OverlayService 应发布实例；否则悬浮条永远不会更新",
                    OverlayBus.service,
                )
            } else {
                // 没有权限时的设计：不画窗口，但前台服务和急停通知保留。
                assertNull("没有悬浮窗权限时不应发布窗口实例", OverlayBus.service)
            }
        } finally {
            OverlayService.stop(context)
            scenario.close()
        }
    }

    /** 用 shell 身份打开 SYSTEM_ALERT_WINDOW；失败就按“无权限”分支测。 */
    private fun grantOverlayPermission() {
        runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val pfd = automation.executeShellCommand(
                "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
            )
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    private fun eventually(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
    }

    private companion object {
        // 与 OverlayService 里的私有常量保持一致；改那边时这里要一起改。
        const val CHANNEL_ID = "aiphone_overlay"
        const val NOTIFICATION_ID = 1001
    }
}
