# ============================================================
# 纸盒 —— release 混淆规则
# ============================================================

# ---------- 通用 ----------
# 保留 Kotlin metadata（反射、序列化需要）
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# 保留应用入口
-keep class com.aiphone.assistant.MainActivity { *; }

# ---------- Shizuku ----------
# Shizuku 通过 binder 跨进程通信，混淆会断接口
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }

# ---------- AIDL ----------
-keep class com.aiphone.assistant.shell.IShellService { *; }

# ShellService 本身也要保。它是 IShellService.Stub 的实现，而 Shizuku
# 用户服务是用**字符串**类名跨进程传的：
#     ComponentName(pkg, ShellService::class.java.name)
# 那一步绕开了 AGP 自动生成的 aapt_rules（那份只 keep manifest 里声明的
# 组件，比如 AutoService / OverlayService / ScheduleReceiver），
# 所以类名一旦被混淆，bind 就会失败 —— 表现是副屏彻底用不了、
# 报错只说"服务连不上"。
-keep class com.aiphone.assistant.shell.ShellService { *; }

# ---------- Compose ----------
# Compose 库自带 consumer rules，这里只保留可能被反射用到的
-keep class * extends androidx.compose.runtime.Composable

# ---------- 调试时取消混淆（临时）----------
# 开发阶段 release 也不混淆，方便排查问题。
# 正式发布时把下面这行删掉，并充分测试。
-dontobfuscate
