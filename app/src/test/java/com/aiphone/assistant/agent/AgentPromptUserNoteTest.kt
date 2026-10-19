package com.aiphone.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂停期间用户补的话，怎么嵌进那一条 user 消息。
 *
 * 为什么单独钉：它是**拼接**，而拼接错了不会报错 —— 只会让模型看不见那句话，
 * 表现成"AI 照旧按老路子走，用户以为补充指令没生效"。同时它不能改变
 * 「用户的任务：…」那一行的位置和字样，否则每一轮的重复内容就不一致了。
 */
class AgentPromptUserNoteTest {

    private fun message(note: String?) = AgentPrompt.stepMessage(
        step = 2,
        maxSteps = 20,
        task = "打开微信给妈妈发消息",
        screenWidth = 1080,
        screenHeight = 2400,
        foregroundPackage = "com.tencent.mm",
        uiTree = "[3] Button \"搜索\" @(540,200) [可点]",
        userNote = note,
    )

    @Test
    fun `没有补充时整段不出现`() {
        assertFalse(message(null).contains("用户在你暂停时补充的指令"))
        assertFalse(message("  ").contains("用户在你暂停时补充的指令"))
    }

    @Test
    fun `有补充时原样带上并说明优先级`() {
        val text = message("别用语音，直接打字")
        assertTrue(text.contains("# 用户在你暂停时补充的指令"))
        assertTrue(text.contains("别用语音，直接打字"))
        assertTrue(text.contains("以这段为准"))
    }

    @Test
    fun `补充不能挤掉任务行和界面元素`() {
        val text = message("顺便看看有没有红包")
        assertTrue(text.contains("用户的任务：打开微信给妈妈发消息"))
        assertTrue(text.contains("# 界面元素"))
        assertTrue(text.contains("[3] Button"))
    }
}
