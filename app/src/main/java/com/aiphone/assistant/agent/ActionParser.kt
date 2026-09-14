package com.aiphone.assistant.agent

import com.aiphone.assistant.touch.ScrollDirection
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import org.json.JSONObject

object ActionParser {

    data class Parsed(
        val thought: String?,
        val action: TouchAction?,
        val finished: Boolean,
        val summary: String,
        val raw: String,
        val nextHint: String = "",
        val warning: String? = null,
    )

    private val ALIASES: Map<String, TouchKind> = buildMap {
        TouchKind.entries.forEach { put(it.id, it) }
        put("click", TouchKind.TAP)
        put("press", TouchKind.TAP)
        put("longpress", TouchKind.LONG_PRESS)
        put("long-click", TouchKind.LONG_PRESS)
        put("doubleclick", TouchKind.DOUBLE_TAP)
        put("swipe_up", TouchKind.SCROLL)
        put("swipe_down", TouchKind.SCROLL)
        put("back", TouchKind.KEY_BACK)
        put("home", TouchKind.KEY_HOME)
        put("recents", TouchKind.KEY_RECENTS)
        put("type", TouchKind.INPUT_TEXT)
        put("input", TouchKind.INPUT_TEXT)
        put("launch_app", TouchKind.OPEN_APP)
        put("sleep", TouchKind.WAIT)
    }

    fun parse(raw: String, screenWidth: Int, screenHeight: Int): Parsed {
        val json = extractJson(raw)
            ?: return Parsed(thought = null, action = null, finished = false, summary = "", raw = raw,
                warning = "没能从模型输出里找到 JSON。原文开头：${raw.take(120)}")

        val obj = try { JSONObject(json) } catch (t: Throwable) {
            return Parsed(thought = null, action = null, finished = false, summary = "", raw = raw,
                warning = "JSON 解析失败（${t.message}）。原文：${json.take(120)}")
        }

        val thought = obj.optString("thought").takeIf { it.isNotBlank() }
        val summary = obj.optString("summary").takeIf { it.isNotBlank() }.orEmpty()
        val nextHint = sequenceOf("next_hint", "next", "hint")
            .map { obj.optString(it, "") }.firstOrNull { it.isNotBlank() }.orEmpty()
        val finished = obj.optBoolean("finished", false) ||
            obj.optString("action").equals("finish", true) ||
            obj.optString("action").equals("done", true)

        if (finished) return Parsed(thought, null, true, summary, raw, nextHint)

        val actionName = obj.optString("action").trim().lowercase()
        if (actionName.isBlank())
            return Parsed(thought, null, false, summary, raw, nextHint, warning = "JSON 里没有 action 字段。")

        val kind = ALIASES[actionName]
            ?: return Parsed(thought, null, false, summary, raw, nextHint,
                warning = "动作「$actionName」不在支持列表里，已忽略。")

        val index = obj.optInt("index", 0)
        val x = clamp(obj.optInt("x", 0), screenWidth)
        val y = clamp(obj.optInt("y", 0), screenHeight)
        val x2 = clamp(obj.optInt("x2", 0), screenWidth)
        val y2 = clamp(obj.optInt("y2", 0), screenHeight)
        val duration = obj.optInt("duration_ms", obj.optInt("duration", 0))
        val text = obj.optString("text", obj.optString("content", ""))
        val pkg = obj.optString("package", obj.optString("package_name", ""))
        val direction = ScrollDirection.fromId(obj.optString("direction", "").ifBlank {
            when (actionName) { "swipe_up" -> "up"; "swipe_down" -> "down"; else -> null }
        })

        val problem = validate(kind, index, x, y, text, pkg)
        if (problem != null) return Parsed(thought, null, false, summary, raw, nextHint, warning = problem)

        val action = TouchAction(
            kind = kind, targetIndex = if (index > 0) index else 0,
            x = x, y = y, x2 = x2, y2 = y2, durationMs = duration,
            direction = direction, text = text, packageName = pkg,
        )
        return Parsed(thought, action, false, summary, raw, nextHint)
    }

    private fun validate(kind: TouchKind, index: Int, x: Int, y: Int, text: String, pkg: String): String? = when (kind) {
        TouchKind.TAP, TouchKind.LONG_PRESS, TouchKind.DOUBLE_TAP ->
            if (index <= 0 && (x <= 0 || y <= 0)) "「${kind.label}」既没给 index 也没给有效的 x/y，无法执行。" else null
        TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG ->
            if (x <= 0 || y <= 0) "「${kind.label}」缺少起点坐标 x/y。" else null
        TouchKind.INPUT_TEXT -> if (text.isBlank()) "「输入文字」的 text 是空的。" else null
        TouchKind.OPEN_APP -> if (pkg.isBlank()) "「打开应用」缺少 package 包名。" else null
        else -> null
    }

    private fun clamp(v: Int, max: Int): Int = when { v < 0 -> 0; max > 0 && v > max -> max; else -> v }

    private fun extractJson(raw: String): String? {
        val cleaned = raw.replace(Regex("```json", RegexOption.IGNORE_CASE), "").replace(Regex("```"), "").trim()
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inString = false; var escaped = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            when { escaped -> escaped = false; c == '\\' && inString -> escaped = true; c == '"' -> inString = !inString
                !inString && c == '{' -> depth++; !inString && c == '}' -> { depth--; if (depth == 0) return cleaned.substring(start, i + 1) } }
        }
        return null
    }
}
