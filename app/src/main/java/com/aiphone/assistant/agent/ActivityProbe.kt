package com.aiphone.assistant.agent

import android.content.Context
import com.aiphone.assistant.shell.ShizukuBridge

/**
 * 活动任务栈探测（`dumpsys activity activities`）。
 *
 * ## 为什么单独成类
 *
 * 通道迁移（主屏↔副屏）和「回桌面自动切副屏」都要问同样的问题：
 * *某个包的任务栈 id 是多少？某个 task 还在不在？某个屏的顶部任务是谁？*
 * 原来这五段查询散在 [Agent] 的两千六百行里，混着迁移流程，谁都复用不了。
 *
 * ## 为什么解析做成纯函数
 *
 * `dumpsys` 的文本格式是**系统私有实现**，不同 ROM 有出入；解析错了
 * 轻则"找不到任务栈"，重则把别的应用当成目标迁移。这类解析只能靠
 * 单测钉住 —— 所以真正干活的 [parseTaskIdForPackage] 等全部接受
 * dump 字符串、返回结果，**不碰 binder**，测试直接喂文本
 * （见 `ActivityProbeTest`）。类本身只负责"取数据 + 转发"。
 *
 * 查询走 Shizuku（shell 身份），每条都是独立的一次 `dumpsys` 调用 ——
 * 调用方知道这一点，所以它们都只在"包名变化 / 迁移前复核"这类低频
 * 时机调用，不放在每步循环里。
 */
class ActivityProbe(private val context: Context) {

    private suspend fun dump(): String = ShizukuBridge.run(context, CMD_DUMP)

    /** 主屏（Display #0）段里按包名找任务，取最后一个匹配（最近活跃）。-1 = 没找到 */
    suspend fun resolveTaskIdForPackage(pkg: String): Int = parseTaskIdForPackage(dump(), pkg)

    /** 该 taskId 是否还在活动列表（迁移前校验缓存） */
    suspend fun taskExists(taskId: Int): Boolean = dumpContainsTask(dump(), taskId)

    /**
     * 这个 task 的**根任务** id。
     *
     * `am display move-stack` 在 Android 13 上要的是**根任务** id
     * （`moveRootTaskToTaskDisplayArea` 只认 RootTask）。而
     * `topResumedActivity ... t<id>` 给的是**子任务** id —— 桌面/启动器
     * 这类应用的任务几乎总是子任务（真实案例：蓝叠上 launcher 活动在
     * `Task{... #196 ... rootTaskId=195}` 里，拿 196 去 move-stack 直接
     * `Unknown rootTaskId=196`）。普通应用的任务通常本身就是根任务。
     */
    suspend fun rootTaskIdOf(taskId: Int): Int = parseRootTaskId(dump(), taskId)

    /** 指定 display 段顶部 resumed 任务：(taskId, 包名)。null = 读不到 */
    suspend fun currentTaskOnDisplay(displayId: Int): Pair<Int, String>? =
        parseTopResumedTask(dump(), displayId)

    /** 该 display 段的 topResumedActivity 是不是这个 task（判断回迁后有没有获焦） */
    suspend fun isTopResumedOnDisplay(taskId: Int, displayId: Int): Boolean =
        parseIsTopResumed(dump(), taskId, displayId)

    /** 校验 task 是否已经在指定 display 的段落里 */
    suspend fun verifyTaskOnDisplay(taskId: Int, displayId: Int): Boolean =
        parseTaskInDisplay(dump(), taskId, displayId)

    companion object {

        const val CMD_DUMP = "dumpsys activity activities"

        /**
         * 截取 `Display #<id>` 这一段（右界到下一个 Display 段）。
         *
         * 为什么必须按段截：不同屏上可能有**同样 taskId 的残留记录**
         * （迁移中途两边都留着引用），全 dump 搜索会跨段误判"已经迁过去了"。
         */
        internal fun displaySegment(dump: String, displayId: Int): String? {
            val start = dump.indexOf("Display #$displayId")
            if (start < 0) return null
            val next = dump.indexOf("Display #", start + 1)
            return if (next < 0) dump.substring(start) else dump.substring(start, next)
        }

        /**
         * `Display #0` 段里按包名找 taskId，取**最后一个**匹配。
         *
         * 取最后一个是因为同一包可能开过多个 task，最近活跃的那个排在后面。
         * 只查主屏段：这个方法服务于"把主屏上的 app 迁去副屏"，查副屏没有意义。
         */
        internal fun parseTaskIdForPackage(dump: String, pkg: String): Int {
            val seg = displaySegment(dump, 0) ?: return -1
            return Regex("""${Regex.escape(pkg)}/\S+\s+t(\d+)""")
                .findAll(seg)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .lastOrNull() ?: -1
        }

        /** 整个 dump 里含不含这个 task（`am` 输出里 task 的形态是 ` t<id>}`） */
        internal fun dumpContainsTask(dump: String, taskId: Int): Boolean =
            dump.contains(" t$taskId}")

        /**
         * 从 dump 里解析 taskId 对应的**根任务** id。
         *
         * 子任务的行带 `rootTaskId=<N>`（实测样例）：
         * ```
         * * Task{4430802 #196 type=home I=com.uncube.launcher3/... rootTaskId=195 ...}
         * ```
         * 命中 → 返回 195。没有 `rootTaskId=` 字段的（本身就是根任务）原样返回。
         * 解析不出来时也原样返回 —— 宁可按原 id 试一次拿真实报错，也不静默换目标。
         */
        internal fun parseRootTaskId(dump: String, taskId: Int): Int {
            val child = Regex("""Task\{\S+\s+#$taskId\s[^}]*?rootTaskId=(\d+)""")
                .find(dump)
                ?: return taskId
            return child.groupValues[1].toIntOrNull() ?: taskId
        }

        /**
         * 指定 display 段第一个 topResumedActivity 的 (taskId, 包名)。
         *
         * 行样例：
         * ```
         * topResumedActivity=ActivityRecord{93e952e u0 com.android.settings/.SubSettings t190}
         * ```
         */
        internal fun parseTopResumedTask(dump: String, displayId: Int): Pair<Int, String>? {
            val seg = displaySegment(dump, displayId) ?: return null
            val line = seg.lineSequence().firstOrNull { it.contains("topResumedActivity=ActivityRecord") }
                ?: return null
            val m = Regex("""ActivityRecord\{\S+\s+\S+\s+([\w.]+)/\S+\s+t(\d+)\}""").find(line)
                ?: return null
            val taskId = m.groupValues[2].toIntOrNull() ?: return null
            return taskId to m.groupValues[1]
        }

        /** 指定 display 段的 topResumedActivity 是不是这个 task */
        internal fun parseIsTopResumed(dump: String, taskId: Int, displayId: Int): Boolean {
            val seg = displaySegment(dump, displayId) ?: return false
            val line = seg.lineSequence().firstOrNull { it.contains("topResumedActivity=ActivityRecord") }
                ?: return false
            return line.contains(" t$taskId}")
        }

        /** 该 task 是否出现在指定 display 段内 */
        internal fun parseTaskInDisplay(dump: String, taskId: Int, displayId: Int): Boolean {
            val seg = displaySegment(dump, displayId) ?: return false
            return seg.contains(" t$taskId}")
        }
    }
}
