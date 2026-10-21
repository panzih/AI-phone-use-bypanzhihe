package com.aiphone.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 钉住"JS 产出的 JSON → UiNode 列表 → 给模型看的文本"这一段。
 *
 * 跑在 Robolectric 下只是为了让 UiNode 里的 android.graphics.Rect 是真的
 * （和项目里 NodeLocatorTest 一样）；不碰 WebView，也不依赖 org.json ——
 * 解析用的是 BrowserJson，单测里能真跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserChannelTest {

    private val json = """
{
  "url": "https://m.bilibili.com/",
  "title": "哔哩哔哩",
  "total": 2,
  "truncated": false,
  "scale": 2.0,
  "elements": [
    {"tag":"A","text":"登录","x":100,"y":320,"w":40,"h":40,"editable":false,"disabled":false},
    {"tag":"INPUT","text":"搜索关键词","x":280,"y":110,"w":40,"h":20,"editable":true,"disabled":false}
  ]
}
""".trimIndent()

    @Test
    fun `解析出编号 标签 文字 屏幕 bounds 和 web 序号`() {
        val nodes = BrowserDom.parseTree(json)
        assertEquals(2, nodes.size)

        val first = nodes[0]
        assertEquals(1, first.index)
        assertEquals("A", first.className)
        assertEquals("登录", first.text)
        // bounds 是 getBoundingClientRect 乘过 scale 的物理像素，中心点
        // 就是水波纹该闪的位置
        assertEquals(120, first.centerX)
        assertEquals(340, first.centerY)
        assertEquals("web:1", first.viewId)
        assertTrue(first.clickable)

        val second = nodes[1]
        assertEquals(2, second.index)
        assertEquals("INPUT", second.className)
        assertEquals("搜索关键词", second.text)
        assertEquals("web:2", second.viewId)
        assertTrue("输入框要标成可输入", second.editable)
        assertFalse("输入框不该同时标可点，否则和示例格式对不上", second.clickable)
    }

    @Test
    fun `渲染第一行是页面上下文 后面和 UiNode describe 同格式`() {
        val snapshot = BrowserDom.parseSnapshot(json)
        assertNotNull(snapshot)
        val text = BrowserDom.render(snapshot!!)
        val lines = text.lines()

        assertEquals("页面：https://m.bilibili.com/ ｜ 标题：哔哩哔哩 ｜ 元素 2 个", lines[0])
        assertEquals("""[1] A "登录" @(120,340) [可点]""", lines[1])
        assertEquals("""[2] INPUT "搜索关键词" @(300,120) [可输入]""", lines[2])
    }

    @Test
    fun `元素太多时首行要标明已截断`() {
        val truncated = """
{
  "url": "https://example.com/",
  "title": "",
  "total": 75,
  "truncated": true,
  "elements": [
    {"tag":"BUTTON","text":"下一页","x":0,"y":0,"w":100,"h":40,"editable":false,"disabled":false}
  ]
}
""".trimIndent()
        val snapshot = BrowserDom.parseSnapshot(truncated)!!
        assertTrue(snapshot.truncated)
        val text = BrowserDom.render(snapshot)
        assertTrue("截断必须在首行写出来，模型才知道还有元素没看到",
            text.lines()[0].contains("（已截断）"))
        assertTrue(text.lines()[0].contains("元素 75 个"))
    }

    @Test
    fun `页面没有可交互元素时是空列表 不会编出占位元素`() {
        val empty = """
{"url":"about:blank","title":"","total":0,"truncated":false,"elements":[]}
""".trimIndent()
        val snap = BrowserDom.parseSnapshot(empty)!!
        assertEquals(0, snap.nodes.size)
        assertFalse("空页面不能报成截断", snap.truncated)
    }

    @Test
    fun `解析不了的 JSON 返回空列表而不是抛异常`() {
        assertEquals(0, BrowserDom.parseTree("<html>不是 JSON</html>").size)
        assertNull(BrowserDom.parseSnapshot("[]"))
    }

    @Test
    fun `viewId 的序号和 JS 返回的数组顺序一一对应`() {
        val nodes = BrowserDom.parseTree(json)
        assertTrue(nodes.all { it.viewId == "web:" + it.index })
    }
}
