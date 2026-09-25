package com.aiphone.assistant.record

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证发送框本地快捷指令 [RecordingShortcut.matches]：
 * 整句（去标点、礼貌用语、「一下」之后）正好是录制相关短语才命中，
 * 正常任务不被误带进录制页。纯 Kotlin，不用 Robolectric。
 */
class RecordingShortcutTest {

    // 1. 直接说短语 → 命中
    @Test
    fun `直接短语_命中`() {
        listOf("操作记录", "整理操作记录", "开始录制", "录制宏", "新建宏").forEach {
            assertTrue(it, RecordingShortcut.matches(it))
        }
    }

    // 2. 带礼貌用语、标点、「一下」→ 仍命中
    @Test
    fun `带礼貌用语和标点_命中`() {
        listOf(
            "帮我整理操作记录",
            "请打开操作记录",
            "我想开始录制",
            "帮我整理一下操作记录。",
            "看一下操作记录",
        ).forEach { assertTrue(it, RecordingShortcut.matches(it)) }
    }

    // 2b. 带水词（刚才的 / 我的）→ 仍命中。这是 0.9.0 放宽的那一类
    @Test
    fun `带水词_命中`() {
        listOf(
            "帮我整理一下刚才的操作记录",
            "整理一下我的操作记录",
            "刚才的操作记录",
            "我要看我的操作记录",
            "帮我看看刚才那个操作记录",
        ).forEach { assertTrue(it, RecordingShortcut.matches(it)) }
    }

    // 2c. 水词 + 真任务混在句子里 → **不**命中（放宽的边界就在这里）
    @Test
    fun `水词加真任务_不命中`() {
        listOf(
            "整理一下操作记录里提到的联系人然后发给我妈",
            "帮我打开操作记录页面看看昨天的",
            "把操作记录整理成文档发到我的邮箱",
        ).forEach { assertFalse(it, RecordingShortcut.matches(it)) }
    }

    // 3. 正常任务（哪怕提到操作、记录）→ 不误伤
    @Test
    fun `正常任务_不命中`() {
        listOf(
            "帮我打开微信发消息",
            "去小红书发一条今天的穿搭",
            "操作记录里怎么没有昨天那一条",
            "帮我看看微信",
        ).forEach { assertFalse(it, RecordingShortcut.matches(it)) }
    }

    // 4. 空白 / 纯标点 → 不命中
    @Test
    fun `空白_不命中`() {
        assertFalse(RecordingShortcut.matches("   "))
        assertFalse(RecordingShortcut.matches("。。"))
    }
}
