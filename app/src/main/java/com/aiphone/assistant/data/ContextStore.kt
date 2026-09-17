package com.aiphone.assistant.data

import android.content.Context
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
)

object ContextStore {

    private const val FILE_NAME = "conversation.json"

    /**
     * 最多留多少条消息。
     *
     * 一次任务能产生几十条，"不限"档下跑几天就会堆到几千条 ——
     * 界面渲染吃不消，文件也没必要无限大。超了就丢最早的。
     */
    private const val MAX_ENTRIES = 300

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
        )
    }.getOrNull()

    fun save(context: Context, entries: List<LogEntry>, turns: List<Turn>, lastActivityAt: Long) {
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
            }
            file(context).writeText(o.toString())
        }.onFailure { AppLog.w("保存对话失败：${it.message}", "对话") }
    }

    /** 只在上下文真的被清掉时调用 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
