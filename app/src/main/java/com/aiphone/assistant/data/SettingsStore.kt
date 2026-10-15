package com.aiphone.assistant.data

import android.content.Context

class SettingsStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        baseUrl = sp.getString(KEY_BASE_URL, null) ?: DEFAULT.baseUrl,
        apiKey = sp.getString(KEY_API_KEY, null) ?: DEFAULT.apiKey,
        modelName = sp.getString(KEY_MODEL, null) ?: DEFAULT.modelName,
        useFreeModel = sp.getBoolean(KEY_FREE_MODEL, DEFAULT.useFreeModel),
        thinking = ThinkingMode.fromId(sp.getString(KEY_THINKING, null)),
        mode = OperationMode.fromId(sp.getString(KEY_MODE, null))
            .let { if (it.available) it else OperationMode.ACCESSIBILITY },
        maxSteps = sp.getInt(KEY_MAX_STEPS, DEFAULT.maxSteps),
        contextPolicy = ContextPolicy.fromId(sp.getString(KEY_CONTEXT_POLICY, null)),
        memoryEnabled = sp.getBoolean(KEY_MEMORY, DEFAULT.memoryEnabled),
        saveLogs = sp.getBoolean(KEY_SAVE_LOGS, DEFAULT.saveLogs),
        saveScreenshots = sp.getBoolean(KEY_SAVE_SHOTS, DEFAULT.saveScreenshots),
        localDialogDismiss = sp.getBoolean(KEY_LOCAL_DISMISS, DEFAULT.localDialogDismiss),
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString(KEY_BASE_URL, s.baseUrl)
            .putString(KEY_API_KEY, s.apiKey)
            .putString(KEY_MODEL, s.modelName)
            .putBoolean(KEY_FREE_MODEL, s.useFreeModel)
            .putString(KEY_THINKING, s.thinking.id)
            .putString(KEY_MODE, s.mode.id)
            .putInt(KEY_MAX_STEPS, s.maxSteps)
            .putString(KEY_CONTEXT_POLICY, s.contextPolicy.id)
            .putBoolean(KEY_MEMORY, s.memoryEnabled)
            .putBoolean(KEY_SAVE_LOGS, s.saveLogs)
            .putBoolean(KEY_SAVE_SHOTS, s.saveScreenshots)
            .putBoolean(KEY_LOCAL_DISMISS, s.localDialogDismiss)
            .apply()
    }

    fun update(block: (AppSettings) -> AppSettings): AppSettings =
        block(load()).also { save(it) }

    private companion object {
        val DEFAULT = AppSettings()

        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model_name"
        const val KEY_FREE_MODEL = "use_free_model"
        const val KEY_THINKING = "thinking_mode"
        const val KEY_MODE = "operation_mode"
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_CONTEXT_POLICY = "context_policy"
        const val KEY_MEMORY = "memory_enabled"
        const val KEY_SAVE_LOGS = "save_logs"
        const val KEY_SAVE_SHOTS = "save_screenshots"
        const val KEY_LOCAL_DISMISS = "local_dialog_dismiss"
    }
}
