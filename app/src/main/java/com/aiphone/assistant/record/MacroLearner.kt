package com.aiphone.assistant.record

import com.aiphone.assistant.llm.ChatTurn
import com.aiphone.assistant.llm.LlmClient
import com.aiphone.assistant.llm.LlmResult
import com.aiphone.assistant.log.AppLog
import org.json.JSONObject

/**
 * 把一次录制"学"成一个技能。
 *
 * ## 为什么这里需要模型，而不是代码直接转换
 *
 * 机械转换（把录制的每一步原样变成技能）谁都会写，但录下来的东西
 * 通常**不干净**：
 *
 *   - 用户会多点几下、点错一次再点对的
 *   - 中间夹着"犹豫"（来回切了两次应用）
 *   - 没有名字、没有说明，以后 AI 根本不知道这个技能是干什么的
 *
 * 这些正是模型擅长的判断。所以分工是：
 *
 *   模型  起名、写说明、**挑出哪些步骤该保留**
 *   代码  按它给的编号把原始步骤重新拼成技能
 *
 * ## 关键设计：让模型返回"保留第几步"，而不是让它复述步骤
 *
 * 如果让模型把每一步的 viewId、文字原样再写一遍，它一定会抄错 ——
 * 那些字段又长又没规律。所以只让它回一个 `keep` 数组（第几步要保留），
 * 元素信息一律由代码从原始录制里取。模型做判断，代码做搬运。
 */
class MacroLearner(private val llm: LlmClient) {

    /** @param nameHint 用户给的名字，可以空着让模型起 */
    suspend fun learn(recording: Recording, nameHint: String = ""): MacroSkill? {
        if (recording.isEmpty) return null

        val userText = buildString {
            appendLine("下面是用户在手机上手动操作的过程（共 ${recording.steps.size} 步）：")
            appendLine()
            append(recording.toPromptText())
            if (nameHint.isNotBlank()) {
                appendLine()
                appendLine("用户希望这个技能叫：$nameHint")
            }
        }

        val result = llm.chat(
            system = SYSTEM,
            history = listOf(ChatTurn(ChatTurn.USER, userText)),
            imagePng = null,
        )

        val text = when (result) {
            is LlmResult.Fail -> {
                AppLog.w("学习技能失败：${result.message}", "操作记录")
                return fallback(recording, nameHint)
            }
            is LlmResult.Ok -> result.text
        }

        val obj = extractJson(text) ?: run {
            AppLog.w("模型没返回可用的 JSON，退回原样转换", "操作记录")
            return fallback(recording, nameHint)
        }

        val title = obj.optString("name").trim().ifBlank {
            nameHint.ifBlank { "录制的操作" }
        }

        val keep = parseKeep(obj.optJSONArray("keep"), recording.steps.size)
        val kept = keep.map { recording.steps[it] }
        if (kept.isEmpty()) return fallback(recording, nameHint)

        val gap = obj.optInt("gap_ms", MacroSkill.DEFAULT_GAP_MS)
            .coerceIn(MIN_GAP_MS, MAX_GAP_MS)

        return MacroSkill(
            id = MacroStore.sanitizeId(title),
            title = title,
            summaryText = obj.optString("summary").trim().ifBlank {
                "回放录制的操作：$title"
            },
            docText = buildDoc(obj.optString("doc"), kept, gap),
            steps = kept,
            stepGapMs = gap,
        )
    }

    /**
     * keep 的校验。
     *
     * 模型可能给出越界、重复、乱序的编号。全部夹紧并去重排序 ——
     * 宁可保留多一步，也不能因为一个越界数字让整个技能作废。
     */
    private fun parseKeep(arr: org.json.JSONArray?, total: Int): List<Int> {
        if (arr == null || arr.length() == 0) return (0 until total).toList()
        val picked = LinkedHashSet<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v in 1..total) picked.add(v - 1)
        }
        return picked.sorted()
    }

    private fun buildDoc(rawDoc: String, steps: List<RecordedStep>, gapMs: Int): String {
        val body = rawDoc.trim().ifBlank {
            "这是用户手动录制并学会的一段固定操作，按顺序回放即可。"
        }
        return buildString {
            appendLine(body)
            appendLine()
            appendLine("## 包含的步骤")
            steps.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.label()}") }
            appendLine()
            appendLine("## 回放行为")
            appendLine("- 执行时会在当前界面上按控件签名找回同一个元素再点，不需要坐标")
            appendLine("- 每步之间等 ${gapMs}ms；某一步找不到元素就停下并报出是第几步")
            appendLine("- 有密码的步骤不会被记录，回放时会跳过")
        }
    }

    /**
     * 兜底：模型不可用时，原样转成技能。
     *
     * 比"什么也不做"好 —— 用户的录制不会白费，只是名字和说明比较粗糙。
     */
    private fun fallback(recording: Recording, nameHint: String): MacroSkill {
        val title = nameHint.ifBlank {
            "录制的操作 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date())
        }
        return MacroSkill(
            id = MacroStore.sanitizeId(title),
            title = title,
            summaryText = "回放录制的操作：$title",
            docText = buildDoc("", recording.steps, MacroSkill.DEFAULT_GAP_MS),
            steps = recording.steps,
            stepGapMs = MacroSkill.DEFAULT_GAP_MS,
        )
    }

    /** 和 ActionParser 一样的做法：剥围栏，再抠第一个配平的花括号块 */
    private fun extractJson(raw: String): JSONObject? = runCatching {
        val cleaned = raw
            .replace(Regex("```json", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```"), "")
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return JSONObject(cleaned.substring(start, i + 1))
                }
            }
        }
        null
    }.getOrNull()

    private companion object {
        const val MIN_GAP_MS = 300
        const val MAX_GAP_MS = 6000

        val SYSTEM = """
用户在安卓手机上手动演示了一段操作，我们要把它变成一个可重复执行的"技能"。
你的任务：看懂这段操作在干什么，给它起名、写说明、并判断哪些步骤应该保留。

只输出一个 JSON 对象：

{
  "name": "技能名，不超过 12 个字，说得具体一点（例如「微信发朋友圈」）",
  "summary": "一句话说明这个技能做什么（会显示给 AI 看，用来决定要不要调用它）",
  "doc": "给 AI 看的说明：这个技能怎么用、有什么前提（例如「需要先打开微信并登录」）",
  "keep": [1, 2, 3],
  "gap_ms": 1500
}

关于 keep（最重要）：
- 数组里放**要保留的步骤编号**（从 1 开始，按原顺序）
- 用户多点、点错、来回犹豫的步骤**去掉**，只留下完成这件事必需的
- 每一步都要判断：这一步是"做事"还是在"试探"？试探的去掉
- 拿不准就保留 —— 漏掉关键步骤会让技能跑不通

关于 gap_ms：
- 每步之间等多少毫秒。默认 1500，界面加载慢就调大（最多 6000）

注意：
- 标着「密码（已隐藏）」的步骤保留编号即可，内容是空的，那是正常的
- 不要编造录制里没有的步骤
""".trimIndent()
    }
}
