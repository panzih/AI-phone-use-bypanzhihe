package com.aiphone.assistant.memory

import android.content.Context
import com.aiphone.assistant.log.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Turn(
    val role: Role,
    val text: String,
    val at: Long = System.currentTimeMillis(),
) {
    enum class Role { USER, AI, ACTION }
}

class Conversation {
    private val turns = mutableListOf<Turn>()
    @Volatile var lastActivityAt: Long = System.currentTimeMillis()
        private set
    val size: Int get() = synchronized(turns) { turns.size }

    fun add(turn: Turn) { synchronized(turns) { turns.add(turn) }; lastActivityAt = System.currentTimeMillis() }
    fun snapshot(): List<Turn> = synchronized(turns) { turns.toList() }
    fun clear() { synchronized(turns) { turns.clear() }; lastActivityAt = System.currentTimeMillis() }
    fun idleMinutes(): Long = (System.currentTimeMillis() - lastActivityAt) / 60_000L
    fun isIdleBeyond(minutes: Int): Boolean = minutes > 0 && size > 0 && idleMinutes() >= minutes
}

data class Insight(
    val title: String,
    val markdown: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val fileName: String get() = InsightStore.sanitize(title) + ".md"
}

object InsightStore {
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    fun dir(context: Context): File = AppLog.insightDir(context)

    fun save(context: Context, insight: Insight): File? = runCatching {
        val f = File(dir(context), "${stamp.format(Date(insight.createdAt))}_${insight.fileName}")
        f.writeText(buildString {
            appendLine("# ${insight.title}")
            appendLine()
            appendLine("> 生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(insight.createdAt))}")
            appendLine("> 来源：纸盒 · AI 操作手机")
            appendLine()
            appendLine(insight.markdown)
        })
        AppLog.i("已保存用户洞察：${f.name}", "InsightStore")
        f
    }.getOrNull()

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.sortedByDescending { it.name } ?: emptyList()
    fun readAll(context: Context): String =
        list(context).joinToString("\n\n---\n\n") { runCatching { it.readText() }.getOrDefault("") }
    fun deleteAll(context: Context): Int { val files = list(context); files.forEach { it.delete() }; return files.size }

    fun sanitize(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[\\s/\\\\:*?\"<>|\\n\\r\\t]+"), "_").take(32).trim('_')
        return cleaned.ifBlank { "洞察" }
    }
}

interface InsightDistiller {
    suspend fun distill(turns: List<Turn>): Insight?
}

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
