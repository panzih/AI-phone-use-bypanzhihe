package com.aiphone.assistant.data

import android.content.Context

class SettingsStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        baseUrl = sp.getString(KEY_BASE_URL, null) ?: DEFAULT.baseUrl,
        apiKey = sp.getString(KEY_API_KEY, null) ?: DEFAULT.apiKey,
        modelName = sp.getString(KEY_MODEL, null) ?: DEFAULT.modelName,
        detail = sp.getString(KEY_DETAIL, null) ?: DEFAULT.detail,
        mode = OperationMode.fromId(sp.getString(KEY_MODE, null)),
        maxSteps = sp.getInt(KEY_MAX_STEPS, DEFAULT.maxSteps),
        autoClearMinutes = sp.getInt(KEY_AUTO_CLEAR, DEFAULT.autoClearMinutes),
        memoryEnabled = sp.getBoolean(KEY_MEMORY, DEFAULT.memoryEnabled),
        keepMemory = sp.getBoolean(KEY_KEEP_MEMORY, DEFAULT.keepMemory),
        saveLogs = sp.getBoolean(KEY_SAVE_LOGS, DEFAULT.saveLogs),
        saveScreenshots = sp.getBoolean(KEY_SAVE_SHOTS, DEFAULT.saveScreenshots),
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString(KEY_BASE_URL, s.baseUrl)
            .putString(KEY_API_KEY, s.apiKey)
            .putString(KEY_MODEL, s.modelName)
            .putString(KEY_DETAIL, s.detail)
            .putString(KEY_MODE, s.mode.id)
            .putInt(KEY_MAX_STEPS, s.maxSteps)
            .putInt(KEY_AUTO_CLEAR, s.autoClearMinutes)
            .putBoolean(KEY_MEMORY, s.memoryEnabled)
            .putBoolean(KEY_KEEP_MEMORY, s.keepMemory)
            .putBoolean(KEY_SAVE_LOGS, s.saveLogs)
            .putBoolean(KEY_SAVE_SHOTS, s.saveScreenshots)
            .apply()
    }

    fun update(block: (AppSettings) -> AppSettings): AppSettings =
        block(load()).also { save(it) }

    private companion object {
        val DEFAULT = AppSettings()
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model_name"
        const val KEY_DETAIL = "detail"
        const val KEY_MODE = "operation_mode"
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_AUTO_CLEAR = "auto_clear_minutes"
        const val KEY_MEMORY = "memory_enabled"
        const val KEY_KEEP_MEMORY = "keep_memory"
        const val KEY_SAVE_LOGS = "save_logs"
        const val KEY_SAVE_SHOTS = "save_screenshots"
    }
}
