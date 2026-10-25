package com.aiphone.assistant.data

import android.content.Context

/**
 * 设置的存取。
 *
 * 用 SharedPreferences 而不是 DataStore，理由很实际：这里只有十来个字段，
 * 同步读取对启动速度毫无影响，而 SharedPreferences 是零依赖、零协程的。
 * DataStore 要引入 flow 和挂起，对这个规模是过度设计。
 *
 * **API Key 加密落盘**（见 [SecretCrypto]）：AES-256-GCM，密钥在系统
 * Keystore 里、不可导出，应用沙箱里只有密文。root 过的机器上把
 * `settings.xml` 拷走也解不开 —— 它需要那台设备系统钥匙串里的密钥。
 * 老版本遗留的明文值会在读到后自动加密写回（迁移对用户无感）。
 */
class SettingsStore(
    context: Context,
    private val crypto: SecretCrypto = SecretCrypto.withKeystore(),
) {

    private val sp = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        baseUrl = sp.getString(KEY_BASE_URL, null) ?: DEFAULT.baseUrl,
        apiKey = loadApiKey(),
        modelName = sp.getString(KEY_MODEL, null) ?: DEFAULT.modelName,
        useFreeModel = sp.getBoolean(KEY_FREE_MODEL, DEFAULT.useFreeModel),
        thinking = ThinkingMode.fromId(sp.getString(KEY_THINKING, null)),
        customEffort = sp.getString(KEY_CUSTOM_EFFORT, DEFAULT.customEffort) ?: DEFAULT.customEffort,
        overlayBlur = sp.getBoolean(KEY_OVERLAY_BLUR, DEFAULT.overlayBlur),
        tapReview = TapReviewMode.fromId(sp.getString(KEY_TAP_REVIEW, null)),
        mode = OperationMode.fromId(sp.getString(KEY_MODE, null))
            // 老配置可能存过不可用的 ADB：归一化回无障碍，别把用户卡在灰界面
            .let { if (it.available) it else OperationMode.ACCESSIBILITY },
        maxSteps = sp.getInt(KEY_MAX_STEPS, DEFAULT.maxSteps),
        contextPolicy = ContextPolicy.fromId(sp.getString(KEY_CONTEXT_POLICY, null)),
        memoryEnabled = sp.getBoolean(KEY_MEMORY, DEFAULT.memoryEnabled),
        saveLogs = sp.getBoolean(KEY_SAVE_LOGS, DEFAULT.saveLogs),
        saveScreenshots = sp.getBoolean(KEY_SAVE_SHOTS, DEFAULT.saveScreenshots),
        localDialogDismiss = sp.getBoolean(KEY_LOCAL_DISMISS, DEFAULT.localDialogDismiss),
        autoCheckUpdate = sp.getBoolean(KEY_AUTO_UPDATE, DEFAULT.autoCheckUpdate),
        skippedUpdateCode = sp.getInt(KEY_SKIP_VERSION, DEFAULT.skippedUpdateCode),
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString(KEY_BASE_URL, s.baseUrl)
            .putString(KEY_API_KEY, crypto.seal(s.apiKey))
            .putString(KEY_MODEL, s.modelName)
            .putBoolean(KEY_FREE_MODEL, s.useFreeModel)
            .putString(KEY_THINKING, s.thinking.id)
            .putString(KEY_CUSTOM_EFFORT, s.customEffort)
            .putBoolean(KEY_OVERLAY_BLUR, s.overlayBlur)
            .putString(KEY_TAP_REVIEW, s.tapReview.id)
            .putString(KEY_MODE, s.mode.id)
            .putInt(KEY_MAX_STEPS, s.maxSteps)
            .putString(KEY_CONTEXT_POLICY, s.contextPolicy.id)
            .putBoolean(KEY_MEMORY, s.memoryEnabled)
            .putBoolean(KEY_SAVE_LOGS, s.saveLogs)
            .putBoolean(KEY_SAVE_SHOTS, s.saveScreenshots)
            .putBoolean(KEY_LOCAL_DISMISS, s.localDialogDismiss)
            .putBoolean(KEY_AUTO_UPDATE, s.autoCheckUpdate)
            .putInt(KEY_SKIP_VERSION, s.skippedUpdateCode)
            .apply()
    }

    /**
     * 读 API Key。顺手把老版本遗留的明文**就地加密写回**——
     * 迁移不需要用户做任何事，也不需要版本判断：没有前缀的就是明文。
     */
    private fun loadApiKey(): String {
        val raw = sp.getString(KEY_API_KEY, null) ?: return DEFAULT.apiKey
        if (raw.isEmpty() || crypto.isSealed(raw)) return crypto.unseal(raw)
        val sealed = crypto.seal(raw)
        if (sealed != raw) {
            sp.edit().putString(KEY_API_KEY, sealed).apply()
        }
        return raw
    }

    /** 只改一个字段也走完整保存，避免调用方忘了先 load */
    fun update(block: (AppSettings) -> AppSettings): AppSettings =
        block(load()).also { save(it) }

    private companion object {
        val DEFAULT = AppSettings()

        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model_name"
        const val KEY_FREE_MODEL = "use_free_model"
        const val KEY_THINKING = "thinking_mode"

        /** CUSTOM 档填的 reasoning_effort 原文（留空 = 不发） */
        const val KEY_CUSTOM_EFFORT = "custom_thinking_effort"

        /** 悬浮条背景模糊（默认关，见 AppSettings.overlayBlur 的说明） */
        const val KEY_OVERLAY_BLUR = "overlay_blur"

        /** 点击前复核档位（默认关，见 AppSettings.tapReview） */
        const val KEY_TAP_REVIEW = "tap_review"
        const val KEY_MODE = "operation_mode"
        // 曾经的全局「在副屏上操作」开关。现在是**逐条定时任务**的选项
        // （见 Schedule.useVirtualDisplay），这里不再读写 —— 老配置文件里
        // 残留的那个键会被忽略，不需要清理
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_CONTEXT_POLICY = "context_policy"
        const val KEY_MEMORY = "memory_enabled"
        const val KEY_SAVE_LOGS = "save_logs"
        const val KEY_SAVE_SHOTS = "save_screenshots"
        const val KEY_LOCAL_DISMISS = "local_dialog_dismiss"

        /** 启动时检查更新（默认开，见 AppSettings.autoCheckUpdate） */
        const val KEY_AUTO_UPDATE = "auto_check_update"

        /** 「跳过这个版本」记下的 versionCode（0 = 没跳过任何版本） */
        const val KEY_SKIP_VERSION = "skipped_update_code"
    }
}
