package com.aiphone.assistant.skill

import android.util.Log
import org.json.JSONObject

/**
 * 技能注册表。
 *
 * 三件事：给模型看目录（[catalog]）、按需给完整说明（[doc]）、执行（[run]）。
 *
 * ## 为什么要分"目录"和"说明文档"
 *
 * 完整文档每个都有几百字，全塞进系统提示词是白花钱 —— 大多数任务
 * 一个技能都不需要。所以：
 *
 *   - **目录**（每个技能一行）常驻系统提示词，模型据此知道有什么可用
 *   - **完整说明**由模型用 `use_skill: "list_skills"` 主动拉，只拉一次
 *
 * 和截图那套是同一个思路：便宜的信息常驻，贵的信息按需。
 *
 * ## 新增技能
 *
 * 写一个 [Skill] 实现，加进 [SKILLS]。目录和文档会自动带上，
 * 系统提示词那句也是从注册表生成的，不用去改提示词代码。
 */
class SkillRegistry(
    private val ctx: SkillContext,
    /** 内置技能 */
    private val skills: List<Skill> = DEFAULT_SKILLS,
    /**
     * 运行时加载的技能（比如「操作记录」学来的宏）。
     *
     * 和内置技能分开传：内置的是代码里写死的，这些是从文件读的，
     * 用户随时可能新增/删除。
     */
    private val extra: List<Skill> = emptyList(),
) {

    /** 内置 + 动态 */
    private val all: List<Skill> get() = skills + extra

    /** 模型可以用来"查文档"的特殊 id */
    private val docRequestIds = setOf("list_skills", "skills", "help", "docs")

    /**
     * 技能目录：一行一个，进系统提示词。
     *
     * 没有注册任何技能时返回空串，提示词那边会整段省略。
     */
    fun catalog(): String =
        if (all.isEmpty()) "" else all.joinToString("\n") { "- ${it.id} —— ${it.summary}" }

    /** 已注册技能的 id，给日志和报错用 */
    fun ids(): List<String> = all.map { it.id }

    /** 完整说明文档；[id] 不认识时返回 null */
    fun doc(id: String): String? {
        val key = id.trim().lowercase()
        if (key in docRequestIds) return fullDoc()
        return all.firstOrNull { it.id == key }?.doc
    }

    /**
     * 执行一个技能。
     *
     * **不抛异常**：任何失败都变成一段给模型看的中文说明，
     * 由它决定换一种做法还是就此打住。
     */
    suspend fun run(id: String, args: JSONObject?): SkillOutcome {
        val key = id.trim().lowercase()

        // "查文档"是注册表自己处理的，不走技能实现 ——
        // 否则每个技能都得想办法访问 fellow 的文档，绕成环
        if (key in docRequestIds) {
            return SkillOutcome(ok = true, text = fullDoc())
        }

        val skill = all.firstOrNull { it.id == key }
            ?: return SkillOutcome(
                ok = false,
                text = "没有叫「$id」的技能。可用的技能：" +
                    all.joinToString("、") { it.id } +
                    "。想看某个技能的完整说明，用 use_skill: \"list_skills\"。",
            )

        return try {
            val text = skill.run(ctx, args)
            Log.i(TAG, "技能 $key 执行完成，返回 ${text.length} 字符")
            SkillOutcome(ok = true, text = text)
        } catch (t: Throwable) {
            Log.w(TAG, "技能 $key 执行失败：${t.message}")
            SkillOutcome(
                ok = false,
                text = "技能「$key」执行失败：${t.javaClass.simpleName} ${t.message}",
            )
        }
    }

    /** 目录 + 每个技能的完整说明 */
    private fun fullDoc(): String = buildString {
        appendLine("当前可用的技能（共 ${all.size} 个）：")
        appendLine()
        if (all.isEmpty()) {
            appendLine("（还没有注册任何技能）")
        } else {
            for (s in all) {
                appendLine(s.doc)
                appendLine()
            }
        }
        appendLine("调用方式：在 JSON 里写 \"use_skill\": \"<技能id>\"，")
        appendLine("需要参数的技能再加 \"skill_args\": {...}。")
        appendLine("系统会把技能返回的内容发回给你，这一轮不算一步。")
    }
}

private const val TAG = "SkillRegistry"

/**
 * 已注册的技能。
 *
 * 以后加记忆、用户洞察、剪贴板之类的，都往这里加一行 ——
 * 目录和系统提示词会自动带上。
 */
private val DEFAULT_SKILLS: List<Skill> = listOf(
    AppListSkill,
    MemorySkill,
)
