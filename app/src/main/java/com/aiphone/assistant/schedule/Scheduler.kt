package com.aiphone.assistant.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * 定时任务的调度。
 *
 * ## 一个必须先说清楚的现实限制
 *
 * 定时任务到点时，**手机必须是解锁状态、屏幕亮着**，AI 才操作得了 ——
 * 无障碍服务读不到锁屏后面的界面，锁着屏什么也点不了。
 *
 * 所以这不是"半夜自动帮你干活"的功能，而是**"到点提醒你，然后你看着它干"**。
 * 界面上如实写明这一点，比让用户以为它能无人值守要好。
 *
 * ## 精确闹钟的权限
 *
 * Android 12 起有了"精确闹钟"这个权限，而 **Android 14 默认拒绝**
 * （要用户去系统设置里单独允许）。
 * 所以这里的策略是：
 *
 *   能精确就精确（`setExactAndAllowWhileIdle`）
 *   不能就退到不精确（`setAndAllowWhileIdle`，可能晚几分钟）
 *
 * 两者都设了 wakeup 语义，区别只在准时程度。界面上会显示当前是哪种，
 * 并提供去授权的入口 —— 不然用户会觉得"设了 9 点怎么 9 点 07 才动"。
 */
object Scheduler {

    private const val TAG = "Scheduler"

    /** 算下一次该在什么时候响 */
    fun nextTriggerAt(schedule: Schedule, from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, schedule.hour)
            set(Calendar.MINUTE, schedule.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 今天这个点已经过了就顺延
        if (cal.timeInMillis <= from) {
            if (schedule.repeatDaily) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            } else {
                // 只跑一次且今天已过点：顺延到明天，避免"设完就立刻触发"
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** 登记一条。重复任务只登记下一次，响过之后由接收器再登记下一次 */
    fun schedule(context: Context, schedule: Schedule) {
        if (!schedule.enabled) {
            cancel(context, schedule.id)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = nextTriggerAt(schedule)
        val pi = pendingIntent(context, schedule.id)

        val exact = canScheduleExact(context)
        runCatching {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure {
            // 精确闹钟被系统拒了（有些 ROM 会在运行时收回权限），退回不精确
            Log.w(TAG, "精确闹钟设置失败，退回不精确：${it.message}")
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
        Log.i(TAG, "已登记 ${schedule.id}：${schedule.timeLabel()}（精确=$exact）")
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, id))
        Log.i(TAG, "已取消 $id")
    }

    /** 开机后重新登记 —— 闹钟在重启时会被系统清空 */
    fun rescheduleAll(context: Context) {
        ScheduleStore.loadAll(context)
            .filter { it.enabled }
            .forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION_FIRE)
            .putExtra(ScheduleReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            // FLAG_IMMUTABLE：Android 12+ 强制要求指明可变性。
            // 这里不需要系统往里塞东西，所以用不可变
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
