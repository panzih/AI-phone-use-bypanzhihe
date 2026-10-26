package com.aiphone.assistant.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近若干条日志的**内存副本**，专门给崩溃现场用。
 *
 * ## 为什么光有 run.log 不够
 *
 * `run.log` 是逐行 append 的，正常情况下进程被杀也不丢最后几行。但有两种情况
 * 它一定救不了：
 *
 * 1. **崩溃发生在没有活动任务的时候** —— 那时 `RunLogger` 还没创建（或者已经关了），
 *    行根本没有写的地方。0.10.6 那个「点发送就崩」就属于这一类：拦截发生在
 *    任务真正开始之前，用户导出日志时一行都看不到。
 * 2. **进程被系统直接打死**（`ForegroundServiceDidNotStartInTimeException`
 *    这类是系统杀进程，不走我们的任何清理路径），文件句柄还在缓冲区没刷出去。
 *
 * 所以这里在内存里留一份滚动副本。崩溃处理器在上报时把它一起吐出来 ——
 * 内存里的东西不需要任何 IO 就已经在那里了。
 *
 * ## 为什么要有上限
 *
 * 无上限的集合在长时间运行的任务里会一直涨（每一步的界面元素列表就是几十 KB）。
 * 按条数封顶（[capacity]）比按字节好算，而每条再单独截断（[MAX_ENTRY_CHARS]）
 * 兜住"一条消息特别长"的情况 —— 两道一起才能保证内存占用有确定上界。
 *
 * @param capacity 最多留多少条。超过就丢最旧的。
 */
class RecentLog(val capacity: Int = DEFAULT_CAPACITY) {

    private class Entry(
        val at: Long,
        val level: String,
        val tag: String,
        val message: String,
    )

    private val lock = Any()

    /** 环形缓冲本体。用数组而不是 ArrayDeque：下标运算比每次 add/remove 便宜 */
    private val ring = arrayOfNulls<Entry>(capacity)

    /** 下一条要写的位置 */
    private var next = 0

    /** 累计写了多少条（超过 capacity 后就是"被覆盖掉的总数 + capacity"） */
    private var total = 0

    /** 一共写了多少条（含已被覆盖的）。排查时用来看"日志覆盖了多少时间跨度" */
    val written: Int get() = synchronized(lock) { total }

    /**
     * 记一条。
     *
     * @param level "I" / "W" / "E"，只影响输出时的前缀，不做过滤 ——
     *              崩溃现场最怕的就是"刚好把需要的那行过滤掉了"。
     */
    fun add(level: String, tag: String, message: String, at: Long = System.currentTimeMillis()) {
        // 单条截断：一行几十 KB 的界面元素列表对崩溃排查没用，
        // 但会把整个缓冲挤满，把真正的线索挤出去
        val body = if (message.length > MAX_ENTRY_CHARS) {
            message.take(MAX_ENTRY_CHARS) + "…（截断，原长 ${message.length}）"
        } else {
            message
        }
        synchronized(lock) {
            ring[next] = Entry(at, level, tag, body)
            next = (next + 1) % capacity
            total++
        }
    }

    /** 清空。用于「删除日志」之后，避免崩溃时把刚删的内容又吐出来 */
    fun clear() {
        synchronized(lock) {
            ring.fill(null)
            next = 0
            total = 0
        }
    }

    /**
     * 按时间正序导出现有内容（最旧的在前，最新的在后）。
     *
     * 缓冲没满时从头开始；满了之后从 [next] 开始 —— 它指向的正是最旧的那条
     * （下一条要写的会覆盖它）。
     */
    fun dump(): List<String> = synchronized(lock) {
        val size = minOf(total, capacity)
        if (size == 0) return emptyList()
        val start = if (total < capacity) 0 else next
        (0 until size).mapNotNull { i ->
            val e = ring[(start + i) % capacity] ?: return@mapNotNull null
            "${TIME.format(Date(e.at))} ${e.level}/纸盒 ${e.tag}: ${e.message}"
        }
    }

    /** dump 的字符串形式，直接拼进崩溃报告的 */
    fun dumpText(header: String = "最近日志"): String {
        val lines = dump()
        if (lines.isEmpty()) return "$header：（空）"
        return buildString {
            appendLine("$header（${lines.size} 条，缓冲容量 $capacity，累计写入 $written）")
            lines.forEach { appendLine(it) }
        }
    }

    companion object {
        /** 400 条足够覆盖"崩之前那一两分钟"，又把内存压在几十 KB 量级 */
        const val DEFAULT_CAPACITY = 400

        /** 单条上限。界面元素列表整屏也就几十 KB，截到这里只剩骨架，但够定位 */
        const val MAX_ENTRY_CHARS = 2000

        /** 带毫秒：崩溃排查经常要区分同一秒内的先后顺序 */
        private val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
