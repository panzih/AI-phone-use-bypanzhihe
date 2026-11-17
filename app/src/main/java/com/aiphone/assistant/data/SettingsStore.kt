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
        freeLane = FreeLane.fromId(sp.getString(KEY_FREE_LANE, null)),
        recordingEntry = sp.getBoolean(KEY_RECORDING_ENTRY, DEFAULT.recordingEntry),
        voiceInput = sp.getBoolean(KEY_VOICE_INPUT, DEFAULT.voiceInput),
        onboardingDone = sp.getBoolean(KEY_ONBOARDING_DONE, DEFAULT.onboardingDone),
        freeLaneNoticeAccepted = sp.getBoolean(KEY_FREE_NOTICE, DEFAULT.freeLaneNoticeAccepted),
        allowPublicCleartext = sp.getBoolean(KEY_ALLOW_PUBLIC_CLEARTEXT, DEFAULT.allowPublicCleartext),
        thinking = ThinkingMode.fromId(sp.getString(KEY_THINKING, null)),
        customEffort = sp.getString(KEY_CUSTOM_EFFORT, DEFAULT.customEffort) ?: DEFAULT.customEffort,
        overlayBlur = sp.getBoolean(KEY_OVERLAY_BLUR, DEFAULT.overlayBlur),
        tapReview = TapReviewMode.fromId(sp.getString(KEY_TAP_REVIEW, null)),
        allowHighRiskActions = sp.getBoolean(KEY_ALLOW_HIGH_RISK, DEFAULT.allowHighRiskActions),
        allowBrowserEval = sp.getBoolean(KEY_ALLOW_BROWSER_EVAL, DEFAULT.allowBrowserEval),
        mode = OperationMode.fromId(sp.getString(KEY_MODE, null))
            // 老配置可能存过不可用的 ADB：归一化回无障碍，别把用户卡在灰界面
            .let { if (it.available) it else OperationMode.ACCESSIBILITY },
        maxSteps = sp.getInt(KEY_MAX_STEPS, DEFAULT.maxSteps),
        contextPolicy = ContextPolicy.fromId(sp.getString(KEY_CONTEXT_POLICY, null)),
        memoryEnabled = sp.getBoolean(KEY_MEMORY, DEFAULT.memoryEnabled),
        saveLogs = sp.getBoolean(KEY_SAVE_LOGS, DEFAULT.saveLogs),
        saveScreenshots = sp.getBoolean(KEY_SAVE_SHOTS, DEFAULT.saveScreenshots),
        localDialogDismiss = sp.getBoolean(KEY_LOCAL_DISMISS, DEFAULT.localDialogDismiss),
    ).let { migrateDefaults(it) }

    /**
     * 一次性把**还是旧默认值**的配置项升级到 0.17.0 的新默认值。
     *
     * 为什么需要它：`save()` 会把每一项都写进 SharedPreferences，所以老用户
     * 盘上一定存着"旧默认"（30 步 / 不记忆 / 不复核 / 语音关）。只改
     * data class 的默认值对这种配置**完全不起作用** —— 装上新版一看还是老值，
     * 会以为没改。
     *
     * 判据是"等于旧默认"：用户自己调过的值不动（比如他设了 50 步，就不是 30）。
     * 只跑一次（[KEY_DEFAULTS_REV]），之后用户再改就是用户自己的选择。
     */
    private fun migrateDefaults(s: AppSettings): AppSettings {
        if (sp.getInt(KEY_DEFAULTS_REV, 0) >= DEFAULTS_REV) return s
        val upgraded = s.copy(
            maxSteps = if (s.maxSteps == OLD_DEFAULT_MAX_STEPS) 99 else s.maxSteps,
            // 这两项旧默认是 false，没法区分"没动过"和"故意关掉" ——
            // 一次性打开，之后用户再关就是用户自己的选择。
            memoryEnabled = true,
            voiceInput = true,
            tapReview = if (s.tapReview == TapReviewMode.OFF) TapReviewMode.COORDINATES else s.tapReview,
        )
        // **必须落盘**：只在内存里改的话，下次启动还是盘上的旧值，
        // 而 rev 已经推进到 1，迁移不会再跑 —— 等于白改。
        save(upgraded)
        sp.edit().putInt(KEY_DEFAULTS_REV, DEFAULTS_REV).apply()
        return upgraded
    }

    /**
     * 保存设置。
     *
     * @return 给用户看的警告；null = 一切正常。目前只有一种：
     *         API Key 加密失败 —— 此时**不把明文写进盘**（ROADMAP §2），
     *         之前存过的值保持原样，并把这件事明示给用户
     */
    fun save(s: AppSettings): String? {
        val sealedKey = crypto.seal(s.apiKey)
        val warning = if (s.apiKey.isNotEmpty() && sealedKey == null) {
            android.util.Log.w(TAG, "API Key 加密失败，本次保存不写 Key（不静默存明文）")
            "API Key 加密失败：本次保存没有写入 Key，之前存过的值保持不变。请重启应用后重试。"
        } else null
        sp.edit()
            .putString(KEY_BASE_URL, s.baseUrl)
            .putString(
                KEY_API_KEY,
                // 加密失败时保住之前存过的值（含老明文，等下次启动迁移重试）；
                // 从没存过就写空串 —— 输入框里的 Key 不上盘
                sealedKey ?: sp.getString(KEY_API_KEY, null) ?: "",
            )
            .putString(KEY_MODEL, s.modelName)
            .putBoolean(KEY_FREE_MODEL, s.useFreeModel)
            .putString(KEY_FREE_LANE, s.freeLane.id)
            .putBoolean(KEY_FREE_NOTICE, s.freeLaneNoticeAccepted)
            .putBoolean(KEY_ALLOW_PUBLIC_CLEARTEXT, s.allowPublicCleartext)
            .putString(KEY_THINKING, s.thinking.id)
            .putString(KEY_CUSTOM_EFFORT, s.customEffort)
            .putBoolean(KEY_OVERLAY_BLUR, s.overlayBlur)
            .putString(KEY_TAP_REVIEW, s.tapReview.id)
            .putBoolean(KEY_ALLOW_HIGH_RISK, s.allowHighRiskActions)
            .putBoolean(KEY_ALLOW_BROWSER_EVAL, s.allowBrowserEval)
            .putBoolean(KEY_RECORDING_ENTRY, s.recordingEntry)
            .putBoolean(KEY_VOICE_INPUT, s.voiceInput)
            .putBoolean(KEY_ONBOARDING_DONE, s.onboardingDone)
            .putString(KEY_MODE, s.mode.id)
            .putInt(KEY_MAX_STEPS, s.maxSteps)
            .putString(KEY_CONTEXT_POLICY, s.contextPolicy.id)
            .putBoolean(KEY_MEMORY, s.memoryEnabled)
            .putBoolean(KEY_SAVE_LOGS, s.saveLogs)
            .putBoolean(KEY_SAVE_SHOTS, s.saveScreenshots)
            .putBoolean(KEY_LOCAL_DISMISS, s.localDialogDismiss)
            // 任何一次保存都说明"这份配置已经是新版写的"：打上迁移标记，
            // 免得用户在新版里主动关掉语音/记忆之后，下次启动又被迁移打开。
            .putInt(KEY_DEFAULTS_REV, DEFAULTS_REV)
            .apply()
        return warning
    }

    /**
     * 读 API Key。顺手把老版本遗留的明文**就地加密写回**——
     * 迁移不需要用户做任何事，也不需要版本判断：没有前缀的就是明文。
     * 加密失败就等下次启动再试，不动盘。
     */
    private fun loadApiKey(): String {
        val raw = sp.getString(KEY_API_KEY, null) ?: return DEFAULT.apiKey
        if (raw.isEmpty() || crypto.isSealed(raw)) return crypto.unseal(raw)
        val sealed = crypto.seal(raw) ?: return raw
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

        const val TAG = "SettingsStore"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model_name"
        const val KEY_FREE_MODEL = "use_free_model"

        /** 免费通道选哪一条（见 AppSettings.freeLane） */
        const val KEY_FREE_LANE = "free_lane"

        /** 免费通道风险说明是否已确认（见 AppSettings.freeLaneNoticeAccepted） */
        const val KEY_FREE_NOTICE = "free_lane_notice_accepted"

        /** 是否允许公网明文 HTTP（默认关，见 AppSettings.allowPublicCleartext） */
        const val KEY_ALLOW_PUBLIC_CLEARTEXT = "allow_public_cleartext"
        const val KEY_THINKING = "thinking_mode"

        /** CUSTOM 档填的 reasoning_effort 原文（留空 = 不发） */
        const val KEY_CUSTOM_EFFORT = "custom_thinking_effort"

        /** 悬浮条背景模糊（默认关，见 AppSettings.overlayBlur 的说明） */
        const val KEY_OVERLAY_BLUR = "overlay_blur"

        /** 点击前复核档位（默认关，见 AppSettings.tapReview） */
        const val KEY_TAP_REVIEW = "tap_review"

        /** 是否允许高风险操作（默认关，见 AppSettings.allowHighRiskActions） */
        const val KEY_ALLOW_HIGH_RISK = "allow_high_risk_actions"

        /** 是否允许 browser_eval（默认关，见 AppSettings.allowBrowserEval） */
        const val KEY_ALLOW_BROWSER_EVAL = "allow_browser_eval"

        /** 「操作记录」入口开关（默认关，见 AppSettings.recordingEntry） */
        const val KEY_RECORDING_ENTRY = "recording_entry"

        /** 语音输入开关（0.17.0 起默认开，见 AppSettings.voiceInput） */
        const val KEY_VOICE_INPUT = "voice_input"

        /** 首次安装的权限引导是否看过（见 AppSettings.onboardingDone） */
        const val KEY_ONBOARDING_DONE = "onboarding_done"
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

        /**
         * 默认值迁移版本。0 = 还没迁移过。
         *
         * 0.17.0 改了几项默认值（步数 30→99、开记忆、开语音、复核改中档），
         * 老的 `save()` 把旧默认写进过盘，靠 [migrateDefaults] 一次性抬上来。
         */
        const val KEY_DEFAULTS_REV = "defaults_revision"
        const val DEFAULTS_REV = 1

        /** 0.17.0 之前的默认最大步数（迁移时用来判断"用户没动过"） */
        const val OLD_DEFAULT_MAX_STEPS = 30
    }
}
