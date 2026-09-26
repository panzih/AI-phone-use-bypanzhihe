package com.aiphone.assistant.record

import org.json.JSONObject
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
 * 录制技能的落盘格式（`RecordingScreen` 的回归兜底）。
 *
 * 这个页面以前没有测试。挑出来的都是"坏了后果很重、但平时看不出来"的点：
 *
 *  - **密码原文不能进文件**。录制文件是要发给模型的，写进去等于把密码
 *    交给了模型服务商，而且会永久留在文件里、还会被「导出日志」一起打包发出去。
 *    这条是隐私红线，必须有测试顶着，不能靠"记得别改"。
 *  - **技能 id 会变成文件名**。没洗干净就能写出 `../` 或者被系统拒掉的路径，
 *    表现是"学了个技能但列表里没有"。
 *  - **一个坏文件不能让整张列表加载不出来**（解析失败一律 null）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MacroStoreTest {

    private fun step(
        action: String = "tap",
        inputText: String = "",
        sensitive: Boolean = false,
    ) = RecordedStep(
        action = action,
        packageName = "com.android.settings",
        className = "android.widget.EditText",
        viewId = "com.android.settings:id/search",
        text = "搜索",
        x = 100, y = 200,
        inputText = inputText,
        sensitive = sensitive,
    )

    private fun macro(id: String = "macro_x", steps: List<RecordedStep> = listOf(step())) =
        MacroSkill(
            id = id, title = "打开设置", summaryText = "s", docText = "d",
            steps = steps, stepGapMs = 1500, createdAt = 42L,
        )

    // ---- 隐私红线 ----

    @Test
    fun `密码框的输入内容绝不写进文件`() {
        val json = step(action = "input", inputText = "hunter2", sensitive = true).toJson()
        assertEquals("密码原文不能出现在录制文件里", "", json.optString("input"))
        assertTrue(
            "但「这一步有密码」这件事要留着，回放时才知道要跳过",
            json.optBoolean("sensitive"),
        )
    }

    @Test
    fun `非密码框的输入照常保存`() {
        // 反过来也要钉住：别为了隐私把普通输入也丢了，技能就废了
        val json = step(action = "input", inputText = "南京", sensitive = false).toJson()
        assertEquals("南京", json.optString("input"))
    }

    @Test
    fun `密码经过一次往返之后也不会复活`() {
        val back = RecordedStep.fromJson(
            step(action = "input", inputText = "hunter2", sensitive = true).toJson()
        )
        assertTrue(back.sensitive)
        assertEquals("", back.inputText)
    }

    // ---- id 会变成文件名 ----

    @Test
    fun `id清洗_不能让文件名逃出技能目录`() {
        // 真正要保证的性质是"拼出来的路径还在技能目录里"。
        // 注意 sanitizeId 并不抹掉 ".." 这两个字符本身（`macro_.._.._etc_passwd`
        // 里就有），但分隔符全被换成了下划线，所以它只是一个普通文件名、
        // 逃不出去 —— 断言要落在**路径**上，不要落在"字符串里有没有 .."上。
        val dir = java.io.File("/tmp/macros")
        listOf("../../etc/passwd", "..", ".", "a/../../b", "..\\..\\windows").forEach { raw ->
            val id = MacroStore.sanitizeId(raw)
            assertFalse("不能留路径分隔符：$id", id.contains("/") || id.contains("\\"))
            val f = java.io.File(dir, id)
            assertEquals("不能让 $raw 逃出技能目录", dir.absolutePath, f.parentFile!!.absolutePath)
        }
    }

    @Test
    fun `id清洗_保留可读部分并加前缀`() {
        assertEquals("macro_打开设置", MacroStore.sanitizeId("打开设置"))
        assertTrue("中文技能名要能认出来", MacroStore.sanitizeId("每天签到").contains("每天签到"))
    }

    @Test
    fun `id清洗_空格与换行都换掉且长度有上限`() {
        val id = MacroStore.sanitizeId("a b\nc\td")
        assertFalse(id.contains(" "))
        assertFalse(id.contains("\n"))
        // 文件名不能无限长：前缀之外的部分截到 32 个字符
        assertTrue("实际 ${id.length}", id.length <= "macro_".length + 32)
    }

    @Test
    fun `id清洗_空名字要有兜底`() {
        assertEquals("macro_skill", MacroStore.sanitizeId("   "))
        assertEquals("macro_skill", MacroStore.sanitizeId("///"))
    }

    // ---- 解析容错 ----

    @Test
    fun `缺id的技能丢掉_缺标题的给默认名`() {
        assertNull(MacroSkill.fromJson(JSONObject().apply { put("title", "x") }))
        val s = MacroSkill.fromJson(JSONObject().apply { put("id", "macro_x") })
        assertNotNull(s)
        assertEquals("未命名技能", s!!.title)
    }

    @Test
    fun `技能往返_步骤一个都不能丢`() {
        val src = macro(steps = listOf(step(), step(action = "long_press"), step(action = "input", inputText = "南京")))
        val back = MacroSkill.fromJson(src.toJson())!!
        assertEquals(src.title, back.title)
        assertEquals("s", back.summary)
        assertEquals("d", back.doc)
        assertEquals(1500, back.stepGapMs)
        assertEquals(42L, back.createdAt)
        assertEquals(3, back.steps.size)
        assertEquals("南京", back.steps[2].inputText)
        assertEquals("long_press", back.steps[1].action)
    }

    @Test
    fun `步骤字段类型不对_退回默认值而不是崩`() {
        val bad = JSONObject().apply {
            put("id", "macro_x")
            put("steps", org.json.JSONArray().apply { put(JSONObject().apply { put("x", "不是数字") }) })
        }
        val s = MacroSkill.fromJson(bad)
        assertNotNull(s)
        assertEquals(1, s!!.steps.size)
        assertEquals(0, s.steps[0].x)
        assertEquals("tap", s.steps[0].action)
    }
}
