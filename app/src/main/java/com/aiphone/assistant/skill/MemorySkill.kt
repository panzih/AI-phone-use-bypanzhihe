package com.aiphone.assistant.skill

import com.aiphone.assistant.memory.InsightStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 技能：取回之前存下来的记忆。
 *
 * ## 为什么记忆要做成"技能"而不是预置进提示词
 *
 * 记忆是**越长越多**的。全塞进系统提示词有两个问题：
 *
 *   1. 每一轮都要重发，token 一直涨（而且大部分跟当前任务无关）
 *   2. 它会破坏系统提示词的静态性 —— 刚存完一条，前缀就变了，
 *      上下文缓存全部失效
 *
 * 做成技能之后，模型在**需要的时候**要一次：任务涉及"我平时怎么做的"
 * 才会去取，取回来的内容进历史，之后靠缓存复用。
 *
 * ## 要不要做成"按相关性检索"
 *
 * 现在没有向量检索，只有关键词粗筛（[run] 里的 query）。够用的原因：
 * 记忆条数天然很少（一次任务最多沉淀一条，而且很多次会返回"无可记"），
 * 真实场景下通常就几十条以内。真多到几百条再考虑换检索方式。
 */
object MemorySkill : Skill {

    /** 最多返回几份，从最新的往回取 */
    private const val MAX_INSIGHTS = 5

    /** 每份最多给多少字符，超了截断 */
    private const val MAX_CHARS_PER_INSIGHT = 1500

    override val id: String = "recall_memory"

    override val summary: String =
        "取回之前存下来的记忆（用户的偏好、常用应用、这台机器上踩过的坑）。" +
            "任务需要「按我平时的习惯来」时用"

    override val doc: String = """
# 技能：recall_memory —— 取回记忆

## 用途
拿到之前任务里沉淀下来的「用户洞察」。这些是**跨任务成立**的东西：
用户的偏好和习惯、常用应用、这台设备上哪些页面有坑、用户对任务的固定要求。

**什么时候该用：**
- 用户说"照我平时的习惯""还用上次那个"这类话
- 你不确定用户的偏好（比如用哪个应用、要不要确认）
- 任务开始前想先看看有没有相关经验

**什么时候不该用：**
- 已经取过一次了 —— 结果就在上下文里，不要重复取
- 任务跟用户偏好完全无关（比如"打开设置"）

## 参数
| 参数 | 必填 | 说明 |
|---|---|---|
| query | 否 | 关键词，用来挑相关的记忆。不给就返回最近几条 |

## 返回
按时间倒序的若干份记忆（正文是 markdown）。一份都没有时明确说明"还没有记忆"。

## 示例
{"use_skill": "recall_memory"}
{"use_skill": "recall_memory", "skill_args": {"query": "微信"}}

## 注意
- 记忆是**过去**的认知，可能过时。跟当前界面冲突时以当前界面为准。
- 里面可能有用户自己写的规则，优先遵守。
""".trimIndent()

    override suspend fun run(ctx: SkillContext, args: JSONObject?): String =
        withContext(Dispatchers.IO) {
            val files = InsightStore.list(ctx.context)
            if (files.isEmpty()) {
                return@withContext "还没有存下任何记忆。用户可能刚装好这个应用，" +
                    "或者「开启记忆」是关着的。"
            }

            val query = args?.optString("query", "")?.trim().orEmpty()

            // 读了才能筛，所以这里先把内容读出来（记忆条数很少，成本可忽略）
            val loaded = files.take(40).mapNotNull { f ->
                val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
                f.name to text
            }

            val picked = if (query.isBlank()) {
                loaded
            } else {
                val keywords = query.split(Regex("[\\s,，、]+")).filter { it.length >= 2 }
                val hit = loaded.filter { (_, text) ->
                    keywords.any { k -> text.contains(k, ignoreCase = true) }
                }
                // 关键词一条都没命中就退回最近几条 —— 什么都不给比给不相关的更糟
                hit.ifEmpty { loaded }
            }.take(MAX_INSIGHTS)

            buildString {
                appendLine(
                    "共 ${files.size} 份记忆，下面是" +
                        (if (query.isBlank()) "最近的" else "和「$query」相关的") +
                        " ${picked.size} 份（新的在前）："
                )
                picked.forEach { (name, text) ->
                    appendLine()
                    appendLine("──────── $name ────────")
                    if (text.length > MAX_CHARS_PER_INSIGHT) {
                        appendLine(text.take(MAX_CHARS_PER_INSIGHT))
                        appendLine("……（这份共 ${text.length} 字符，已截断）")
                    } else {
                        appendLine(text)
                    }
                }
                appendLine()
                append("记忆是过去的认知，可能已经过时；和当前界面对不上时以当前界面为准。")
            }
        }
}
