package com.aiphone.assistant.agent

import com.aiphone.assistant.touch.ScrollDirection
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import org.json.JSONObject

/**
 * 解析模型输出。
 *
 * ## 为什么这一层必须存在
 *
 * 就算提示词里写"只输出 JSON"，模型实际会给你这些东西：
 *
 *   ```json
 *   {"action":"tap", ...}
 *   ```
 *
 *   好的，我来帮你操作手机：
 *   {"action":"tap", ...}
 *
 *   {
 *     "thought": "...",
 *     "action": "shell",        ← 编出来的动作名
 *     ...
 *   }
 *
 *   {"action": "tap", "index": 3,   ← 截断了
 *
 * 所以流程是：剥围栏 → 提取第一个配平的花括号块 → 解析 →
 * 校验动作名在白名单里 → 校验并夹紧坐标。
 * **任何一步失败都不抛异常**，而是返回一个带 warning 的结果，
 * 由 Agent 决定是重试还是跳过。
 */
object ActionParser {

    /**
     * 解析结果。
     *
     * [action] 为 null 且 [finished] 为 false 时，说明这一轮没解析出可用动作，
     * 看 [warning] 里的原因。
     */
    data class Parsed(
        val thought: String?,
        val action: TouchAction?,
        val finished: Boolean,
        val summary: String,
        val raw: String,
        val warning: String? = null,
        /** 界面变了没有 —— 由 Agent 用截图哈希判断后回填，解析层不关心 */
    )

    /** 动作名白名单。不在这张表里的一律拒绝，绝不去执行。 */
    private val ALIASES: Map<String, TouchKind> = buildMap {
        TouchKind.entries.forEach { put(it.id, it) }
        // 模型很爱用这些同义词，多认几个能省掉不少无谓的重试
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

    fun parse(
        raw: String,
        screenWidth: Int,
        screenHeight: Int,
    ): Parsed {
        val json = extractJson(raw)
            ?: return Parsed(
                thought = null,
                action = null,
                finished = false,
                summary = "",
                raw = raw,
                warning = "没能从模型输出里找到 JSON。原文开头：${raw.take(120)}",
            )

        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return Parsed(
                thought = null,
                action = null,
                finished = false,
                summary = "",
                raw = raw,
                warning = "JSON 解析失败（${t.message}）。原文：${json.take(120)}",
            )
        }

        val thought = obj.optString("thought").takeIf { it.isNotBlank() }
        val summary = obj.optString("summary").takeIf { it.isNotBlank() }.orEmpty()
        val finished = obj.optBoolean("finished", false) ||
            obj.optString("action").equals("finish", true) ||
            obj.optString("action").equals("done", true)

        if (finished) {
            return Parsed(thought, null, true, summary, raw)
        }

        val actionName = obj.optString("action").trim().lowercase()
        if (actionName.isBlank()) {
            return Parsed(
                thought, null, false, summary, raw,
                warning = "JSON 里没有 action 字段。",
            )
        }

        val kind = ALIASES[actionName]
            ?: return Parsed(
                thought, null, false, summary, raw,
                warning = "动作「$actionName」不在支持列表里，已忽略。" +
                    "可用动作：${TouchKind.entries.joinToString("/") { it.id }}",
            )

        // ---- 参数 ----
        val index = obj.optInt("index", 0)
        val x = clamp(obj.optInt("x", 0), screenWidth)
        val y = clamp(obj.optInt("y", 0), screenHeight)
        val x2 = clamp(obj.optInt("x2", 0), screenWidth)
        val y2 = clamp(obj.optInt("y2", 0), screenHeight)
        val duration = obj.optInt("duration_ms", obj.optInt("duration", 0))
        val text = obj.optString("text", obj.optString("content", ""))
        val pkg = obj.optString("package", obj.optString("package_name", ""))

        // 方向：模型可能写在 direction 里，也可能写在 action 名里（swipe_up）
        val direction = ScrollDirection.fromId(obj.optString("direction", "").ifBlank {
            when (actionName) {
                "swipe_up" -> "up"
                "swipe_down" -> "down"
                else -> null
            }
        })

        // ---- 校验：这个动作能不能执行 ----
        val problem = validate(kind, index, x, y, text, pkg)
        if (problem != null) {
            return Parsed(thought, null, false, summary, raw, warning = problem)
        }

        val action = TouchAction(
            kind = kind,
            targetIndex = if (index > 0) index else 0,
            x = x, y = y, x2 = x2, y2 = y2,
            durationMs = duration,
            direction = direction,
            text = text,
            packageName = pkg,
        )
        return Parsed(thought, action, false, summary, raw)
    }

    /**
     * 这个动作缺不缺必需参数。
     *
     * 缺参数时**不能**硬执行 —— 比如 tap 没有 index 也没有坐标，
     * 执行下去就是点 (0,0)，会误触左上角。宁可让模型重出一遍。
     */
    private fun validate(
        kind: TouchKind,
        index: Int,
        x: Int,
        y: Int,
        text: String,
        pkg: String,
    ): String? = when (kind) {
        TouchKind.TAP, TouchKind.LONG_PRESS, TouchKind.DOUBLE_TAP ->
            if (index <= 0 && (x <= 0 || y <= 0)) {
                "「${kind.label}」既没给 index 也没给有效的 x/y，无法执行。"
            } else null

        TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG ->
            if (x <= 0 || y <= 0) {
                "「${kind.label}」缺少起点坐标 x/y。"
            } else null

        TouchKind.INPUT_TEXT ->
            if (text.isBlank()) "「输入文字」的 text 是空的。" else null

        TouchKind.OPEN_APP ->
            if (pkg.isBlank()) "「打开应用」缺少 package 包名。" else null

        else -> null
    }

    private fun clamp(v: Int, max: Int): Int = when {
        v < 0 -> 0
        max > 0 && v > max -> max
        else -> v
    }

    /**
     * 从一堆乱七八糟的文本里把第一个配平的 JSON 对象抠出来。
     *
     * 不能简单用 indexOf('{') + lastIndexOf('}') —— 模型后面可能
     * 还跟了一段解释文字，里面有花括号；也不能不管字符串里的花括号，
     * 比如 {"text":"a}b"}。
     */
    private fun extractJson(raw: String): String? {
        // 先剥 markdown 围栏
        val cleaned = raw
            .replace(Regex("```json", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```"), "")
            .trim()

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
                    if (depth == 0) return cleaned.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
