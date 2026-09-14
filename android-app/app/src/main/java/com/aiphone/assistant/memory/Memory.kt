package com.aiphone.assistant.memory

import android.content.Context
import com.aiphone.assistant.log.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上下文与记忆。
 *
 * ## 这里解决的是什么问题
 *
 * 每次任务都从零开始，AI 就永远不认识你 —— 不知道你常用哪几个 App、
 * 不知道你上次说过"消息发完要确认一下"。
 *
 * 所以要把上下文**沉淀**下来：
 *
 * ```
 * 一轮对话结束（或闲置超时）
 *   → 把上下文交给 AI 分析
 *   → AI 写成一份 md（"用户洞察"）
 *   → 下次执行任务前，把相关的 md 喂回去
 * ```
 *
 * ## 为什么是 md 而不是数据库
 *
 * 这些洞察是给 **AI 读**的，不是给程序查的。md 有两个别的好处：
 *   1. 用户自己能用任何编辑器看、改、删 —— 记忆不该是黑盒
 *   2. 可以整个目录丢进任何支持"知识库"的工具里
 *
 * ## 当前进度
 *
 * 落盘、读取、清理策略都做好了，**只差真正的 AI 分析那一步** ——
 * 那需要 LLM 客户端，属于下一步。所以 [InsightDistiller] 是个接口，
 * 现在是空实现，接上模型就能用，上层一行不用改。
 */

/** 一轮对话。 */
data class Turn(
    val role: Role,
    val text: String,
    val at: Long = System.currentTimeMillis(),
) {
    enum class Role { USER, AI, ACTION }
}

/**
 * 会话上下文。
 *
 * 只管"当前攒了哪些轮次"和"该不该清"，不关心内容怎么来。
 */
class Conversation {

    private val turns = mutableListOf<Turn>()

    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set

    val size: Int get() = synchronized(turns) { turns.size }

    fun add(turn: Turn) {
        synchronized(turns) { turns.add(turn) }
        lastActivityAt = System.currentTimeMillis()
    }

    fun snapshot(): List<Turn> = synchronized(turns) { turns.toList() }

    /**
     * 清空。
     *
     * 注意 [clear] 之前应该先让 [InsightStore] 把内容沉淀掉 ——
     * 直接清就是把记忆丢了。这个顺序由调用方保证（见 shouldAutoClear 的注释）。
     */
    fun clear() {
        synchronized(turns) { turns.clear() }
        lastActivityAt = System.currentTimeMillis()
    }

    /** 闲置了多久（分钟） */
    fun idleMinutes(): Long =
        (System.currentTimeMillis() - lastActivityAt) / 60_000L

    /**
     * 是否闲置超过了阈值。
     *
     * @param minutes 0 表示从不判超时
     *
     * 注意这里**只判断时间**，不决定"清掉还是存下来" ——
     * 那取决于"保存记忆"开关和有没有值得沉淀的内容，
     * 由调用方决定。判断和动作分开，是为了不让一个函数承担两件事。
     */
    fun isIdleBeyond(minutes: Int): Boolean =
        minutes > 0 && size > 0 && idleMinutes() >= minutes
}

/**
 * 一份"用户洞察"。
 *
 * @param title 一句话概括，同时用作文件名
 * @param markdown 正文
 */
data class Insight(
    val title: String,
    val markdown: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val fileName: String
        get() = InsightStore.sanitize(title) + ".md"
}

/**
 * 洞察的落盘与读取。
 *
 * 文件放在 `<应用私有目录>/纸盒/insights/`，一份一个 md，
 * 文件名带日期，方便按时间排。
 */
object InsightStore {

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun dir(context: Context): File = AppLog.insightDir(context)

    /**
     * 写一份洞察。
     *
     * 文件头带上时间和来源，因为将来 AI 读回来时要能判断"这是什么时候的认知"——
     * 半年前的偏好不一定还成立。
     */
    fun save(context: Context, insight: Insight): File? = runCatching {
        val f = File(dir(context), "${stamp.format(Date(insight.createdAt))}_${insight.fileName}")
        f.writeText(
            buildString {
                appendLine("# ${insight.title}")
                appendLine()
                appendLine("> 生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(insight.createdAt))}")
                appendLine("> 来源：纸盒 · AI 操作手机")
                appendLine()
                appendLine(insight.markdown)
            }
        )
        AppLog.i("已保存用户洞察：${f.name}", "InsightStore")
        f
    }.getOrNull()

    /** 全部洞察，最新的在前 */
    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun readAll(context: Context): String =
        list(context).joinToString("\n\n---\n\n") { runCatching { it.readText() }.getOrDefault("") }

    fun deleteAll(context: Context): Int {
        val files = list(context)
        files.forEach { it.delete() }
        return files.size
    }

    /**
     * 文件名清理。
     *
     * 洞察的标题是 AI 生成的，里面可能有斜杠、冒号、问号这些
     * 在文件名里非法的字符，直接用会导致创建失败。
     */
    fun sanitize(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\s/\\\\:*?\"<>|\\n\\r\\t]+"), "_")
            .take(32)
            .trim('_')
        return cleaned.ifBlank { "洞察" }
    }
}

/**
 * 把上下文分析成洞察。
 *
 * 这一层是接口，因为实现要调大模型 —— 那是下一步的事。
 * 留成接口的意义在于：**上层的"什么时候该存""存到哪""怎么读回来"现在就是完整可用的**，
 * 接上模型只需实现这一个方法。
 */
interface InsightDistiller {
    /**
     * @return 分析出的洞察；返回 null 表示这次没有值得沉淀的内容
     */
    suspend fun distill(turns: List<Turn>): Insight?
}

/**
 * 占位实现：不调模型，只把原始上下文原样存成 md。
 *
 * 这样即使模型还没接上，"保存记忆"这个开关也是**真的在工作**——
 * 内容不会丢，只是还没被分析过。比做一个点了没反应的按钮诚实。
 */
class RawDistiller : InsightDistiller {
    override suspend fun distill(turns: List<Turn>): Insight? {
        if (turns.isEmpty()) return null
        val body = buildString {
            appendLine("（以下为原始上下文，尚未经 AI 归纳）")
            appendLine()
            turns.forEach { t ->
                val who = when (t.role) {
                    Turn.Role.USER -> "用户"
                    Turn.Role.AI -> "AI"
                    Turn.Role.ACTION -> "动作"
                }
                appendLine("- **$who**：${t.text}")
            }
        }
        return Insight(title = "上下文快照", markdown = body)
    }
}
