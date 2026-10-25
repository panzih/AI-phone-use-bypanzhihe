package com.aiphone.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 更新接口的解析。
 *
 * 这部分是最容易出错的地方：服务端字段少一个、`downloadUrl` 为空（版本传了但没传包）、
 * `force` 在顶层和 latest 里各有一份。**这三种情况直接决定用户会不会点到一个 404**，
 * 所以逐个钉死。网络那段（socket / 超时）不在这里测 —— 它是样板代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    private fun body(
        hasUpdate: Boolean = true,
        downloadUrl: String = "https://paperbox-update.app.workbuddy.host/api/v1/download/2?sharecode=X",
        forceTop: Boolean = false,
        forceLatest: Boolean = false,
    ) = """
        {
          "ok": true,
          "hasUpdate": $hasUpdate,
          "force": $forceTop,
          "latest": {
            "id": 2,
            "versionCode": 74,
            "versionName": "0.10.7",
            "size": 3679251,
            "sha256": "29c69533",
            "changelog": "修好了三件事\n\n- 一\n- 二",
            "force": $forceLatest,
            "downloadUrl": ${if (downloadUrl.isEmpty()) "null" else "\"$downloadUrl\""}
          }
        }
    """.trimIndent()

    @Test
    fun `有新版本_解析出版本信息与下载地址`() {
        val info = UpdateChecker.parseInfo(body())!!
        assertEquals(74, info.versionCode)
        assertEquals("0.10.7", info.versionName)
        assertEquals(3679251L, info.size)
        assertEquals("29c69533", info.sha256)
        assertTrue(info.downloadUrl.contains("/api/v1/download/2"))
        assertTrue(info.changelog.contains("修好了三件事"))
        assertFalse(info.force)
    }

    @Test
    fun `没有新版_返回 null`() {
        assertNull(UpdateChecker.parseInfo(body(hasUpdate = false)))
    }

    @Test
    fun `版本传了但没传安装包_不当作有更新`() {
        // 这种版本点下载就是 404，宁可不提示
        assertNull(UpdateChecker.parseInfo(body(downloadUrl = "")))
    }

    @Test
    fun `强制更新_顶层或 latest 任一为真就算`() {
        assertTrue(UpdateChecker.parseInfo(body(forceTop = true))!!.force)
        assertTrue(UpdateChecker.parseInfo(body(forceLatest = true))!!.force)
        assertFalse(UpdateChecker.parseInfo(body())!!.force)
    }

    @Test
    fun `返回不是 JSON_返回 null 而不是抛异常`() {
        assertNull(UpdateChecker.parseInfo("<html>502 Bad Gateway</html>"))
        assertNull(UpdateChecker.parseInfo(""))
    }

    @Test
    fun `大小文案_按量级切换单位`() {
        fun info(size: Long) = UpdateChecker.Info(
            versionCode = 1, versionName = "x", changelog = "", size = size,
            sha256 = "", downloadUrl = "https://x", force = false,
        )
        assertEquals("", info(0).sizeText)
        assertEquals("500 KB", info(500L * 1024).sizeText)
        assertEquals("3.5 MB", info((3.5 * 1024 * 1024).toLong()).sizeText)
    }
}
