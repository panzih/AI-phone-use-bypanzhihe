package com.aiphone.assistant.a11y

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * 无障碍通道的设备冒烟测试。
 *
 * 这个测试不替代真机验收，只回答两个最容易在打包/合并时坏掉的问题：
 *
 *   1. manifest 里的服务声明和 `accessibility_service_config.xml` 是否还在；
 *   2. 用户已经授权时，服务是否真的连上、能不能读到当前窗口。
 *
 * 第 2 条在没授权无障碍的设备上会 **skip**（不是失败）——
 * 运行前请先在系统设置里给「纸盒」打开无障碍。见 docs/testing-device.md。
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityServiceSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun accessibilityService_isDeclaredWithRequiredCapabilities() {
        val pm = context.packageManager
        val services = pm.queryIntentServices(
            Intent(AccessibilityService.SERVICE_INTERFACE),
            PackageManager.GET_META_DATA or PackageManager.GET_SERVICES,
        )
        val info = services
            .mapNotNull { it.serviceInfo }
            .firstOrNull {
                it.packageName == context.packageName &&
                    it.name == AutoService::class.java.name
            }

        assertNotNull("manifest 必须声明 ${AutoService::class.java.name}", info)
        info!!
        assertEquals(
            "无障碍服务必须由系统绑定，不能允许普通应用启动",
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
            info.permission,
        )
        assertTrue("系统要能跨应用绑定，exported 必须为 true", info.exported)

        val meta = info.metaData
        assertNotNull("缺少 android.accessibilityservice 的 meta-data", meta)
        val configRes = meta!!.getInt("android.accessibilityservice", 0)
        assertTrue("meta-data 必须指向 accessibility_service_config", configRes != 0)

        val parser = context.resources.getXml(configRes)
        var canRetrieveWindowContent = false
        var canPerformGestures = false
        var canTakeScreenshot = false
        var hasDescription = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                canRetrieveWindowContent = parser.getAttributeBooleanValue(
                    ANDROID_NS, "canRetrieveWindowContent", false,
                )
                canPerformGestures = parser.getAttributeBooleanValue(
                    ANDROID_NS, "canPerformGestures", false,
                )
                canTakeScreenshot = parser.getAttributeBooleanValue(
                    ANDROID_NS, "canTakeScreenshot", false,
                )
                hasDescription = !parser.getAttributeValue(ANDROID_NS, "description").isNullOrBlank()
            }
            event = parser.next()
        }

        assertTrue("读控件树是这个应用的核心能力", canRetrieveWindowContent)
        assertTrue("点击/滑动依赖 dispatchGesture", canPerformGestures)
        assertTrue("截图依赖 canTakeScreenshot（API 30+）", canTakeScreenshot)
        assertTrue("无障碍设置页要显示用途说明", hasDescription)
    }

    @Test
    fun accessibilityService_whenEnabled_canReadCurrentWindow() {
        assumeTrue(
            "需要先在系统设置里启用「纸盒」无障碍服务；本次跳过",
            isOurServiceEnabled(),
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (!AutoService.isConnected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("无障碍已授权但服务没有连上", AutoService.isConnected)

        val service = AutoService.get()
        assertNotNull("isConnected 为 true 但拿不到服务实例", service)
        val root = service!!.rootInActiveWindow
        assertNotNull("服务已连接但读不到当前窗口根节点（屏幕是否熄灭/锁屏？）", root)
    }

    private fun isOurServiceEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo?.serviceInfo?.let { info ->
                    info.packageName == context.packageName &&
                        info.name == AutoService::class.java.name
                } == true
            }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
