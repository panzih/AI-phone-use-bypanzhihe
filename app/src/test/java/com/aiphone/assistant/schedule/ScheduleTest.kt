package com.aiphone.assistant.schedule

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
 * 定时任务的数据模型与落盘格式（`ScheduleScreen` 的回归兜底）。
 *
 * 这个页面以前没有测试。选这几条理由都是"坏了会静默出错"：
 *  - **小时/分钟越界不夹** → 闹钟排到一个不存在的时间点，任务永远不触发，
 *    而界面看上去一切正常；
 *  - **`useVirtualDisplay` 丢了** → 用户勾了"在副屏跑"，重启后变成主屏跑，
 *    主屏会亮起来打扰人（这个字段是后加的，最容易被漏）；
 *  - **坏文件让整张列表加载不出来** → 一条手改坏的 json 就能让所有定时任务消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleTest {

    private fun json(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    private fun full() = Schedule(
        id = "s1", task = "打开设置", hour = 9, minute = 30,
        repeatDaily = true, useVirtualDisplay = true, enabled = false, lastRunAt = 123L,
    )

    // ---- 文案 ----

    @Test
    fun `时间文案_每天与仅一次_都补零`() {
        assertEquals("每天 09:30", full().timeLabel())
        assertEquals("仅一次 00:05", full().copy(repeatDaily = false, hour = 0, minute = 5).timeLabel())
        assertEquals("每天 23:59", full().copy(hour = 23, minute = 59).timeLabel())
    }

    // ---- 解析：坏数据一律丢掉，不能让列表整体加载失败 ----

    @Test
    fun `缺任务或任务为空_丢掉这条`() {
        assertNull(Schedule.fromJson(json("id" to "s1", "hour" to 9)))
        assertNull(Schedule.fromJson(json("id" to "s1", "task" to "   ")))
    }

    @Test
    fun `缺id_丢掉这条`() {
        // id 是删除/更新时的唯一凭据，缺了就没法操作这一条
        assertNull(Schedule.fromJson(json("task" to "打开设置")))
        assertNull(Schedule.fromJson(json("id" to "", "task" to "打开设置")))
    }

    @Test
    fun `时间越界要夹住_不能排出不存在的时间点`() {
        val a = Schedule.fromJson(json("id" to "s1", "task" to "t", "hour" to 99, "minute" to 99))!!
        assertEquals(23, a.hour)
        assertEquals(59, a.minute)
        val b = Schedule.fromJson(json("id" to "s2", "task" to "t", "hour" to -3, "minute" to -1))!!
        assertEquals(0, b.hour)
        assertEquals(0, b.minute)
    }

    @Test
    fun `缺字段给默认值`() {
        val s = Schedule.fromJson(json("id" to "s1", "task" to "打开设置"))!!
        assertEquals(9, s.hour)
        assertEquals(0, s.minute)
        assertTrue("默认每天重复", s.repeatDaily)
        assertFalse("默认走主屏", s.useVirtualDisplay)
        assertTrue("默认启用", s.enabled)
        assertEquals(0L, s.lastRunAt)
    }

    @Test
    fun `字段类型不对_也不能崩`() {
        // 用户手改过 schedules.json 就是这样：hour 写成了字符串、enabled 写成数字。
        // opt* 要能兜住，最坏是退回默认值，不能让整张列表加载不出来
        val s = Schedule.fromJson(
            json("id" to "s1", "task" to "打开设置", "hour" to "9点", "enabled" to 1)
        )
        assertNotNull(s)
        assertEquals("解析不出来就用默认值", 9, s!!.hour)
    }

    // ---- 往返：落盘再读回来必须一字不差 ----

    @Test
    fun `json往返_每个字段都要保住`() {
        val back = Schedule.fromJson(full().toJson())
        assertNotNull(back)
        assertEquals(full(), back)
    }

    @Test
    fun `副屏开关必须能往返`() {
        // 后加字段最容易只在 toJson 里写上、fromJson 里忘了读（或反过来）
        assertTrue(Schedule.fromJson(full().toJson())!!.useVirtualDisplay)
        val mainScreen = full().copy(useVirtualDisplay = false)
        assertFalse(Schedule.fromJson(mainScreen.toJson())!!.useVirtualDisplay)
    }
}
