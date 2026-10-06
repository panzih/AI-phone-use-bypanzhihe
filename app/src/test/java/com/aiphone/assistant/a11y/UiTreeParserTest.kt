package com.aiphone.assistant.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住一个看起来很小、但把 0.8.5 整批失败都盖住了的行为：
 * **空列表渲染出来是一行非空文本**。
 *
 * 旧代码里 Agent 用 `tree.lines().count { it.isNotBlank() }` 当元素个数，
 * 于是"读不到控件树"被打印成「控件树：1 个元素」—— 导出包里那个 "1"
 * 看起来就像一个真控件，谁也不会怀疑是无障碍没工作。
 *
 * 现在元素个数一律取 [UiTreeParser.ParseResult.nodes]`.size`；
 * 这个用例守住"占位文本永远只有一行、而且能一眼认出是占位"。
 */
class UiTreeParserTest {

    @Test
    fun `空列表渲染成一行占位文本`() {
        val text = UiTreeParser.render(emptyList())
        assertEquals(UiTreeParser.EMPTY_TEXT, text)
        assertEquals("占位文本必须是单行，否则又会被人按行数当成元素个数", 1, text.lines().size)
    }

    @Test
    fun `占位文本要说明这不是一个元素`() {
        assertTrue(UiTreeParser.EMPTY_TEXT.contains("没有可交互元素"))
        assertTrue(
            "占位文本本身要能被认出来，不然复盘时无法区分",
            UiTreeParser.EMPTY_TEXT.startsWith("（"),
        )
    }
}
