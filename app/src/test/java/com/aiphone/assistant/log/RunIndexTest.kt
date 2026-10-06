package com.aiphone.assistant.log

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 钉住导出包的"一览表"（index.csv）。
 *
 * 背景：用户导出一次日志，包里往往有十几次运行。0.8.5 那份包的 runs.txt
 * 只有 8 行目录名，想知道"哪几次失败、失败在哪一步"，得一个个目录点进去。
 * index.csv 就是为这一步准备的。
 *
 * 这里同时守住"坏数据不能拖垮导出"：report.json 缺失、字段不全、
 * JSON 截断，都必须只是留空，不能让整次导出失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(
        name: String,
        report: JSONObject? = null,
        uiTruncatedStep: Int? = null,
    ): File {
        val dir = tmp.newFolder(name)
        if (report != null) {
            File(dir, RunLogger.REPORT_NAME).writeText(report.toString())
        }
        if (uiTruncatedStep != null) {
            val ui = File(dir, RunLogger.UI_SUBDIR).apply { mkdirs() }
            File(ui, "step_%02d.txt".format(uiTruncatedStep)).writeText(
                "控件树：60 个元素（遍历 80 个原始节点） ⚠️ 已达上限 60，界面还有元素没发给模型\n" +
                    "根节点可读：true\n元素个数：60（遍历 80 个原始节点，截断=true）\n"
            )
        }
        return dir
    }

    private fun report(
        task: String,
        success: Boolean?,
        outcome: String,
        steps: List<Pair<Int, String?>>,
    ): JSONObject = JSONObject().apply {
        put("task", task)
        put("startedAt", 1_790_000_000_000L)
        put("durationMs", 12_500L)
        put("screenshots", 0)
        if (success == null) put("success", JSONObject.NULL) else put("success", success)
        put("outcome", outcome)
        put("steps", JSONArray().apply {
            steps.forEach { (no, tree) ->
                put(JSONObject().apply {
                    put("step", no)
                    put("uiTree", tree ?: JSONObject.NULL)
                })
            }
        })
    }

    @Test
    fun `一览表里有结局 步数和耗时`() {
        val dir = run(
            "20261001_125839_打开蓝牙",
            report("打开蓝牙", success = true, outcome = "已成功关闭蓝牙", steps = listOf(1 to "x")),
        )
        val s = RunIndex.build(listOf(dir))
        assertEquals(1, s.rows.size)
        assertEquals("打开蓝牙", s.rows[0].task)
        assertEquals(true, s.rows[0].success)
        assertEquals(1, s.rows[0].steps)
        assertEquals(1, s.totalSteps)

        val csv = s.csv
        assertTrue(csv.startsWith("目录,任务,开始时间,耗时秒,步数,成功,结局,截断步数,读不到控件树的步,可疑原因"))
        assertTrue(csv.contains("打开蓝牙"))
        assertTrue(csv.contains("12.5"))
        assertTrue(csv.contains("已成功关闭蓝牙"))
    }

    @Test
    fun `读不到控件树的步会被标出来`() {
        // 0.8.5 现实：8 次运行里有 6 次的关键步骤 uiTree 都是空的
        val dir = run(
            "20261005_141940_打开微信",
            report("打开微信", success = false, outcome = "卡住了", steps = listOf(1 to null, 2 to null, 3 to "元素")),
        )
        val row = RunIndex.parseRun(dir)
        assertEquals(listOf(1, 2), row.noTreeSteps)
        assertTrue(RunIndex.suspicion(row).contains("读不到控件树"))
    }

    @Test
    fun `元素列表被截断的步会被数出来`() {
        val dir = run(
            "20261005_142800_中信",
            report("中信", success = true, outcome = "完成", steps = listOf(4 to "元素")),
            uiTruncatedStep = 4,
        )
        val row = RunIndex.parseRun(dir)
        assertEquals(1, row.truncatedSteps)
        assertTrue(RunIndex.suspicion(row).contains("截断"))
    }

    @Test
    fun `失败的运行会被标成失败`() {
        val dir = run("r1", report("x", success = false, outcome = "卡住了：界面连续 6 步没有任何变化", steps = emptyList()))
        val row = RunIndex.parseRun(dir)
        assertTrue(RunIndex.suspicion(row).contains("失败"))
        assertTrue(RunIndex.suspicion(row).contains("一步都没走"))
    }

    @Test
    fun `report点json 缺失或坏掉都不影响导出`() {
        val good = run("good", report("好的", success = true, outcome = "完成", steps = listOf(1 to "a")))
        val empty = tmp.newFolder("empty")            // 旧版本可能没有 report.json
        val broken = tmp.newFolder("broken")
        File(broken, RunLogger.REPORT_NAME).writeText("{ 这不是 JSON")

        val s = RunIndex.build(listOf(good, empty, broken))
        assertEquals(3, s.rows.size)
        // 坏的那两个留空，但目录名还在，能人工去看
        assertEquals("empty", s.rows[1].task)
        assertEquals(null, s.rows[1].success)
        assertEquals("broken", s.rows[2].task)
        assertEquals(null, s.rows[2].success)
        assertEquals(1, s.totalSteps)
        // 解析不出来 ≠ 失败。不能把"不知道"算成"失败"，否则一览表会虚报
        assertEquals(0, s.failedRuns)
    }

    @Test
    fun `csv 会转义逗号和引号`() {
        val dir = run("r", report("给李四发, 你好", success = true, outcome = "他说\"好\"", steps = emptyList()))
        val csv = RunIndex.build(listOf(dir)).csv
        assertTrue(csv.contains("\"给李四发, 你好\""))
        assertTrue(csv.contains("\"他说\"\"好\"\"\""))
    }
}
