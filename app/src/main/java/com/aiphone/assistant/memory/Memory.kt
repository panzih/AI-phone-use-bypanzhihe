package com.aiphone.assistant.memory

import android.content.Context
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    /**
     * 记忆文件的硬上限。
     *
     * 记忆是只增不减的，一直写下去会无限长：注入提示词只取末尾 6000 字，
     * 前面那些永远不会被读到，却一直占磁盘、拖慢每次读取。所以超过上限
     * 就压缩，只保留**最新的**一部分 —— 新的认知通常最有用。
     */
    const val MAX_CHARS = 60_000

    /** 最多留多少条 */
    const val MAX_ENTRIES = 300

    /** 压缩后保留的字符数；留出余量，避免每写一条都压缩 */
    private const val KEEP_CHARS = 40_000

    /** 压缩后最多保留的条数 */
    private const val KEEP_ENTRIES = 200

    /** 单条正文上限，防止模型一次写进来几千字把文件撑爆 */
    private const val MAX_ENTRY_CHARS = 2_000

    /** 文件头。压缩重写时用它，避免把用户手工改过的说明也当成记忆保留 */
    private const val HEADER = """# 记忆

> 纸盒在每次任务结束后自动沉淀的「用户洞察」，只增不减。
> AI 在新开上下文时会读取这里的内容，也可以随时调 recall_memory 技能查看。

"""

    /** 写记忆的结果。去重和失败要分开，界面才能说清楚为什么没写 */
    sealed class AppendResult {
        object Written : AppendResult()
        object Duplicate : AppendResult()
        data class Failed(val reason: String) : AppendResult()
    }

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
     * 每次都写到文件末尾，之前的内容一个字节都不会被碰；写完后如果
     * 超过 [MAX_CHARS] / [MAX_ENTRIES] 就压缩（只留最新的）。
     *
     * 去重按**正文规范化后完全相同**判断：模型对同一件事反复归纳时，
     * 文件不该无限堆同一条。标题不同但正文一样也算重复。
     */
    fun append(context: Context, entry: MemoryEntry): AppendResult = runCatching {
        val body = entry.body.trim().take(MAX_ENTRY_CHARS)
        if (body.isBlank()) return@runCatching AppendResult.Failed("记忆正文为空")

        val f = file(context)
        val existing = read(context)
        if (isDuplicate(existing, body)) {
            AppLog.i("记忆重复，已跳过：${entry.title}", "记忆")
            return@runCatching AppendResult.Duplicate
        }

        if (existing.isBlank()) f.writeText(HEADER)
        f.appendText(formatEntry(entry.copy(body = body)))
        AppLog.i("记忆 +1：${entry.title}", "记忆")

        val text = f.readText()
        if (text.length > MAX_CHARS || entryCount(text) > MAX_ENTRIES) {
            compact(context, text)
        }
        AppendResult.Written
    }.getOrElse {
        AppLog.w("写记忆失败：${it.message}", "记忆")
        AppendResult.Failed(it.message ?: "写文件失败")
    }

    /**
     * 手动压缩：只保留最新的 [KEEP_CHARS] / [KEEP_ENTRIES]。
     *
     * @return true = 确实压小了；false = 没东西可压（空文件/已经很小）
     */
    fun compress(context: Context): Boolean = runCatching {
        val text = read(context)
        if (text.isBlank()) return@runCatching false
        val beforeChars = text.length
        val beforeCount = entryCount(text)
        compact(context, text)
        val after = read(context)
        after.length < beforeChars || entryCount(after) < beforeCount
    }.getOrElse {
        AppLog.w("压缩记忆失败：${it.message}", "记忆")
        false
    }

    /** 清空记忆。只影响之后新开的上下文 —— 当前上下文里的快照不会被动。 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
        AppLog.i("记忆已清空", "记忆")
    }

    private fun formatEntry(entry: MemoryEntry): String = buildString {
        appendLine()
        appendLine("## ${stamp.format(Date(entry.at))} · ${entry.title}")
        appendLine()
        appendLine(entry.body.trim())
        appendLine()
    }

    private fun entryCount(text: String): Int =
        Regex("(?m)^## ").findAll(text).count()

    /** 按 `## ` 标题切出每一条记忆（含标题行） */
    private fun entries(text: String): List<String> {
        val starts = Regex("(?m)^## ").findAll(text).map { it.range.first }.toList()
        return starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: text.length
            text.substring(start, end).trimEnd()
        }
    }

    private fun entryBody(block: String): String =
        block.lineSequence().drop(1).joinToString("\n").trim()

    private fun normalize(s: String): String =
        s.replace(Regex("\\s+"), " ").trim()

    private fun isDuplicate(existing: String, body: String): Boolean {
        val target = normalize(body)
        if (target.isBlank()) return true
        return entries(existing).any { normalize(entryBody(it)) == target }
    }

    /** 只保留最新的若干条，重写成完整文件（原子替换，避免压缩途中被杀留半截） */
    private fun compact(context: Context, text: String) {
        val all = entries(text)
        var chars = 0
        val kept = ArrayDeque<String>()
        for (e in all.asReversed()) {
            if (kept.isNotEmpty() &&
                (chars + e.length > KEEP_CHARS || kept.size >= KEEP_ENTRIES)
            ) break
            kept.addFirst(e)
            chars += e.length
        }
        writeAtomically(
            file(context),
            HEADER + kept.joinToString("\n\n") + "\n",
        )
        AppLog.i("记忆已压缩：${all.size} 条 → ${kept.size} 条 / $chars 字符", "记忆")
    }

    private fun writeAtomically(main: File, text: String) {
        main.parentFile?.mkdirs()
        val tmp = File(main.parentFile, "${main.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                main.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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
 * 归纳的结果。
 *
 * 分成"拿到条目"和"放弃并给出原因"两种，而不是用 `MemoryEntry?` ——
 * 因为**放弃的原因必须能传到界面上**。原来一律返回 null，
 * 用户只看到一句"没写入"，排查时完全无从下手。
 */
sealed class DistillResult {
    /**
     * @param note 需要额外说明时用（例如"模型回了「无」，已兜底"）。
     *             为空表示这是一次正常归纳
     */
    data class Entry(val entry: MemoryEntry, val note: String = "") : DistillResult()

    /** @param reason 可以直接给用户看的一句话 */
    data class GaveUp(val reason: String) : DistillResult()
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

    suspend fun distill(turns: List<Turn>): DistillResult {
        if (turns.isEmpty()) return DistillResult.GaveUp("没有可归纳的轮次")

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
            is LlmResult.Fail -> return DistillResult.GaveUp("模型调用失败：${result.message}")
            is LlmResult.Ok -> result.text.trim()
        }

        // 模型偶尔会无视"必须写"的指令，回一个「无」或者干脆空。
        // **不允许因此不写记忆**（这是用户明确要求的），所以这里兜一条：
        // 用任务原文顶上。宁可记一条朴素的事实，也不要出现"这一轮什么都没留下"。
        //
        // 2026-09-17 修：这里原先是 `AppLog.w(...)` —— 而调用方是任务结束之后才
        // 走的，那时任务级 logger 已经 close，写它等于写进黑洞。现在是应用级
        // AppLog + 把原因一路带回界面。
        if (text.isBlank() || text == "无" || text.startsWith("无\n")) {
            val fallback = turns.firstOrNull { it.role == Turn.Role.USER }?.text.orEmpty().trim()
            if (fallback.isBlank()) {
                return DistillResult.GaveUp("模型没给内容，且找不到可兜底的任务原文")
            }
            return DistillResult.Entry(
                MemoryEntry(
                    title = fallback.take(20),
                    body = "（模型未给出归纳，自动记录的任务原文）\n\n$fallback",
                ),
                note = "模型回了「无」，已用任务原文兜底",
            )
        }

        val lines = text.lines()
        val title = lines.firstOrNull { it.isNotBlank() }
            ?.trimStart('#', ' ', '-')
            ?.take(24)
            ?.ifBlank { null }
            ?: "用户洞察"
        val body = lines.drop(1).joinToString("\n").trim().ifBlank { text }

        return DistillResult.Entry(MemoryEntry(title = title, body = body))
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

    /**
     * 一次写入的结果。
     *
     * 用类型而不是 `String?`，是因为**"为什么没写进去"必须能说清楚**。
     * 原来失败一律返回 null，调用方只能显示一句"没写入"，
     * 用户和排查的人都无从下手 —— 这次"记忆写入失效"就是这么被藏住的。
     */
    sealed class Outcome {
        /** 成功写入，[title] 是那条记忆的标题 */
        data class Written(val title: String) : Outcome()

        /** 没写，[reason] 是可以直接给用户看的一句话原因 */
        data class NotWritten(val reason: String) : Outcome()
    }

    private val lock = Mutex()

    /** 有没有正在排队/写入的 */
    @Volatile
    var busy: Boolean = false
        private set

    /**
     * 排队写一条。
     *
     * **调用方必须在 IO 线程**：里面会做同步阻塞的 HTTP 调用
     * （见 [LlmDistiller.distill]）。
     */
    suspend fun write(context: Context, llm: LlmClient, turns: List<Turn>): Outcome {
        if (turns.isEmpty()) return Outcome.NotWritten("没有可归纳的轮次")
        busy = true
        return try {
            lock.withLock {
                when (val r = LlmDistiller(llm).distill(turns)) {
                    is DistillResult.Entry -> when (val a = MemoryStore.append(context, r.entry)) {
                        MemoryStore.AppendResult.Written -> Outcome.Written(r.entry.title)
                        MemoryStore.AppendResult.Duplicate ->
                            Outcome.NotWritten("和已有记忆重复，已跳过")
                        is MemoryStore.AppendResult.Failed ->
                            Outcome.NotWritten(a.reason)
                    }
                    is DistillResult.GaveUp -> Outcome.NotWritten(r.reason)
                }
            }
        } finally {
            busy = false
        }
    }
}
