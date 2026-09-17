package com.aiphone.assistant.skill

import android.util.Log
import com.aiphone.assistant.shell.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 技能：读系统设置。
 *
 * ⚠️ 当前未注册到 SkillRegistry.DEFAULT_SKILLS，见 SkillRegistry 注释。
 * 原因：依赖 Shizuku/ADB，当前版本以无障碍为主，先搁置。
 * 未来 Shizuku 恢复时直接取消注释即可启用。
 *
 * ## 为什么需要这个
 *
 * 有些设置项在截图上看不出来，比如：
 *   - WiFi 是否打开
 *   - 屏幕亮度
 *   - 勿扰模式是否开启
 *   - 自动旋转是否开启
 *
 * 这些用 `settings get` 就能直接读。
 *
 * ## 安全设计
 *
 * **只读，不提供 settings put** —— 写系统设置是危险操作，先不做。
 */
object ReadSettingSkill : Skill {

    private const val TAG = "ReadSettingSkill"
    private const val MAX_CHARS = 4_000

    override val id: String = "read_setting"

    override val summary: String =
        "读系统设置：WiFi/亮度/勿扰/自动旋转等（需要知道某个设置项当前值时用）"

    override val doc: String = """
# 技能：read_setting —— 读系统设置

## 用途
读取 Android 系统设置项的当前值。

**什么时候该用：**
- 不确定某个设置项当前是开还是关时
- 任务开始前先确认相关设置

**什么时候不该用：**
- 只是想看当前屏幕 —— 那是控件树的事
- 同一个任务里刚查过同一个设置 —— 不要重复调

## 参数
```json
{
  "namespace": "global|system|secure",  // 命名空间，默认 global
  "key": "wifi_on"                       // 设置项的 key
}
```

常用 key 示例：
- `wifi_on`（global）—— WiFi 是否打开
- `screen_brightness`（system）—— 屏幕亮度（0-255）
- `zen_mode`（global）—— 勿扰模式
- `accelerometer_rotation`（system）—— 自动旋转

## 返回
设置项的当前值。如果读不到，会说明"这个设置项读不到"。

## 示例
{"use_skill": "read_setting", "skill_args": {"namespace": "global", "key": "wifi_on"}}
""".trimIndent()

    override suspend fun run(ctx: SkillContext, args: JSONObject?): String =
        withContext(Dispatchers.IO) {
            // ① 前置检查
            val state = ShizukuBridge.state(ctx.context)
            if (state != ShizukuBridge.State.READY) {
                return@withContext "Shizuku 未就绪（当前：${state.label}），读不到系统设置。" +
                    "让用户到「设置 → 增强能力」里启动并授权。"
            }

            // 解析参数
            val namespace = args?.optString("namespace", "global") ?: "global"
            val key = args?.optString("key", "") ?: ""

            if (key.isBlank()) {
                return@withContext "缺少参数：需要传 key（要读哪个设置项）。"
            }

            // ② 跑命令
            val raw = ShizukuBridge.run(ctx.context, "settings get $namespace $key")

            // ③ 处理返回值
            // settings get 读不到时返回字符串 "null"
            val value = raw.trim()
            val result = when {
                value == "null" -> "设置项「$namespace/$key」读不到（可能不存在或权限不够）。"
                value.isEmpty() -> "设置项「$namespace/$key」返回空。"
                else -> "设置项 $namespace/$key = $value"
            }

            // ④ 体积断言
            val final = if (result.length > MAX_CHARS) {
                result.take(MAX_CHARS) + "\n...（已截断）"
            } else {
                result
            }

            // ⑤ 落日志
            Log.i(TAG, "技能 $id 返回 ${final.length} 字符（$namespace/$key）")

            final
        }
}
