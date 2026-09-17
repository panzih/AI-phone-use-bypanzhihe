package com.aiphone.assistant.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aiphone.assistant.MainActivity
import com.aiphone.assistant.R

/**
 * 定时任务的触发点。
 *
 * 只处理"闹钟到点"这一件事（[ACTION_FIRE]）。
 *
 * 开机重排交给 [BootReceiver] —— 那个必须导出（系统要能发广播给它），
 * 而这个**不导出**。合成一个的话，导出的那个就等于对外开了一个
 * "随便谁都能触发定时任务"的口子。
 *
 * ## 怎么把任务跑起来
 *
 * 收到闹钟时，这个进程可能压根没在运行，而 `BroadcastReceiver` 只有
 * 十来秒的存活时间 —— 不可能在这里跑 AI 循环。所以做法是**把主界面拉起来**
 * 并把任务交给它（`MainActivity` 收到 extra 就自动开始执行）。
 *
 * 从后台启动 Activity 在 Android 10+ 默认是被拦的，但有一条豁免：
 * **用户授予过「显示在其他应用上层」的应用不受限制** —— 而这个应用本来
 * 就需要那个权限（悬浮窗）。所以这条路是通的。
 *
 * 为了不把宝押在一条豁免上，同时**发一条通知**：万一 Activity 真被拦了，
 * 用户点通知也能开始。主界面成功接手后会把它撤掉。
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        fire(context, id)
    }

    private fun fire(context: Context, id: String) {
        val all = ScheduleStore.loadAll(context)
        val schedule = all.firstOrNull { it.id == id } ?: run {
            Log.w(TAG, "闹钟响了但找不到任务 $id，忽略")
            return
        }
        if (!schedule.enabled) return

        Log.i(TAG, "触发定时任务：${schedule.task}")

        // 记下这次跑过；一次性的任务跑完就自动关掉
        val updated = schedule.copy(
            lastRunAt = System.currentTimeMillis(),
            enabled = schedule.repeatDaily,
        )
        ScheduleStore.upsert(context, updated)

        // 重复任务：登记下一次
        if (updated.enabled) Scheduler.schedule(context, updated)

        notifyUser(context, schedule)
        startMainActivity(context, schedule)
    }

    /**
     * 尝试直接把界面拉起来。
     *
     * 失败不抛异常（后台启动限制是"静默拦截"而不是报错），所以这里
     * 只做尽力而为，真正的兜底是那条通知。
     */
    private fun startMainActivity(context: Context, schedule: Schedule) {
        runCatching {
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(MainActivity.EXTRA_RUN_TASK, schedule.task)
                putExtra(MainActivity.EXTRA_SCHEDULE_ID, schedule.id)
                putExtra(MainActivity.EXTRA_SCHEDULE_VIRTUAL_DISPLAY, schedule.useVirtualDisplay)
            }
            context.startActivity(i)
        }.onFailure { Log.w(TAG, "拉起主界面失败：${it.message}") }
    }

    private fun notifyUser(context: Context, schedule: Schedule) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "定时任务",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "定时任务到点时的提醒" }
            )
        }

        val open = PendingIntent.getActivity(
            context,
            schedule.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_RUN_TASK, schedule.task)
                putExtra(MainActivity.EXTRA_SCHEDULE_ID, schedule.id)
                putExtra(MainActivity.EXTRA_SCHEDULE_VIRTUAL_DISPLAY, schedule.useVirtualDisplay)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val n: Notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.schedule_notify_title))
            .setContentText(schedule.task)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        // 用任务 id 当通知 id，避免多条任务互相覆盖
        runCatching { nm.notify(schedule.id.hashCode(), n) }
    }

    companion object {
        private const val TAG = "ScheduleReceiver"

        const val ACTION_FIRE = "com.aiphone.assistant.action.SCHEDULE_FIRE"
        const val EXTRA_ID = "schedule_id"

        private const val CHANNEL_ID = "schedules"

        /** 主界面接手之后把提醒撤掉 */
        fun cancelNotification(context: Context, scheduleId: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.cancel(scheduleId.hashCode()) }
        }
    }
}

/**
 * 开机 / 应用更新后重新登记定时任务。
 *
 * 闹钟在设备重启时会被系统清空，不重新登记的话用户设好的任务
 * 重启一次就永远不响了 —— 而且没有任何提示，属于最难发现的失败。
 *
 * 这个接收器**必须导出**（系统要跨应用把 BOOT_COMPLETED 发进来），
 * 所以它只做一件事、不接收任何参数，没有可利用的面。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Log.i("BootReceiver", "开机/更新，重新登记定时任务")
                Scheduler.rescheduleAll(context)
            }
        }
    }
}
