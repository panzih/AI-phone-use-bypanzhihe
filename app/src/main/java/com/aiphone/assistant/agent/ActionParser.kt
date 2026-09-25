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
 * **2. `need_image` / `use_skill` / `capture`。** 模型可以只要一张截图、
 * 或者只要调用一个技能，而不给动作。这不算"没解析出动作" —— Agent 会把
 * 结果取回来重新问它一次，而且这一轮不算一步。
 * `capture` 不一样：它是**动作序列里的一个元素**（可以夹在两步点击中间），
 * 截到的图随下一步一起发，所以不多一次往返。
 */
object ActionParser {

    /** 一轮最多执行多少个动作，防止模型给一条超长脚本 */
    const val MAX_ACTIONS = 12

    /** sleep 没写时长时用的默认值 */
    const val DEFAULT_SLEEP_MS = 1500

    /** 单次等待的上限，防止模型写出一个"等一小时"的手滑值 */
    const val MAX_SLEEP_MS = 60_000

    /**
     * **没声明 `long_wait` 时**允许的最长等待。
     *
     * 超过就夹到这个值，并回灌一行警告给模型。理由：系统本来就会在每个
     * 动作之后自动等界面稳定（普通跳转 0.4~1.2s、开应用 1.5~2.5s），
     * 所以 `sleep` 只有"倒计时/长加载"这种自动等待盖不住的情况才需要。
     * 用户在真机上见过模型写「等待 25000ms」——那是纯粹白等，
     * 还让急停要多等二十几秒才生效。
     *
     * [MAX_SLEEP_MS] 仍是硬上限：写了 long_wait 也不是想睡多久就睡多久。
     */
    const val MAX_SILENT_SLEEP_MS = 5_000

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
        /** 任务成功收尾 */
        val finished: Boolean,
        /**
         * 模型判断**做不下去**了（不是失败，是止损）。
         *
         * 和 [finished] 分开是必须的：把"做不成"报成"已完成"，用户会以为
         * 事情办好了；而这两种情况该给他看的东西完全不同 ——
         * 一个是结果，一个是"卡在哪、需要你做什么"。
         */
        val failed: Boolean = false,
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
        /**
         * 模型要求调用的技能 id。
         *
         * 和 `needImage` 一样属于"先给我信息，我再决定怎么做"：
         * 有它的时候 [actions] 会被忽略 —— 等技能结果回来再说。
         */
        val skillId: String? = null,
        /** 技能的参数，可能没有 */
        val skillArgs: JSONObject? = null,
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
        // 端侧关闭弹窗：模型可能用这些写法，都认（无参数，端侧自己找按钮）
        put("dismiss", TouchKind.DISMISS_DIALOG)
        put("close_dialog", TouchKind.DISMISS_DIALOG)
        put("close_popup", TouchKind.DISMISS_DIALOG)
    }

    /** 这些"动作名"其实是"我要看截图"，不是真动作。
     *
     *  只对**顶层老格式**（`{"action":"screenshot"}`）生效 ——
     * 写在 `actions[]` 里的一律归一成 CAPTURE 动作，见 parse() 里的说明。 */
    private val IMAGE_REQUESTS = setOf(
        "screenshot", "screen_shot", "screencap", "look", "see", "view_image", "request_image",
    )

    /** 这些"动作名"表示**成功**收尾 */
    private val FINISH_NAMES = setOf("finish", "done", "complete", "success")

    /**
     * 这些"动作名"表示**做不下去**。
     *
     * `stop` 放在这里而不是上面：模型在半路说 stop，几乎都是"我不想继续试了"，
     * 而不是"任务完成了"。报成成功会骗到用户。
     */
    private val FAIL_NAMES = setOf(
        "fail", "failed", "give_up", "giveup", "abort", "blocked", "cannot", "cant", "stuck", "stop",
    )

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

        // 想在这批动作之后顺手看一眼（老字段，现在只做兼容）
        val wantShotAfter = obj.optBoolean("screenshot_after", false)

        // 要调用技能：字段名模型可能写成好几种，都认
        val skillId = sequenceOf("use_skill", "skill", "call_skill", "useSkill")
            .map { obj.optString(it, "").trim() }
            .firstOrNull { it.isNotBlank() }
        // 参数：skill_args 优先，写成 args 也认
        val skillArgs = obj.optJSONObject("skill_args") ?: obj.optJSONObject("args")

        // ---- 收集原始动作条目 ----
        val items = ArrayList<JSONObject>()
        val arr: JSONArray? = obj.optJSONArray("actions")
        // 老格式：单个动作直接写顶层。它和 actions[] 里的同名动作**语义不同**
        // （见下面 IMAGE_REQUESTS 分支），所以要记住来源
        val fromTopLevel = arr == null && obj.has("action")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { items.add(it) }
            }
        } else if (obj.has("action")) {
            items.add(obj)
        }

        // ---- 逐条解析 ----
        val actions = ArrayList<TouchAction>()
        val notes = ArrayList<String>()
        var finished = obj.optBoolean("finished", false)
        // failed 可以写在顶层，也可以当成数组里的一个"动作"
        var failed = obj.optBoolean("failed", false) ||
            obj.optBoolean("give_up", false) ||
            obj.optBoolean("blocked", false)

        for ((i, item) in items.withIndex()) {
            val name = item.optString("action").trim().lowercase()

            if (name in IMAGE_REQUESTS) {
                // 两种来源、两种处理：
                //   actions[] 里写了 screenshot/look 这类名字 → 当成一个 capture
                //     **动作**，保留它在序列里的位置（序列中间截的就是中间那一步
                //     的画面，图随下一步发，不多一次往返）
                //   顶层老格式 {"action":"screenshot"} → 仍按"立即要图"处理，
                //     免得老历史/老脚本的行为变样
                if (fromTopLevel) {
                    needImage = true
                    continue
                }
                if (actions.size >= MAX_ACTIONS) {
                    notes.add("动作太多（给了 ${items.size} 个），只执行了前 $MAX_ACTIONS 个。")
                    break
                }
                actions.add(TouchAction(kind = TouchKind.CAPTURE))
                continue
            }
            if (name in FINISH_NAMES) {
                finished = true
                continue
            }
            if (name in FAIL_NAMES) {
                failed = true
                continue
            }
            if (actions.size >= MAX_ACTIONS) {
                notes.add("动作太多（给了 ${items.size} 个），只执行了前 $MAX_ACTIONS 个。")
                break
            }

            val problem = parseOne(item, name, actions, notes, screenWidth, screenHeight)
            if (problem != null) {
                notes.add("第 ${i + 1} 个动作被丢弃：$problem")
            }
        }

        // finish / fail 也可能只写在 action 字段里
        val topAction = obj.optString("action").trim().lowercase()
        if (topAction == "finish" || topAction == "done") finished = true
        if (topAction in FAIL_NAMES) failed = true

        // ---- screenshot_after 归一化 ----
        // 它的语义是"这批动作之后顺手看一眼"，和"在序列末尾放一个 capture"
        // 完全等价。现在统一成后者：提示词里已经不再教这个字段，这里只为兼容
        // 模型偶发的老写法。注意三条边界：
        //   - 和 need_image 同给 → need_image 优先（立即给图，更直接）
        //   - 这批就是收尾（finished / failed）→ 不加，任务马上结束没人消费它
        //   - 没动作也没技能 → 意图就是看图，退化成立即要图，别落到"空转"警告
        if (wantShotAfter) {
            when {
                needImage -> {}
                finished || failed -> {}
                actions.isNotEmpty() -> {
                    // 位置已经满了就先腾一个出来，别把末尾那个动作挤掉
                    if (actions.size >= MAX_ACTIONS) actions.removeAt(actions.size - 1)
                    actions.add(TouchAction(kind = TouchKind.CAPTURE))
                }
                skillId == null -> needImage = true
            }
        }

        // failed 优先于 finished：两个都给了的话，宁可报"没做成"，
        // 也不要谎报成功
        if (failed) {
            return Parsed(
                thought = thought,
                actions = emptyList(),
                needImage = false,
                finished = false,
                failed = true,
                summary = summary,
                raw = raw,
                nextHint = nextHint,
                warning = notes.takeIf { it.isNotEmpty() }?.joinToString("；"),
            )
        }

        if (finished) {
            // 同批可能还带着动作（语义"做完这批就完成"）：动作必须保留，
            // 由 Agent 先执行、再收尾，不能在这里清空成 emptyList()。
            return Parsed(
                thought, actions, needImage = false, finished = true,
                summary = summary, raw = raw, nextHint = nextHint,
                warning = notes.takeIf { it.isNotEmpty() }?.joinToString("；"),
            )
        }

        // 既没动作、也不要图、也不调技能 —— 这一轮等于空转，把原因回灌给模型
        if (actions.isEmpty() && !needImage && skillId == null) {
            val reason = notes.firstOrNull()
                ?: "输出里既没有可用的 actions，也没有 need_image，也没有 use_skill。"
            return Parsed(
                thought = thought,
                actions = emptyList(),
                needImage = false,
                finished = false,
                summary = summary,
                raw = raw,
                nextHint = nextHint,
                warning = reason,
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
            skillId = skillId,
            skillArgs = skillArgs,
        )
    }

    /**
     * 解析一条动作，成功就 append 进 [out]，返回 null；
     * 失败返回给模型看的原因。
     *
     * 时长和坐标都在这里夹紧 —— 越界的值一定是模型手滑，
     * 直接照着执行会点到界面外面去。
     *
     * 能执行但需要提醒模型的（比如"时长被夹短了"）不返回原因，
     * 而是往 [notes] 里追加一行 —— 那行最后会拼进 warning 回灌给模型，
     * 动作本身照常执行。
     */
    private fun parseOne(
        item: JSONObject,
        actionName: String,
        out: MutableList<TouchAction>,
        notes: MutableList<String>,
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
        // "ms" 是模型爱写的简写（duration_ms / duration 之外多认一个）
        val duration = item.optInt("duration_ms", item.optInt("duration", item.optInt("ms", 0)))
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
        var ms = when (kind) {
            TouchKind.WAIT -> duration.takeIf { it > 0 } ?: DEFAULT_SLEEP_MS
            else -> duration
        }.coerceIn(0, MAX_SLEEP_MS)

        // 长等待必须显式声明：系统本来就会自动等界面稳定，写个 25 秒的 sleep
        // 多半是白等，还会拖慢急停的响应。声明了 long_wait 的照办（但仍受
        // MAX_SLEEP_MS 硬上限约束），没声明的夹短并告诉他一声。
        if (kind == TouchKind.WAIT && ms > MAX_SILENT_SLEEP_MS) {
            val declared = item.optBoolean("long_wait", false) || item.optBoolean("longWait", false)
            if (declared) {
                val note = item.optString("note", item.optString("reason", "")).trim()
                notes.add("等待 ${ms}ms" + if (note.isBlank()) "" else "（$note）")
            } else {
                notes.add(
                    "等待 ${ms}ms 未声明 long_wait，已按 ${MAX_SILENT_SLEEP_MS}ms 执行。" +
                        "系统已自动等界面稳定，确实需要长等（倒计时/长加载）" +
                        "就加 \"long_wait\": true 并在 note 里说明原因。"
                )
                ms = MAX_SILENT_SLEEP_MS
            }
        }

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
