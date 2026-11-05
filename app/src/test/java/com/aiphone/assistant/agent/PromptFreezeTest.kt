package com.aiphone.assistant.agent

import com.aiphone.assistant.touch.TouchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * 提示词 / 工具集冻结测试（ROADMAP §0）。
 *
 * ## 为什么要钉哈希
 *
 * `AgentPrompt.VERSION` 是手改的，提示词文本本身没有任何东西看着 ——
 * 误改一个字，服务端的 KV cache 从第一个改动 token 起全部作废，
 * 表现只是"变慢变贵"，极难定位。把整篇系统提示词的 SHA-256 钉在这里：
 * **有意的修改**会让这个测试红，改的人必须连哈希一起更新（顺便升
 * [AgentPrompt.VERSION]，让老上下文按设计重开）；**无意的改动**直接挡在 CI。
 *
 * 工具名同理：动作名是模型输出协议的一部分，发布后**只增不改** ——
 * 改名 = 所有按老名字输出的模型全部解析失败。
 */
class PromptFreezeTest {

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `系统提示词被哈希钉住`() {
        val prompt = AgentPrompt.system(
            skillCatalog = "（测试目录：list_skills）",
            memory = null,
            modelName = "",
        )
        val hash = sha256(prompt)
        // 有意修改提示词后：跑一次测试，把失败信息里的实际哈希填回来，
        // 并把 AgentPrompt.VERSION 升一位（老上下文要按设计重开）。
        assertEquals(
            "系统提示词被改动了。若是有意的：更新下面的哈希并升级 AgentPrompt.VERSION；" +
                "若是无意的：恢复原文。实际 SHA-256 = $hash",
            "b70b9707bfcae31e82423bd55d9c12f7be695c2758a47eabf8945307b0d3f198",
            hash,
        )
    }

    @Test
    fun `动作名只增不改`() {
        // 0.12.x 发布时已存在的动作名：改名/删除都会让老模型输出解析失败
        val frozen = setOf(
            "tap", "long_press", "double_tap", "scroll", "swipe", "flick", "drag",
            "input_text", "key_back", "key_home", "key_recents", "open_app", "wait",
            "dismiss_dialog", "capture", "browser_open", "browser_html", "browser_eval",
        )
        val current = TouchKind.entries.map { it.id }.toSet()
        val missing = frozen - current
        assertTrue(
            "这些动作名被删掉或改名了（协议破坏）：$missing。动作名发布后只增不改。",
            missing.isEmpty(),
        )
        // ROADMAP §0 新增的动作必须在场（顺带钉住本轮扩展）
        assertTrue(
            current.containsAll(
                setOf(
                    "press_enter", "collect", "move_app", "set_alarm",
                    "sms_draft", "open_nav", "share_text", "clipboard_get", "clipboard_set",
                ),
            ),
        )
    }
}
