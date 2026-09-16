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
 * ## 两层记忆
 *
 *   [LlmDistiller]  模型归纳：任务结束后让 AI 提炼"以后还用得上的"
 *   [RawDistiller]  原样转存：不调模型，只是别把内容丢了
 *
 * 前者是"学习"，后者是"不丢"，两个开关各管一件事。
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
     * 直接清就是把记忆丢了。这个顺序由调用方保证
     * （MainActivity 里每次都是 persist 之后才 clear）。
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
 * 两个实现：调模型的 [LlmDistiller] 和原样转存的 [RawDistiller]。
 * 留在接口后面是因为"什么时候该存""存到哪""怎么读回来"跟怎么归纳无关。
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


/**
 * 让模型把操作记录归纳成一份「用户洞察」。
 *
 * ## 为什么值得多花一次模型调用
 *
 * 上下文里的东西 99% 是这一次任务的细节（"点了 3 号按钮""界面没变化"），
 * 下次一点用都没有。真正该留下的是**跨任务成立的东西**：
 * 你常用什么 App、你有哪些固定要求、这台机器上哪些页面有坑。
 * 这需要判断力，不是 `grep` 能干的。
 *
 * ## 两个刻意的设计
 *
 * **入参截断。** 一次任务可能有几十轮、上万字符。全喂进去归纳一次要花不少钱，
 * 而"最近的几轮"通常信息量最大（前面大多是重复的界面操作）。所以按
 * [MAX_INPUT_CHARS] 从**尾部**往前截。
 *
 * **明确允许"没东西可记"。** 提示词里直接要求"没什么值得记的就只输出「无」"，
 * 否则模型会为了交差硬编点什么出来 —— 那比没有记忆更糟，因为它会被当成事实
 * 在以后的任务里使用。
 */
class LlmDistiller(
    private val llm: com.aiphone.assistant.llm.LlmClient,
) : InsightDistiller {

    override suspend fun distill(turns: List<Turn>): Insight? {
        if (turns.isEmpty()) return null

        val transcript = buildString {
            turns.forEach { t ->
                val who = when (t.role) {
                    Turn.Role.USER -> "用户"
                    Turn.Role.AI -> "AI"
                    Turn.Role.ACTION -> "动作"
                }
                appendLine("$who：${t.text}")
            }
        }
        val clipped = if (transcript.length > MAX_INPUT_CHARS) {
            "（前面省略了 ${transcript.length - MAX_INPUT_CHARS} 个字符的早期记录）\n" +
                transcript.takeLast(MAX_INPUT_CHARS)
        } else {
            transcript
        }

        val result = llm.chat(
            system = SYSTEM,
            history = listOf(
                com.aiphone.assistant.llm.ChatTurn(
                    com.aiphone.assistant.llm.ChatTurn.USER,
                    "下面是一次任务的操作记录：\n\n$clipped",
                )
            ),
            imagePng = null,
        )

        val text = when (result) {
            is com.aiphone.assistant.llm.LlmResult.Fail -> {
                AppLog.w("归纳记忆失败：${result.message}", "记忆")
                return null
            }
            is com.aiphone.assistant.llm.LlmResult.Ok -> result.text.trim()
        }

        // 模型判断没有值得记的
        if (text.isBlank() || text == "无" || text.startsWith("无\n")) {
            AppLog.i("这次没有值得沉淀的内容", "记忆")
            return null
        }

        val lines = text.lines()
        val title = lines.firstOrNull { it.isNotBlank() }
            ?.trimStart('#', ' ', '-')
            ?.take(24)
            ?.ifBlank { null }
            ?: "用户洞察"
        val body = lines.drop(1).joinToString("\n").trim().ifBlank { text }

        return Insight(title = title, markdown = body)
    }

    private companion object {
        /** 喂给模型的记录上限（字符）。超出就从最早的部分砍掉 */
        const val MAX_INPUT_CHARS = 6000

        val SYSTEM = """
你是"记忆整理助手"。用户在安卓手机上用 AI 操作手机，下面是一轮操作的记录。

请从中提炼**以后还用得上的信息**，写成一份简短的 markdown。

只写这几类：
- 用户的偏好和习惯（例如「喜欢深色模式」「发消息后要确认一下」）
- 常用应用及其包名（记录里出现过的）
- 这台设备上的坑（例如「这个页面要等两秒才加载完」「那个按钮要点两次」）
- 用户对任务的固定要求

规则（很重要）：
1. **只写记录里确实出现过的，绝不编造。** 宁可少写。
2. 只写对**未来的操作**有用的。这次任务的具体结果（例如「打开了 WiFi」）
   不用写 —— 那是已经完成的事，不是记忆。
3. 用短句和列表，不要客套话，不要总结这次任务的经过。
4. 没什么值得记的，就**只输出一个字：无**

输出格式：第一行是一句话标题（不超过 15 字），第二行开始是正文。
""".trimIndent()
    }
}
