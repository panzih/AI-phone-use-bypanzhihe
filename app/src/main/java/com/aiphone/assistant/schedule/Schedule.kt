package com.aiphone.assistant.schedule

import android.content.Context
import com.aiphone.assistant.log.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条定时任务。
 *
 * ## 为什么只做"每天一次"和"只跑一次"
 *
 * 更复杂的（每周几、间隔重复、cron 表达式）在这台设备上意义不大：
 * 定时任务必须**手机是解锁状态、屏幕亮着**才能真正执行（见 [Scheduler] 的说明）。
 * 一个半夜三点唤醒设备去点屏幕的任务，跑成功的概率本来就很低。
 * 先把"早上九点提醒我打卡"这种最常见的场景做扎实。
 */
data class Schedule(
    val id: String,
    /** 要交给 AI 的任务，和主界面输入框里写的是同一套 */
    val task: String,
    val hour: Int,
    val minute: Int,
    /** true = 每天这个点；false = 只跑一次 */
    val repeatDaily: Boolean,
    val enabled: Boolean = true,
    val lastRunAt: Long = 0L,
) {
    /** 到点时间的人话，例如「每天 09:30」 */
    fun timeLabel(): String {
        val t = "%02d:%02d".format(hour, minute)
        return if (repeatDaily) "每天 $t" else "仅一次 $t"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("task", task)
        put("hour", hour)
        put("minute", minute)
        put("repeatDaily", repeatDaily)
        put("enabled", enabled)
        put("lastRunAt", lastRunAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Schedule? = runCatching {
            val task = o.optString("task").trim()
            if (task.isBlank()) return null
            Schedule(
                id = o.optString("id").ifBlank { return null },
                task = task,
                hour = o.optInt("hour", 9).coerceIn(0, 23),
                minute = o.optInt("minute", 0).coerceIn(0, 59),
                repeatDaily = o.optBoolean("repeatDaily", true),
                enabled = o.optBoolean("enabled", true),
                lastRunAt = o.optLong("lastRunAt", 0L),
            )
        }.getOrNull()
    }
}

/**
 * 定时任务的存取。
 *
 * 存成一个 json 文件而不是 SharedPreferences：条目是**列表**，
 * 而且用户可能想自己看一眼、改一改。也方便导出时一起带走。
 */
object ScheduleStore {

    private const val TAG = "ScheduleStore"
    private const val FILE = "schedules.json"

    private fun file(context: Context): File =
        File(AppLog.macroDir(context).parentFile, FILE)

    fun loadAll(context: Context): List<Schedule> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Schedule.fromJson(it) }
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, list: List<Schedule>): Boolean = runCatching {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        file(context).writeText(arr.toString(2))
        true
    }.onFailure { android.util.Log.w(TAG, "保存定时任务失败：${it.message}") }
        .getOrDefault(false)

    fun upsert(context: Context, schedule: Schedule): List<Schedule> {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == schedule.id }
        if (idx >= 0) list[idx] = schedule else list.add(schedule)
        return list.sortedBy { it.hour * 60 + it.minute }.also { save(context, it) }
    }

    fun delete(context: Context, id: String): List<Schedule> =
        loadAll(context).filterNot { it.id == id }.also { save(context, it) }

    fun newId(): String = "s" + System.currentTimeMillis()
}
