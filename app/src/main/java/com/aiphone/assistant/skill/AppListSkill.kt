package com.aiphone.assistant.skill

import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 技能：列出手机上已安装的可启动应用。
 *
 * ## 为什么这是第一个技能
 *
 * 它对应的失败最直观：模型想 `open_app`，只能凭记忆编包名。
 * 编 `com.tencent.mm`（微信）这种常见的大概率对，编小众应用就是碰运气，
 * 而失败时的报错是"没找到这个包"，看不出真正原因是"名字记错了"。
 *
 * 更麻烦的是**系统应用**：设置在不同的 ROM 上叫 `com.android.settings`、
 * `com.miui.securitycenter`、`com.coloros.safecenter`…… 只能查，不能猜。
 *
 * ## 两个刻意的取舍
 *
 * **只列可启动的。** 用 `CATEGORY_LAUNCHER` 查，而不是把
 * `getInstalledApplications()` 全倒出来 —— 后者包含几百个后台服务组件
 * （`com.android.providers.*` 这类），模型拿到的表里全是噪声。
 *
 * **限量。** 一台手机通常 50~150 个可启动应用，多的能到 300。
 * 每行约 30 字符，全列出来大概 3k token。这个数字可以接受（一次调用，
 * 之后靠缓存），但仍然设个上限，免得装了几百个应用的机器上爆掉。
 */
object AppListSkill : Skill {

    /** 最多列多少个。超出的只报个数 */
    private const val MAX_APPS = 200

    override val id: String = "list_apps"

    override val summary: String =
        "列出手机上已安装的可启动应用和它们的包名（open_app 之前不确定包名时用）"

    override val doc: String = """
# 技能：list_apps —— 列出已安装的应用

## 用途
拿到手机上所有**可启动应用**的名字和包名。

**什么时候该用：**
- 要用 open_app 打开某个应用之前。**不要凭记忆编包名** —— 编错了
  系统只会说"没找到这个包"，看不出是名字记错了。
- 不确定手机上装没装某个应用时。
- 打开系统设置类应用时（不同厂商的 ROM 包名完全不一样，只能查）。

**什么时候不该用：**
- 只是想看当前屏幕 —— 那是控件树的事，不用调技能。
- 同一个任务里已经调过了，结果还在上下文里 —— 不要重复调。

## 参数
无。

## 返回
一行一个应用，格式是 `应用名 (包名)`，按应用名排序，最多 ${MAX_APPS} 个。
当前正显示在前台的那个会标一个 `← 当前前台`。

## 示例
{"use_skill": "list_apps"}

## 注意
- 只包含**有启动入口**的应用（桌面图标那种），不含纯后台服务组件。
- 结果会留在上下文里，后面几步都能直接引用，不用反复调用。
""".trimIndent()

    override suspend fun run(ctx: SkillContext, args: JSONObject?): String =
        withContext(Dispatchers.IO) {
            val pm = ctx.context.packageManager

            val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = try {
                pm.queryIntentActivities(launchable, 0)
            } catch (t: Throwable) {
                return@withContext "读取应用列表失败：${t.javaClass.simpleName} ${t.message}"
            }

            if (resolved.isEmpty()) {
                return@withContext "没有查到任何可启动应用。可能是这台设备的 PackageManager 限制，" +
                    "或者当前的 Android 版本对这个查询做了过滤。"
            }

            // 同一个包可能有多个入口（比如微信的"扫一扫"），按包名去重
            val byPackage = LinkedHashMap<String, String>()
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (byPackage.containsKey(pkg)) continue
                val label = try {
                    info.loadLabel(pm).toString().trim()
                } catch (t: Throwable) {
                    ""
                }
                byPackage[pkg] = label.ifBlank { pkg }
            }

            val foreground = try {
                ctx.controller.currentPackage()
            } catch (t: Throwable) {
                null
            }

            val sorted = byPackage.entries
                .sortedBy { it.value.lowercase() }

            val shown = sorted.take(MAX_APPS)
            val sb = StringBuilder()
            sb.append("共 ").append(sorted.size).append(" 个可启动应用")
            if (sorted.size > MAX_APPS) {
                sb.append("（下面只列前 ").append(MAX_APPS).append(" 个）")
            }
            sb.append("：\n")
            for ((pkg, label) in shown) {
                sb.append("- ").append(label).append(" (").append(pkg).append(')')
                if (pkg == foreground) sb.append("  ← 当前前台")
                sb.append('\n')
            }
            sb.append("\n提示：用 open_app 打开时 package 填括号里的包名。")
            sb.toString()
        }
}
