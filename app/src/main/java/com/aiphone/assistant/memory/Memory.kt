package com.aiphone.assistant.memory

import android.content.Context
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 不知道你上次说过"消息发完要确认一下"、不知道这台机器上哪个页面要等两秒。
 *
 * 所以要有记忆。而记忆的关键在于**它怎么进出上下文**：
 *
 * ```
 *   任务结束   →  AI 归纳出一条洞察  →  追加到记忆文件末尾（只增不减）
 *
 *   新开上下文 →  把记忆塞进系统提示词（只在这一个时机）
 *   上下文进行中 → 不塞，模型需要就调 recall_memory 技能
 * ```
 *
 * ## 为什么只在"新开上下文"时注入
 *
 * 服务端的上下文缓存是**按前缀匹配**的。往一段正在进行的对话的系统提示词里
 * 塞东西，等于把前缀整段作废 —— 下一次请求全部按未命中计价，
 * 那比省下的这点记忆 token 贵得多。
 *
 * 而"新开上下文"时本来就没有可复用的前缀，这时注入是免费的。
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
     * 从持久化的状态恢复。
     *
     * **连 lastActivityAt 一起恢复**：光有内容不记时间的话，
     * 重启之后就判不出"闲置超过 24 小时"这一档到底过期没有。
     */
    fun restore(savedTurns: List<Turn>, savedActivityAt: Long) {
        synchronized(turns) {
            turns.clear()
            turns.addAll(savedTurns)
        }
        if (savedActivityAt > 0) lastActivityAt = savedActivityAt
    }

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
     * 这里**只判断时间**，不决定"清掉还是保留" —— 那取决于上下文策略，
     * 由调用方决定。判断和动作分开，一个函数只做一件事。
     */
    fun isIdleBeyond(minutes: Int): Boolean =
        minutes > 0 && size > 0 && idleMinutes() >= minutes
}

/**
 * 一条要写进记忆的洞察。
 *
 * @param title 一句话标题，写进二级标题里
 * @param body markdown 正文
 */
data class MemoryEntry(
    val title: String,
    val body: String,
    val at: Long = System.currentTimeMillis(),
)

/**
 * 记忆的落盘与读取。
 *
 * ## 一个文件，只增不减
 *
 * 所有洞察追加到同一个 `memory.md` 的**末尾**，新的在最下面。
 * 不删、不改、不重排 —— 三条理由：
 *
 *   1. **安全**：记忆是 AI 写的。让它有机会"改写"历史，就可能把用户
 *      纠正过的错误认知又改回去。追加式没有这个风险
 *   2. **便宜**：追加是纯文本 append，不需要重写整个文件
 *   3. **可审**：用户打开文件能看到完整的来龙去脉，而不是一份被
 *      反复覆盖的摘要
 *
 * 代价是文件会越来越长，所以注入系统提示词时有 [INJECT_CHARS_LIMIT]，
 * 超了只取**最新的**那一段（最新的认知通常最有用）。
 */
object MemoryStore {

    private const val FILE_NAME = "memory.md"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /**
     * 注入系统提示词时的字数上限。
     *
     * 记忆是纯文本，一直涨下去迟早会把系统提示词撑爆。约 6000 字
     * 大致是 3~4k token，对一次任务可以接受 —— 而且只在**新开上下文**
     * 时付这一次。
     */
    const val INJECT_CHARS_LIMIT = 6000

    fun file(context: Context): File = File(AppLog.rootDir(context), FILE_NAME)

    fun exists(context: Context): Boolean =
        file(context).let { it.exists() && it.length() > 0 }

    /** 全量读取。技能要用完整内容 */
    fun read(context: Context): String =
        runCatching { if (file(context).exists()) file(context).readText() else "" }
            .getOrDefault("")

    /** 给系统提示词用的版本：超长就只保留**末尾**那一段 */
    fun readForPrompt(context: Context): String {
        val all = read(context).trim()
        if (all.length <= INJECT_CHARS_LIMIT) return all
        return "（前面省略了较早的记忆）\n\n" + all.takeLast(INJECT_CHARS_LIMIT)
    }

    /**
     * 追加一条。**这是唯一的写入方式**。
     *
     * 每次都写到文件末尾，之前的内容一个字节都不会被碰。
     */
    fun append(context: Context, entry: MemoryEntry): Boolean = runCatching {
        val f = file(context)
        if (!f.exists()) {
            f.writeText(
                buildString {
                    appendLine("# 记忆")
                    appendLine()
                    appendLine("> 纸盒在每次任务结束后自动沉淀的「用户洞察」，只增不减。")
                    appendLine("> AI 在新开上下文时会读到这里的内容，也可以随时调 recall_memory 技能查看。")
                    appendLine()
                }
            )
        }
        f.appendText(formatEntry(entry))
        AppLog.i("记忆 +1：${entry.title}", "记忆")
        true
    }.getOrElse {
        AppLog.w("写记忆失败：${it.message}", "记忆")
        false
    }

    private fun formatEntry(entry: MemoryEntry): String = buildString {
        appendLine()
        appendLine("## ${stamp.format(Date(entry.at))} · ${entry.title}")
        appendLine()
        appendLine(entry.body.trim())
        appendLine()
    }

    /** 条数 + 字符数，给设置页显示 */
    fun stats(context: Context): Pair<Int, Int> {
        val text = read(context)
        if (text.isBlank()) return 0 to 0
        val count = text.lineSequence().count { it.startsWith("## ") }
        return count to text.length
    }
}

/**
 * 让模型把一次任务的记录归纳成一条洞察。
 *
 * ## 为什么值得多花一次模型调用
 *
 * 上下文里 99% 是这一次任务的细节（"点了 3 号按钮""界面没变化"），
 * 下次一点用都没有。真正该留下的是**跨任务成立的东西**：
 * 你常用什么 App、你有哪些固定要求、这台机器上哪些页面有坑。
 * 这需要判断力，不是 grep 能干的。
 *
 * ## 两个刻意的设计
 *
 * **入参从尾部截断。** 一次任务可能几十轮、上万字符，全喂进去要花不少钱，
 * 而最近的几轮信息量最大（前面大多是重复的界面操作）。
 *
 * **不允许"没东西可记"。** 提示词里明确要求每一轮都必须产出一条；
 * 模型万一还是回了「无」，这里用任务原文兜底 —— 用户要的是"每次任务
 * 都留下一条记录"，不是"模型觉得值得才留"。
 */
class LlmDistiller(private val llm: LlmClient) {

    suspend fun distill(turns: List<Turn>): MemoryEntry? {
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
                ChatTurn(ChatTurn.USER, "下面是一次任务的操作记录：\n\n$clipped")
            ),
        )

        val text = when (result) {
            is LlmResult.Fail -> {
                AppLog.w("归纳记忆失败：${result.message}", "记忆")
                return null
            }
            is LlmResult.Ok -> result.text.trim()
        }

        // 模型偶尔会无视"必须写"的指令，回一个「无」或者干脆空。
        // **不允许因此不写记忆**（这是用户明确要求的），所以这里兜一条：
        // 用任务原文顶上，并在日志里点出来。宁可记一条朴素的事实，
        // 也不要出现"这一轮什么都没留下"。
        if (text.isBlank() || text == "无" || text.startsWith("无\n")) {
            AppLog.w("模型没按要求输出记忆，已用任务原文兜底", "记忆")
            val fallback = turns.firstOrNull { it.role == Turn.Role.USER }?.text.orEmpty().trim()
            if (fallback.isBlank()) return null
            return MemoryEntry(
                title = fallback.take(20),
                body = "（模型未给出归纳，自动记录的任务原文）\n\n$fallback",
            )
        }

        val lines = text.lines()
        val title = lines.firstOrNull { it.isNotBlank() }
            ?.trimStart('#', ' ', '-')
            ?.take(24)
            ?.ifBlank { null }
            ?: "用户洞察"
        val body = lines.drop(1).joinToString("\n").trim().ifBlank { text }

        return MemoryEntry(title = title, body = body)
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
1. **只写记录里确实出现过的，绝不编造。**
2. 用短句和列表，不要客套话，不要复述这次任务的经过。
3. **每一条记录都必须产出一条记忆，不允许以"没什么可写的"为由跳过。**
   这轮任务本身的结果（例如「打开了 WiFi」）确实不是记忆，但每一轮操作
   都必然会暴露一点关于**用户偏好、常用应用、设备特性或任务要求**的信息 —— 
   哪怕是「这位用户偏好用语音输入而不是打字」这种很轻的一条，也要写出来。
4. 实在没有偏好类信息时，就写这一轮的**任务模式**：这个用户会把什么样的
   任务交给 AI（例如「常让 AI 做重复性的打卡操作」）。这同样是有效的记忆。

输出格式：第一行是一句话标题（不超过 15 字），第二行开始是正文。
""".trimIndent()
    }
}

/**
 * 记忆的写入队列。
 *
 * ## 为什么需要排队
 *
 * 写记忆要调一次模型，几秒钟。这期间用户完全可能又发了一条任务，
 * 而那条任务结束时也要写记忆 —— 两个写入同时进行会有两种坏结果：
 * **两条记录交错插进文件**（markdown 结构乱掉），或者**后写的盖掉先写的**。
 *
 * 用一个互斥锁把它们排成一队：谁先到谁先写，后来的等着。
 * 只增不减的追加式文件最怕并发写，这里是唯一的入口。
 */
object MemoryWriter {

    private val lock = Mutex()

    /** 有没有正在排队/写入的 */
    @Volatile
    var busy: Boolean = false
        private set

    /**
     * 排队写一条。
     *
     * @return 写进去的标题；模型判断没什么可记、或调用失败时返回 null
     */
    suspend fun write(context: Context, llm: LlmClient, turns: List<Turn>): String? {
        if (turns.isEmpty()) return null
        busy = true
        return try {
            lock.withLock {
                val entry = LlmDistiller(llm).distill(turns) ?: return@withLock null
                if (MemoryStore.append(context, entry)) entry.title else null
            }
        } finally {
            busy = false
        }
    }
}
