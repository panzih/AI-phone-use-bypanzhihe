package com.aiphone.assistant.skill

import android.util.Log
import com.aiphone.assistant.shell.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 技能：查设备状态（电池、网络、电源）。
 *
 * ⚠️ 当前未注册到 SkillRegistry.DEFAULT_SKILLS，见 SkillRegistry 注释。
 * 原因：依赖 Shizuku/ADB，当前版本以无障碍为主，先搁置。
 * 未来 Shizuku 恢复时直接取消注释即可启用。
 *
 * ## 为什么需要这个
 *
 * 模型只看得到控件树和截图，但有些信息截图上看不出来：
 *   - 电池电量、是否充电
 *   - WiFi/移动网络状态
 *   - 屏幕亮灭
 *
 * 这些信息用 `dumpsys` 就能拿到，而且输出很小。
 *
 * ## 体积控制
 *
 * - battery：~360 字节，全收
 * - connectivity：~37KB，只 grep 关键行
 * - power：~几 KB，全收
 *
 * 总共控制在 20KB 以内。
 */
object DeviceStateSkill : Skill {

    private const val TAG = "DeviceStateSkill"
    private const val MAX_CHARS = 20_000

    override val id: String = "device_state"

    override val summary: String =
        "查设备状态：电池电量、网络连接、屏幕亮灭（需要知道手机当前状态时用）"

    override val doc: String = """
# 技能：device_state —— 查设备状态

## 用途
拿到手机当前的硬件状态：电池电量、网络连接、屏幕亮灭。

**什么时候该用：**
- 任务开始前先确认设备状态（比如要跑长任务，先看电量够不够）
- 不确定网络是否连接时
- 屏幕灭了需要唤醒时

**什么时候不该用：**
- 只是想看当前屏幕 —— 那是控件树的事
- 同一个任务里刚查过 —— 不要重复调

## 参数
无。

## 返回
电池、网络、电源三部分的摘要，最多 $MAX_CHARS 字符。

## 示例
{"use_skill": "device_state"}
""".trimIndent()

    override suspend fun run(ctx: SkillContext, args: JSONObject?): String =
        withContext(Dispatchers.IO) {
            // ① 前置检查：Shizuku 必须就绪
            val state = ShizukuBridge.state(ctx.context)
            if (state != ShizukuBridge.State.READY) {
                return@withContext "Shizuku 未就绪（当前：${state.label}），读不到设备状态。" +
                    "让用户到「设置 → 增强能力」里启动并授权。"
            }

            val sb = StringBuilder()

            // 电池：全收（很小）
            sb.appendLine("【电池】")
            val battery = ShizukuBridge.run(ctx.context, "dumpsys battery")
            sb.appendLine(clipSmall(battery, "battery"))
            sb.appendLine()

            // 网络：只 grep 关键行
            sb.appendLine("【网络】")
            val connectivity = ShizukuBridge.run(
                ctx.context,
                "dumpsys connectivity | grep -E 'Active default network|NetworkAgentInfo|Transports|LinkAddresses' | head -30"
            )
            sb.appendLine(clipSmall(connectivity, "connectivity"))
            sb.appendLine()

            // 电源：全收
            sb.appendLine("【电源】")
            val power = ShizukuBridge.run(ctx.context, "dumpsys power")
            sb.appendLine(clipSmall(power, "power"))

            // ② 体积断言
            val result = sb.toString()
            val final = if (result.length > MAX_CHARS) {
                Log.w(TAG, "device_state 输出 ${result.length} 字符，超过上限，截断")
                result.take(MAX_CHARS) + "\n...（已截断）"
            } else {
                result
            }

            // ③ 落日志
            Log.i(TAG, "技能 $id 返回 ${final.length} 字符")

            final
        }

    /** 去掉空行和多余空白，压缩输出 */
    private fun clipSmall(raw: String, name: String): String {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (lines.isEmpty()) "（$name 无输出）" else lines.joinToString("\n")
    }
}
