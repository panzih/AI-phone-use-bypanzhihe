package com.aiphone.assistant.record

import android.content.Context
import android.util.Log
import com.aiphone.assistant.a11y.UiNode
import com.aiphone.assistant.log.AppLog
import com.aiphone.assistant.skill.Skill
import com.aiphone.assistant.skill.SkillContext
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 录制学来的技能（宏）。
 *
 * ## 这是"快"的来源
 *
 * 让 AI 一步步操作，每一步都要一次模型调用加一轮判断。而这个技能是
 * **确定性回放**：按录下来的顺序找到同一个控件、点下去 ——
 * 一次技能调用走完十几步，中间不花任何模型推理。
 * 这正是"以后执行起来快很多"的实现方式。
 *
 * ## 代价：它只会照着做
 *
 * 界面变了、多了个弹窗、按钮挪了位置，回放就会在某一步停下来。
 * 所以每次执行都会返回一份**带步骤号的报告**：卡在哪一步、
 * 那一步想点什么、前面完成了几步。模型据此决定接着自己操作、
 * 换别的做法，还是告诉用户。
 *
 * 快路走不通就退回慢路，而不是整个任务失败 —— 这是它和普通脚本的区别。
 */
class MacroSkill(
    override val id: String,
    val title: String,
    /** summary 和 doc 存在文件里，所以用字段带着，而不是每次现算 */
    private val summaryText: String,
    private val docText: String,
    val steps: List<RecordedStep>,
    /** 每步之间等多久 */
    val stepGapMs: Int = DEFAULT_GAP_MS,
    val createdAt: Long = System.currentTimeMillis(),
) : Skill {

    override val summary: String get() = summaryText
    override val doc: String get() = docText

    override suspend fun run(ctx: SkillContext, args: JSONObject?): String {
        if (steps.isEmpty()) return "技能「$title」里没有任何步骤。"

        val report = StringBuilder()
        report.appendLine("技能「$title」开始回放，共 ${steps.size} 步。")

        var done = 0
        for ((i, step) in steps.withIndex()) {
            val no = i + 1

            // 密码步直接跳过：我们不回放密码，也不需要为此等元素出现。
            // 但要在报告里说清楚，否则用户会以为少点了一下是 bug
            if (step.action == "input" && step.sensitive) {
                report.appendLine("第 $no 步是密码输入，已跳过（密码不记录也不回放）。")
                done++
                continue
            }

            // 1. 该换应用就先换。录制时跨过应用，回放时也得跨回去
            switchAppIfNeeded(ctx, step)

            // 2. 等目标控件出现。界面加载快慢不一，固定 sleep 一下再点必然出事
            val node = waitFor(ctx, step, ELEMENT_TIMEOUT_MS)
            if (node == null) {
                report.appendLine(
                    "第 $no 步卡住了：当前界面上找不到要操作的元素（${step.label()}）。" +
                        "前面 $done 步已经完成，接下来请你自己判断怎么继续。"
                )
                return report.toString()
            }

            // 3. 执行。按**编号**点而不是按坐标 —— 编号对应的是系统给的最新
            //    bounds，比录制时那一刻的坐标可靠
            val err = execute(ctx, step, node)

            if (err != null) {
                report.appendLine("第 $no 步（${step.label()}）执行失败：$err。前面 $done 步已完成。")
                return report.toString()
            }

            done++
            report.appendLine("第 $no 步：${step.label()} ✓")
            delay(stepGapMs.toLong())
        }

        report.appendLine("技能「$title」${steps.size} 步全部执行完成。")
        return report.toString()
    }

    private suspend fun execute(ctx: SkillContext, step: RecordedStep, node: UiNode): String? =
        when {
            step.action == "input" -> ctx.controller.execute(
                TouchAction(kind = TouchKind.INPUT_TEXT, text = step.inputText)
            )

            step.action == "long_press" -> ctx.controller.execute(
                TouchAction(
                    kind = TouchKind.LONG_PRESS,
                    targetIndex = node.index,
                    x = node.centerX,
                    y = node.centerY,
                )
            )

            else -> ctx.controller.execute(
                TouchAction(
                    kind = TouchKind.TAP,
                    targetIndex = node.index,
                    x = node.centerX,
                    y = node.centerY,
                )
            )
        }

    private suspend fun switchAppIfNeeded(ctx: SkillContext, step: RecordedStep) {
        if (step.packageName.isBlank()) return
        val current = runCatching { ctx.controller.currentPackage() }.getOrNull() ?: return
        if (current == step.packageName) return
        ctx.controller.execute(
            TouchAction(kind = TouchKind.OPEN_APP, packageName = step.packageName)
        )
        delay(OPEN_APP_WAIT_MS)
    }

    /** 等元素出现。到点还没找到就返回 null */
    private suspend fun waitFor(
        ctx: SkillContext,
        step: RecordedStep,
        timeoutMs: Long,
    ): UiNode? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val nodes = runCatching { ctx.controller.parseNodes() }.getOrDefault(emptyList())
            val hit = ElementMatch.best(step, nodes)
            if (hit != null) return@withContext hit
            delay(POLL_MS)
        }
        null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("summary", summaryText)
        put("doc", docText)
        put("stepGapMs", stepGapMs)
        put("createdAt", createdAt)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        /** 等元素出现的最长时间 */
        const val ELEMENT_TIMEOUT_MS = 6000L

        /** 轮询间隔 */
        const val POLL_MS = 300L

        /** 切换应用之后多等一会儿 */
        const val OPEN_APP_WAIT_MS = 2000L

        /** 默认步间隔。和 Agent 的默认一致 */
        const val DEFAULT_GAP_MS = 1500

        /**
         * 从 json 还原。
         *
         * 解析失败一律返回 null 而不是抛异常 —— 一个坏掉的技能文件
         * 不该让整个技能列表加载不出来。
         */
        fun fromJson(o: JSONObject): MacroSkill? = runCatching {
            val stepsArr = o.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArr.length()).mapNotNull { i ->
                stepsArr.optJSONObject(i)?.let { RecordedStep.fromJson(it) }
            }
            MacroSkill(
                id = o.optString("id").ifBlank { return null },
                title = o.optString("title").ifBlank { "未命名技能" },
                summaryText = o.optString("summary"),
                docText = o.optString("doc"),
                steps = steps,
                stepGapMs = o.optInt("stepGapMs", DEFAULT_GAP_MS),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }.getOrNull()
    }
}

/**
 * 学会的技能存到哪。
 *
 * 一份技能一个 json，放在 `<应用私有目录>/纸盒/macros/`。
 * 用 json 而不是塞进 SharedPreferences：技能会越来越多，
 * 而且用户可能想自己看看、甚至手改。
 */
object MacroStore {

    private const val TAG = "MacroStore"

    fun dir(context: Context): File = AppLog.macroDir(context)

    fun save(context: Context, macro: MacroSkill): File? = runCatching {
        val f = File(dir(context), "${macro.id}.json")
        f.writeText(macro.toJson().toString(2))
        Log.i(TAG, "已保存技能：${f.name}")
        f
    }.getOrNull()

    fun loadAll(context: Context): List<MacroSkill> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { MacroSkill.fromJson(JSONObject(f.readText())) }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun delete(context: Context, id: String): Boolean =
        File(dir(context), "$id.json").delete()

    /**
     * id 清理：技能名是用户或 AI 起的，可能带斜杠、冒号、空格。
     *
     * 同时兼作去重 —— 同名技能会覆盖，这是想要的行为（重新学一遍就更新）。
     */
    fun sanitizeId(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\s/\\\\:*?\"<>|\\n\\r\\t]+"), "_")
            .take(32)
            .trim('_')
        return "macro_" + cleaned.ifBlank { "skill" }
    }
}
