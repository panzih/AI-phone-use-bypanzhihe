package com.aiphone.assistant.schedule

import java.util.UUID

/**
 * 定时任务 → 主界面的**进程内**交接点。
 *
 * ## 为什么不能再用 Intent extra 传任务
 *
 * `MainActivity` 必须导出（桌面启动器要能拉起它）。导出之后，任何已安装
 * 应用都可以对它发一个带 `run_task` 的显式 Intent；只要用户给过无障碍权限，
 * 这等于把「操作手机」的能力开放给了任意第三方 App。
 *
 * 所以任务内容不再经过 Intent，而是先放进这个进程内对象，再只把一次性的
 * 随机令牌传给主界面。外部 App 即使能拉起 `MainActivity`：
 *
 *  - 它拿不到令牌（随机 UUID，只存在于本进程内存）；
 *  - 它也没法把任务内容写进这里（进程内存不跨应用）。
 *
 * 令牌对不上时 [consume] 不会取走任何东西，普通启动也就不会误跑定时任务。
 */
internal object PendingRunTask {

    /** 一条待执行的定时任务。 */
    data class Task(
        val text: String,
        val scheduleId: String?,
        val useVirtualDisplay: Boolean,
    )

    private class Entry(val token: String, val task: Task)

    /**
     * 同一个定时任务只保留最后一次交接：重复任务每天触发时，旧的通知
     * 会被新通知覆盖，旧令牌自然作废，也不会在内存里越积越多。
     */
    private val pending = HashMap<String, Entry>()

    /**
     * 存下任务并返回一次性令牌。
     *
     * 以 `scheduleId` 为槽位；没有 id 的调用（测试/极端兜底）用任务文本。
     */
    @Synchronized
    fun offer(task: Task): String {
        val token = UUID.randomUUID().toString()
        pending[task.scheduleId ?: task.text] = Entry(token, task)
        return token
    }

    /**
     * 令牌匹配才取走任务，取走即清空（一次性）。
     *
     * 令牌为空或不匹配时原样保留：用户从桌面正常打开 App 不应该
     * "顺手"把一条还没被接手的定时任务跑掉。
     */
    @Synchronized
    fun consume(token: String?): Task? {
        if (token.isNullOrBlank()) return null
        val key = pending.entries.firstOrNull { it.value.token == token }?.key ?: return null
        return pending.remove(key)?.task
    }

    /** 主界面通过 Intent 拿到的交接令牌字段名。 */
    const val EXTRA_HANDOFF_TOKEN = "schedule_handoff_token"
}
