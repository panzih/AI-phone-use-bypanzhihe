package com.aiphone.assistant.data

import android.content.Context

/**
 * 设置的存取。
 *
 * 用 SharedPreferences 而不是 DataStore，理由很实际：这里只有十来个字段，
 * 同步读取对启动速度毫无影响，而 SharedPreferences 是零依赖、零协程的。
 * DataStore 要引入 flow 和挂起，对这个规模是过度设计。
 *
 * 唯一的注意点是 API Key 存在明文里 —— 应用私有目录 + 沙箱隔离，
 * 对"自己用"这个定位够了。要更严的话应该上 EncryptedSharedPreferences，
 * 但那会引入 security-crypto 依赖，等真要分发时再说。
 */
class SettingsStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        baseUrl = sp.getString(KEY_BASE_URL, null) ?: DEFAULT.baseUrl,
        apiKey = sp.getString(KEY_API_KEY, null) ?: DEFAULT.apiKey,
        modelName = sp.getString(KEY_MODEL, null) ?: DEFAULT.modelName,
        thinking = ThinkingMode.fromId(sp.getString(KEY_THINKING, null)),
        mode = OperationMode.fromId(sp.getString(KEY_MODE, null)),
        maxSteps = sp.getInt(KEY_MAX_STEPS, DEFAULT.maxSteps),
        contextPolicy = ContextPolicy.fromId(sp.getString(KEY_CONTEXT_POLICY, null)),
        memoryEnabled = sp.getBoolean(KEY_MEMORY, DEFAULT.memoryEnabled),
        saveLogs = sp.getBoolean(KEY_SAVE_LOGS, DEFAULT.saveLogs),
        saveScreenshots = sp.getBoolean(KEY_SAVE_SHOTS, DEFAULT.saveScreenshots),
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString(KEY_BASE_URL, s.baseUrl)
            .putString(KEY_API_KEY, s.apiKey)
            .putString(KEY_MODEL, s.modelName)
            .putString(KEY_THINKING, s.thinking.id)
            .putString(KEY_MODE, s.mode.id)
            .putInt(KEY_MAX_STEPS, s.maxSteps)
            .putString(KEY_CONTEXT_POLICY, s.contextPolicy.id)
            .putBoolean(KEY_MEMORY, s.memoryEnabled)
            .putBoolean(KEY_SAVE_LOGS, s.saveLogs)
            .putBoolean(KEY_SAVE_SHOTS, s.saveScreenshots)
            .apply()
    }

    /** 只改一个字段也走完整保存，避免调用方忘了先 load */
    fun update(block: (AppSettings) -> AppSettings): AppSettings =
        block(load()).also { save(it) }

    private companion object {
        val DEFAULT = AppSettings()

        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model_name"
        const val KEY_THINKING = "thinking_mode"
        const val KEY_MODE = "operation_mode"
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_CONTEXT_POLICY = "context_policy"
        const val KEY_MEMORY = "memory_enabled"
        const val KEY_SAVE_LOGS = "save_logs"
        const val KEY_SAVE_SHOTS = "save_screenshots"
    }
}
