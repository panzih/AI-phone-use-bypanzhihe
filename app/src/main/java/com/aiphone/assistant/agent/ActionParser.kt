package com.aiphone.assistant.agent

import com.aiphone.assistant.touch.ScrollDirection
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析模型输出。
 *
 * ## 为什么这一层必须存在
 *
 * 就算提示词里写"只输出 JSON"，模型实际会给你这些东西：
 *
 *   ```json
 *   {"actions":[{"action":"tap","index":3}]}
 *   ```
 *
 *   好的，我来帮你操作手机：
 *   {"actions":[...]}
 *
 *   {"action": "shell", ...}                  ← 编出来的动作名
 *
 *   {"actions":[{"action":"tap","index":3}    ← 截断了
 *
 * 所以流程是：剥围栏 → 提取第一个配平的花括号块 → 解析 →
 * 逐条校验动作名 / 参数 → 夹紧坐标和时长。
 * **任何一步失败都不抛异常**，而是返回带 warning 的结果，
 * 由 Agent 决定是重试还是跳过。
 *
 * ## 这一版新增的两件事
 *
 * **1. `actions` 是数组。** 一轮可以给一批动作，按顺序执行。
 * 内层每一条单独校验 —— **坏的那条丢掉，好的照常执行**，
 * 而不是因为一条写错就整轮作废。丢掉的会在 warning 里说明并回灌给模型。
 *
 * **2. `need_image`。** 模型可以只要一张截图而不给动作。
 * 这不算"没解析出动作"，Agent 会截图后重新问它一次。
 */
object ActionParser {

    /** 一轮最多执行多少个动作，防止模型给一条超长脚本 */
    const val MAX_ACTIONS = 12

    /** sleep 没写时长时用的默认值 */
    const val DEFAULT_SLEEP_MS = 1500

    /** 单次等待的上限，防止模型写出一个"等一小时"的手滑值 */
    const val MAX_SLEEP_MS = 60_000

    /**
     * 解析结果。
     *
     * [actions] 为空且 [finished] 和 [needImage] 都是 false 时，
     * 说明这一轮没解析出可用动作，看 [warning] 里的原因。
     */
    data class Parsed(
        val thought: String?,
        /** 按顺序执行的动作列表。sleep 也是其中一个元素（kind = WAIT） */
        val actions: List<TouchAction>,
        /** 模型要求看截图 */
        val needImage: Boolean,
        val finished: Boolean,
        val summary: String,
        val raw: String,
        /**
         * 模型对**下一步**的预告，显示给用户看的那一行。
         *
         * 这是"人在环路"的关键：用户在你动手之前就知道你要干嘛，
         * 觉得不对就能按急停 —— 而不是事后才发现点错了。
         */
        val nextHint: String = "",
        /** 非致命的问题说明（某些动作被丢掉了之类），会回灌给模型 */
        val warning: String? = null,
    )

    /** 动作名白名单。不在这张表里的一律拒绝，绝不去执行。 */
    private val ALIASES: Map<String, TouchKind> = buildMap {
        TouchKind.entries.forEach { put(it.id, it) }
        // 模型很爱用这些同义词，多认几个能省掉不少无谓的重试
        put("click", TouchKind.TAP)
        put("press", TouchKind.TAP)
        put("longpress", TouchKind.LONG_PRESS)
        put("long-click", TouchKind.LONG_PRESS)
        put("longclick", TouchKind.LONG_PRESS)
        put("doubleclick", TouchKind.DOUBLE_TAP)
        put("double-click", TouchKind.DOUBLE_TAP)
        put("swipe_up", TouchKind.SCROLL)
        put("swipe_down", TouchKind.SCROLL)
        put("back", TouchKind.KEY_BACK)
        put("home", TouchKind.KEY_HOME)
        put("recents", TouchKind.KEY_RECENTS)
        put("type", TouchKind.INPUT_TEXT)
        put("input", TouchKind.INPUT_TEXT)
        put("launch_app", TouchKind.OPEN_APP)
        put("sleep", TouchKind.WAIT)
        put("delay", TouchKind.WAIT)
    }

    /** 这些"动作名"其实是"我要看截图"，不是真动作 */
    private val IMAGE_REQUESTS = setOf(
        "screenshot", "screen_shot", "screencap", "look", "see", "view_image", "request_image",
    )

    /** 这些"动作名"表示任务结束 */
    private val FINISH_NAMES = setOf("finish", "done", "complete", "stop")

    fun parse(
        raw: String,
        screenWidth: Int,
        screenHeight: Int,
    ): Parsed {
        val json = extractJson(raw)
            ?: return Parsed(
                thought = null,
                actions = emptyList(),
                needImage = false,
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
                actions = emptyList(),
                needImage = false,
                finished = false,
                summary = "",
                raw = raw,
                warning = "JSON 解析失败（${t.message}）。原文：${json.take(120)}",
            )
        }

        val thought = obj.optString("thought").takeIf { it.isNotBlank() }
        val summary = obj.optString("summary").takeIf { it.isNotBlank() }.orEmpty()
        // 下一步预告。模型可能写成 next_hint / next / hint，都认。
        val nextHint = sequenceOf("next_hint", "next", "hint")
            .map { obj.optString(it, "") }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        // 要截图：可以写在顶层，也可以当成数组里的一个"动作"
        var needImage = obj.optBoolean("need_image", false) ||
            obj.optBoolean("need_screenshot", false) ||
            obj.optBoolean("needShot", false)

        // ---- 收集原始动作条目 ----
        val items = ArrayList<JSONObject>()
        val arr: JSONArray? = obj.optJSONArray("actions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { items.add(it) }
            }
        } else if (obj.has("action")) {
            // 兼容旧格式：单个动作直接写在顶层
            items.add(obj)
        }

        // ---- 逐条解析 ----
        val actions = ArrayList<TouchAction>()
        val notes = ArrayList<String>()
        var finished = obj.optBoolean("finished", false)

        for ((i, item) in items.withIndex()) {
            val name = item.optString("action").trim().lowercase()

            if (name in IMAGE_REQUESTS) {
                needImage = true
                continue
            }
            if (name in FINISH_NAMES) {
                finished = true
                continue
            }
            if (actions.size >= MAX_ACTIONS) {
                notes.add("动作太多（给了 ${items.size} 个），只执行了前 $MAX_ACTIONS 个。")
                break
            }

            val problem = parseOne(item, name, actions, screenWidth, screenHeight)
            if (problem != null) {
                notes.add("第 ${i + 1} 个动作被丢弃：$problem")
            }
        }

        // finish 也可能只写在 action 字段里
        if (obj.optString("action").equals("finish", true) ||
            obj.optString("action").equals("done", true)
        ) {
            finished = true
        }

        if (finished) {
            return Parsed(
                thought, emptyList(), needImage = false, finished = true,
                summary = summary, raw = raw, nextHint = nextHint,
                warning = notes.takeIf { it.isNotEmpty() }?.joinToString("；"),
            )
        }

        // 既没动作也不要图 —— 这一轮等于空转，把原因回灌给模型
        if (actions.isEmpty() && !needImage) {
            val reason = notes.firstOrNull()
                ?: "输出里既没有可用的 actions，也没有把 need_image 设为 true。"
            return Parsed(
                thought, emptyList(), false, false, summary, raw, nextHint, warning = reason,
            )
        }

        return Parsed(
            thought = thought,
            actions = actions,
            needImage = needImage,
            finished = false,
            summary = summary,
            raw = raw,
            nextHint = nextHint,
            warning = notes.takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
    }

    /**
     * 解析一条动作，成功就 append 进 [out]，返回 null；
     * 失败返回给模型看的原因。
     *
     * 时长和坐标都在这里夹紧 —— 越界的值一定是模型手滑，
     * 直接照着执行会点到界面外面去。
     */
    private fun parseOne(
        item: JSONObject,
        actionName: String,
        out: MutableList<TouchAction>,
        screenWidth: Int,
        screenHeight: Int,
    ): String? {
        if (actionName.isBlank()) return "没有 action 字段"

        val kind = ALIASES[actionName]
            ?: return "动作「$actionName」不在支持列表里（可用：" +
                TouchKind.entries.joinToString("/") { it.id } + "）"

        val index = item.optInt("index", 0)
        val x = clamp(item.optInt("x", 0), screenWidth)
        val y = clamp(item.optInt("y", 0), screenHeight)
        val x2 = clamp(item.optInt("x2", 0), screenWidth)
        val y2 = clamp(item.optInt("y2", 0), screenHeight)
        val duration = item.optInt("duration_ms", item.optInt("duration", 0))
        val text = item.optString("text", item.optString("content", ""))
        val pkg = item.optString("package", item.optString("package_name", ""))

        // 方向：模型可能写在 direction 里，也可能写在 action 名里（swipe_up）
        val direction = ScrollDirection.fromId(
            item.optString("direction", "").ifBlank {
                when (actionName) {
                    "swipe_up" -> "up"
                    "swipe_down" -> "down"
                    else -> null
                }
            }
        )

        // ---- 校验：这条动作缺不缺必需参数 ----
        val problem = when (kind) {
            TouchKind.TAP, TouchKind.LONG_PRESS, TouchKind.DOUBLE_TAP ->
                if (index <= 0 && (x <= 0 || y <= 0)) {
                    "「${kind.label}」既没给 index 也没给有效的 x/y"
                } else null

            TouchKind.SWIPE, TouchKind.FLICK, TouchKind.DRAG ->
                if (x <= 0 || y <= 0) "「${kind.label}」缺少起点坐标 x/y" else null

            TouchKind.INPUT_TEXT ->
                if (text.isBlank()) "「输入文字」的 text 是空的" else null

            TouchKind.OPEN_APP ->
                if (pkg.isBlank()) "「打开应用」缺少 package 包名" else null

            else -> null
        }
        if (problem != null) return problem

        // sleep 没写时长就按默认值处理（不算错，不用回灌）
        val ms = when (kind) {
            TouchKind.WAIT -> duration.takeIf { it > 0 } ?: DEFAULT_SLEEP_MS
            else -> duration
        }.coerceIn(0, MAX_SLEEP_MS)

        out.add(
            TouchAction(
                kind = kind,
                targetIndex = if (index > 0) index else 0,
                x = x, y = y, x2 = x2, y2 = y2,
                durationMs = ms,
                direction = direction,
                text = text,
                packageName = pkg,
            )
        )
        return null
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
