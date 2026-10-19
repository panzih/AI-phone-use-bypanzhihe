package com.aiphone.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复盘」这句话的边界。
 *
 * 判错的代价不对称：**该命中的没命中**只是多跑一趟任务；
 * **不该命中的命中了**会把"复盘一下然后帮我打开设置"当成纯复盘，任务直接不执行。
 * 所以判据收得很紧。
 */
class ReviewShortcutTest {

    @Test
    fun `几个常见说法都命中`() {
        listOf("复盘", "复评", "回顾", "复盘一下。", " 复评 ", "总结这趟").forEach {
            assertTrue("应该命中：$it", ReviewShortcut.matches(it))
        }
    }

    @Test
    fun `带后续任务的句子不命中`() {
        // 这是新任务，不是回看
        listOf(
            "复盘一下我刚才的操作记录然后整理成文档",
            "复盘之后帮我打开微信",
            "回顾一下再给我发个消息给妈妈",
        ).forEach {
            assertFalse("不该命中：$it", ReviewShortcut.matches(it))
        }
    }

    @Test
    fun `普通任务不命中`() {
        listOf("打开设置", "", "   ").forEach {
            assertFalse("不该命中：$it", ReviewShortcut.matches(it))
        }
    }
}
