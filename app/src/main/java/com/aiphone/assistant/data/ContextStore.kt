package com.aiphone.assistant.data

import android.content.Context
import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.memory.Turn
import com.aiphone.assistant.ui.LogEntry
import com.aiphone.assistant.ui.LogKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 对话的持久化。
 *
 * ## 解决什么问题
 *
 * 主界面中间那块"对话"原来只活在内存里 —— 应用被系统回收、或者用户
 * 划掉重开，看起来就像"上下文被清空了"，哪怕设置里选的是「不限」。
 * 用户看到的和实际发生的对不上，这是最糟的一种体验。
 *
 * 所以把两样都存下来：
 *
 *   entries         用户看到的那串消息（思考 / 动作 / 结果 / 错误）
 *   turns           给记忆归纳用的轮次
 *   lastActivityAt  判断"闲置多久了"的依据 —— 光有内容没时间，
 *                   重启之后就判不出 24 小时这一档过期没有
 *
 * ## 什么时候清
 *
 * **只在上下文真的被清掉时清**（策略判定过期、或用户手动清空）。
 * 清掉之后会补一条系统消息说明原因，不然用户只会看到消息"凭空消失"。
 */
data class SavedContext(
    val entries: List<LogEntry>,
    val turns: List<Turn>,
    val lastActivityAt: Long,
    /**
     * **模型那一侧的历史**（[ChatTurn] 列表）。
     *
     * 和 [entries] 的区别是这份是发给模型的：里面是控件树、动作结果、模型原话。
     * 不带它的话，用户看到的对话还在、AI 却当每次任务都是全新的 ——
     * 这正是"上下文没生效"的根因。
     */
    val history: List<ChatTurn> = emptyList(),
    /** 上一次请求的指纹链，跨任务维持"前缀复用"自查。见 [CarriedContext] */
    val fingerprints: List<Int> = emptyList(),
    /**
     * 这段上下文**开头的系统提示词**里注入的那份记忆。
     *
     * 必须原样留住、每次都注入同一份 —— 系统提示词是前缀的第 0 个 token，
     * 它一变，整段上下文的缓存就全废。所以哪怕后来记忆文件又长了，
     * 这段上下文里也只能继续用当初那一份（想用新的就清空上下文重开）。
     */
    val memorySnapshot: String = "",

    /**
     * 建这段上下文时的「提示词协议版本 / 模型名 / 技能目录」快照。
     * 三者都进系统提示词、是缓存前缀的一部分；载入时任一对不上，
     * 调用方按「新开上下文」处理（见 MainActivity）。
     */
    val promptVersion: String = "",
    val modelName: String = "",
    val skillCatalog: String = "",
)

/**
 * 从上一段上下文里带过来的东西。
 *
 * 两个字段是配套的：
 *   [history]   —— 发给模型的历史（见 [SavedContext.history]）
 *   [fingerprints] —— 上一次请求的指纹链，用来判断前缀有没有被我们自己改动
 *
 * 两者分开存是因为指纹比原文小几个数量级；而"没有指纹"就等于
 * 下一次请求无从比对，前缀复用率那行日志永远是 0/0。
 */
data class CarriedContext(
    val history: List<ChatTurn> = emptyList(),
    val fingerprints: List<Int> = emptyList(),
    val memorySnapshot: String = "",
) {
    val isEmpty: Boolean get() = history.isEmpty()
}

object ContextStore {

    private const val FILE_NAME = "conversation.json"

    /**
     * 最多留多少条消息。
     *
     * 一次任务能产生几十条，"不限"档下跑几天就会堆到几千条 ——
     * 界面渲染吃不消，文件也没必要无限大。超了就丢最早的。
     */
    private const val MAX_ENTRIES = 300

    /**
     * 模型历史最多留多少个字符。
     *
     * 这些字符每次请求都要重发 —— 但**命中缓存的部分只按 $0.003/百万**
     * 计价（见 DeepSeek 的上下文硬盘缓存），所以留着比丢掉更划算：
     * 丢掉会让前缀变化，反而要按未命中价重算。上限的存在只是为了
     * 别把 1M 的窗口顶满。
     */
    private const val MAX_HISTORY_CHARS = 400_000

    private fun file(context: Context): File =
        File(AppLog.rootDir(context), FILE_NAME)

    fun load(context: Context): SavedContext? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())

        val entries = o.optJSONArray("entries")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { e ->
                    val kind = runCatching {
                        LogKind.valueOf(e.optString("kind", LogKind.THOUGHT.name))
                    }.getOrDefault(LogKind.THOUGHT)
                    val text = e.optString("text")
                    if (text.isBlank()) null
                    else LogEntry(
                        id = e.optString("id", "r$i"),
                        kind = kind,
                        text = text,
                        label = e.optString("label").takeIf { it.isNotBlank() },
                    )
                }
            }
        } ?: emptyList()

        val turns = o.optJSONArray("turns")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { t ->
                    val text = t.optString("text")
                    if (text.isBlank()) null
                    else Turn(
                        role = runCatching {
                            Turn.Role.valueOf(t.optString("role", Turn.Role.USER.name))
                        }.getOrDefault(Turn.Role.USER),
                        text = text,
                        at = t.optLong("at", System.currentTimeMillis()),
                    )
                }
            }
        } ?: emptyList()

        SavedContext(
            entries = entries,
            turns = turns,
            lastActivityAt = o.optLong("lastActivityAt", 0L),
            history = o.optJSONArray("history")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { h ->
                        val text = h.optString("text")
                        val role = h.optString("role")
                        if (text.isBlank() || role.isBlank()) null
                        else ChatTurn(role = role, text = text)
                    }
                }
            } ?: emptyList(),
            fingerprints = o.optJSONArray("fingerprints")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it) }
            } ?: emptyList(),
            memorySnapshot = o.optString("memorySnapshot", ""),
            promptVersion = o.optString("promptVersion", ""),
            modelName = o.optString("modelName", ""),
            skillCatalog = o.optString("skillCatalog", ""),
        )
    }.getOrNull()

    fun save(
        context: Context,
        entries: List<LogEntry>,
        turns: List<Turn>,
        lastActivityAt: Long,
        history: List<ChatTurn> = emptyList(),
        fingerprints: List<Int> = emptyList(),
        memorySnapshot: String = "",
        promptVersion: String = "",
        modelName: String = "",
        skillCatalog: String = "",
    ) {
        runCatching {
            val kept = if (entries.size > MAX_ENTRIES) entries.takeLast(MAX_ENTRIES) else entries
            val o = JSONObject().apply {
                put("lastActivityAt", lastActivityAt)
                put("entries", JSONArray().apply {
                    kept.forEach { e ->
                        put(
                            JSONObject().apply {
                                put("id", e.id)
                                put("kind", e.kind.name)
                                put("text", e.text)
                                put("label", e.label ?: "")
                            }
                        )
                    }
                })
                put("turns", JSONArray().apply {
                    turns.takeLast(MAX_ENTRIES).forEach { t ->
                        put(
                            JSONObject().apply {
                                put("role", t.role.name)
                                put("text", t.text)
                                put("at", t.at)
                            }
                        )
                    }
                })
                put("history", JSONArray().apply {
                    trimHistory(history).forEach { h ->
                        put(
                            JSONObject().apply {
                                put("role", h.role)
                                put("text", h.text)
                            }
                        )
                    }
                })
                put("fingerprints", JSONArray().apply {
                    fingerprints.forEach { put(it) }
                })
                put("memorySnapshot", memorySnapshot)
                put("promptVersion", promptVersion)
                put("modelName", modelName)
                put("skillCatalog", skillCatalog)
            }
            file(context).writeText(o.toString())
        }.onFailure { AppLog.w("保存对话失败：${it.message}", "对话") }
    }

    /**
     * 历史留太长会把请求撑爆。按**字符数**兜底，从最早的消息开始成对丢。
     *
     * 为什么按字符而不按条数：控件树一条就能有几千字，条数完全不代表体积。
     *
     * 为什么可以丢最早的：丢一次会让**下一次**请求的前缀缓存落空一次
     * （前缀变了），之后就又是稳定前缀了。相比把上下文顶爆导致任务直接
     * 停下，这一次未命中是划算的。
     */
    private fun trimHistory(history: List<ChatTurn>): List<ChatTurn> {
        var total = 0
        val out = ArrayDeque<ChatTurn>()
        for (h in history.asReversed()) {
            if (total + h.text.length > MAX_HISTORY_CHARS && out.isNotEmpty()) break
            out.addFirst(h)
            total += h.text.length
        }
        return out.toList()
    }

    /** 只在上下文真的被清掉时调用 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
