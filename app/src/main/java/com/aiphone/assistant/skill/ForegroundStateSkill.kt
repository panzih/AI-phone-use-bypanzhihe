package com.aiphone.assistant.skill

import android.util.Log
import com.aiphone.assistant.shell.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 技能：查前台应用状态。
 *
 * ⚠️ 当前未注册到 SkillRegistry.DEFAULT_SKILLS，见 SkillRegistry 注释。
 * 原因：依赖 Shizuku/ADB，当前版本以无障碍为主，先搁置。
 * 未来 Shizuku 恢复时直接取消注释即可启用。
 *
 * ## 为什么需要这个
 *
 * 模型知道"现在在哪个 App 的哪个页面"非常重要，但有时候：
 *   - 截图看不清楚
 *   - 控件树解析不到
 *   - 需要确认当前确实在某个页面
 *
 * 用 `dumpsys activity activities` 就能直接查到前台 Activity。
 *
 * ## 体积控制
 *
 * 原始 dumpsys 输出 313KB，太大。
 * 只 grep 关键行：topResumedActivity、ResumedActivity、任务栈顶几项。
 */
object ForegroundStateSkill : Skill {

    private const val TAG = "ForegroundStateSkill"
    private const val MAX_CHARS = 20_000

    override val id: String = "foreground_state"

    override val summary: String =
        "查前台应用状态：当前在哪个 App 的哪个页面（需要确认当前页面时用）"

    override val doc: String = """
# 技能：foreground_state —— 查前台应用状态

## 用途
拿到当前前台应用和 Activity 的信息，以及任务栈顶几项。

**什么时候该用：**
- 任务开始前先确认当前在哪个页面
- 不确定有没有跳转到目标页面时
- 截图看不清楚当前界面时

**什么时候不该用：**
- 控件树已经能看清楚当前页面了 —— 不用调技能
- 同一个任务里刚查过 —— 不要重复调

## 参数
无。

## 返回
当前前台 Activity、任务栈顶几项，最多 $MAX_CHARS 字符。

## 示例
{"use_skill": "foreground_state"}
""".trimIndent()

    override suspend fun run(ctx: SkillContext, args: JSONObject?): String =
        withContext(Dispatchers.IO) {
            // ① 前置检查
            val state = ShizukuBridge.state(ctx.context)
            if (state != ShizukuBridge.State.READY) {
                return@withContext "Shizuku 未就绪（当前：${state.label}），读不到前台状态。" +
                    "让用户到「设置 → 增强能力」里启动并授权。"
            }

            // ② 跑命令（只 grep 关键行）
            val raw = ShizukuBridge.run(
                ctx.context,
                "dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity|mResumedActivity|Hist #' | head -20"
            )

            // ③ 处理返回值
            val lines = raw.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val result = if (lines.isEmpty()) {
                "没有查到前台 Activity 信息。"
            } else {
                buildString {
                    appendLine("【前台应用状态】")
                    for (line in lines) {
                        appendLine("- ").append(line)
                    }
                }
            }

            // ④ 体积断言
            val final = if (result.length > MAX_CHARS) {
                Log.w(TAG, "foreground_state 输出 ${result.length} 字符，超过上限，截断")
                result.take(MAX_CHARS) + "\n...（已截断）"
            } else {
                result
            }

            // ⑤ 落日志
            Log.i(TAG, "技能 $id 返回 ${final.length} 字符")

            final
        }
}
