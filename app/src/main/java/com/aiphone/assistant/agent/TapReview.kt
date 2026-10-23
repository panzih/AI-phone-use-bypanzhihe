package com.aiphone.assistant.agent

import com.aiphone.assistant.data.TapReviewMode
import com.aiphone.assistant.touch.TouchAction
import com.aiphone.assistant.touch.TouchKind
import org.json.JSONObject
import kotlin.math.min

/**
 * 「点击前复核」的纯逻辑：裁哪一块、问模型什么、怎么读回答。
 *
 * 这里**不碰 Android 图形**（裁图画十字是 [TapReviewRenderer] 的事），
 * 所以能在 JVM 单测里把边界钉死：
 *   - 落点贴着屏幕边缘时，裁剪框要夹回屏内，十字不能跑到图外；
 *   - 模型返回的更正坐标是**裁剪图内**的，要换算回屏幕坐标；
 *   - 模型说"不通过"但没给坐标时，按叫停处理，不能默认放行。
 */
object TapReview {

    /**
     * 复核员的人设。
     *
     * 刻意和主 Agent 的提示词分开：复核是一次独立的短请求，不需要也不应该
     * 把主对话几万 token 的历史再发一遍（那会把复核变成第二个大头）。
     * 它只拿到"任务 + 这一步要做什么 + 一张十字准星局部图"。
     */
    val SYSTEM = """
你是「点击前复核员」。你只做一件事：在模型真正点下去之前，检查局部截图里的十字准星是不是落在正确的位置。

规则：
1. 只看十字准星这一下。不要评价任务本身，也不要给后续操作建议。
2. 准星落在目标控件内部或边缘 = 通过；落在相邻控件、空白处、图标缝隙、或者目标根本不在图里 = 不通过。
3. 如果看不清，按不通过处理 —— 宁可让主模型重新看一屏，也不要盲点。
4. 只说结果，不解释推理过程。

输出格式（只输出一个 JSON，不要 Markdown 代码块）：
{"ok":true}
{"ok":false,"x":数字,"y":数字,"why":"一句话：偏到哪了、应该点哪"}
{"ok":false,"abort":true,"why":"一句话：为什么这一步不能点"}

其中 x / y 是**这张裁剪图内**的像素坐标，左上角是 (0,0)。
""".trimIndent()

    /** 裁剪区域（屏幕像素）。 */
    data class Crop(
        val left: Int,
        val top: Int,
        val size: Int,
    ) {
        val right: Int get() = left + size
        val bottom: Int get() = top + size
    }

    /**
     * 复核结论。
     *
     * [approved] = true 才按原点执行；[correctedX]/[correctedY] 非空 = 改点；
     * [abort] = true = 这一步不要执行，交回主模型重新计划。
     * [parsed] = false 只用于"回答实在看不懂"，调用方按原点执行并记一条警告。
     */
    data class Decision(
        val approved: Boolean,
        val correctedX: Int? = null,
        val correctedY: Int? = null,
        val abort: Boolean = false,
        val why: String = "",
        val parsed: Boolean = true,
    )

    /**
     * 这个动作要不要复核。
     *
     * - 只复核"点"类动作：滑动/滚动没有唯一落点，复核一张图说明不了什么；
     * - 浏览器模式跳过：那里是按 DOM 编号点击，位置由元素盒模型决定，不是模型估的；
     * - [TapReviewMode.COORDINATES] 只复核没有编号的坐标点击 —— 编号点击
     *   本来就落在控件中心，再问一次纯属重复花钱。
     */
    fun shouldReview(
        mode: TapReviewMode,
        kind: TouchKind,
        targetIndex: Int,
        isBrowser: Boolean,
    ): Boolean {
        if (mode == TapReviewMode.OFF || isBrowser) return false
        if (kind != TouchKind.TAP && kind != TouchKind.LONG_PRESS && kind != TouchKind.DOUBLE_TAP) {
            return false
        }
        if (mode == TapReviewMode.COORDINATES && targetIndex > 0) return false
        return true
    }

    /**
     * 以落点为中心裁一块正方形。
     *
     * 边长取屏幕短边的 45%，夹在 240~720 之间 —— 太小看不到相邻控件，
     * 太大既费 token 又会让准星显得不起眼。贴边时整体平移回屏内，
     * 准星因此可能不在正中心，提示词会明确告诉模型它在图内的真实位置。
     */
    fun cropFor(x: Int, y: Int, screenWidth: Int, screenHeight: Int): Crop {
        val w = screenWidth.coerceAtLeast(1)
        val h = screenHeight.coerceAtLeast(1)
        val shortest = min(w, h)
        val size = if (shortest <= 240) {
            shortest
        } else {
            (shortest * 0.45f).toInt().coerceIn(240, min(720, shortest))
        }
        val left = (x - size / 2).coerceIn(0, (w - size).coerceAtLeast(0))
        val top = (y - size / 2).coerceIn(0, (h - size).coerceAtLeast(0))
        return Crop(left, top, size)
    }

    /** 拼复核请求。坐标一律说清"图内"还是"屏幕"，避免模型算错一次。 */
    fun prompt(
        task: String,
        step: Int,
        actionDesc: String,
        targetDesc: String,
        pointX: Int,
        pointY: Int,
        crop: Crop,
        screenWidth: Int,
        screenHeight: Int,
    ): String {
        val localX = pointX - crop.left
        val localY = pointY - crop.top
        return """
用户任务：${task.take(500)}

当前：第 $step 步。
模型准备执行：$actionDesc
定位依据：$targetDesc

这张图是从手机屏幕左上角偏移 (${crop.left}, ${crop.top}) 处裁下来的 ${crop.size}×${crop.size} 像素局部图。
图内坐标以这张图的左上角为 (0,0)；十字准星中心在图内 (${localX}, ${localY})，对应屏幕坐标 ($pointX, $pointY)。屏幕总尺寸 ${screenWidth}×${screenHeight}。

请判断：十字准星是否落在模型要操作的那个控件上？
- 落在目标控件内部或边缘 → 通过。
- 落在相邻控件、空白处、图标缝隙 → 不通过，并给出更合适的图内坐标。
- 目标根本不在图里、或看不清 → 不通过；如果这一步明显不该点，直接 abort。
""".trimIndent()
    }

    /**
     * 解析复核回答。
     *
     * 对"看不懂"采取**放行**：复核是增益功能，不能因为复核员偶发输出格式
     * 错误就把用户的任务卡死。真正的安全边界仍由主循环的白名单/坐标夹紧守着。
     */
    fun parse(raw: String, crop: Crop, screenWidth: Int, screenHeight: Int): Decision {
        val obj = extractObject(raw)
        if (obj != null) {
            val abort = obj.optBoolean("abort", false) || obj.optBoolean("veto", false)
            val why = obj.optString("why", obj.optString("reason", "")).trim()
            if (abort) {
                return Decision(
                    approved = false,
                    abort = true,
                    why = why.ifBlank { "复核员认为这一步不能点" },
                )
            }
            val ok = when {
                obj.has("ok") -> obj.optBoolean("ok", false)
                obj.has("approved") -> obj.optBoolean("approved", false)
                obj.has("confirm") -> obj.optBoolean("confirm", false)
                obj.has("pass") -> obj.optBoolean("pass", false)
                else -> false
            }
            if (ok) return Decision(approved = true, why = why)

            val x = firstInt(obj, "x", "cx", "screen_x")
            val y = firstInt(obj, "y", "cy", "screen_y")
            if (x != null && y != null) {
                val sx = toScreenX(x, crop, screenWidth)
                val sy = toScreenY(y, crop, screenHeight)
                return Decision(
                    approved = false,
                    correctedX = sx,
                    correctedY = sy,
                    why = why.ifBlank { "复核员给出了更正的落点" },
                )
            }
            return Decision(
                approved = false,
                abort = true,
                why = why.ifBlank { "复核员认为落点不对，但没有给出更正坐标" },
            )
        }

        // 兜底：有些人/模型会只回一句"通过/不通过"。先判否定，避免
        // "不通过"里包含"通过"被误判成放行。
        val text = raw.trim().lowercase()
        val negative = listOf("不通过", "不对", "不是", "错了", "否", "no", "not", "reject", "wrong")
            .any { text.contains(it) }
        if (negative) {
            return Decision(approved = false, abort = true, why = "复核员认为落点不对")
        }
        val positive = listOf("通过", "正确", "对的", "可以", "yes", "ok", "approve")
            .any { text.contains(it) }
        if (positive) return Decision(approved = true)

        return Decision(
            approved = true,
            why = "复核输出无法解析，按原点执行",
            parsed = false,
        )
    }

    private fun toScreenX(x: Int, crop: Crop, screenWidth: Int): Int {
        val sx = if (x in 0..crop.size) crop.left + x else x
        return sx.coerceIn(0, (screenWidth - 1).coerceAtLeast(0))
    }

    private fun toScreenY(y: Int, crop: Crop, screenHeight: Int): Int {
        val sy = if (y in 0..crop.size) crop.top + y else y
        return sy.coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int? {
        for (k in keys) {
            if (!obj.has(k)) continue
            val v = obj.optInt(k, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) return v
        }
        return null
    }

    /** 从可能带 Markdown/解释的文字里抠出第一个 JSON 对象。 */
    private fun extractObject(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }
}
