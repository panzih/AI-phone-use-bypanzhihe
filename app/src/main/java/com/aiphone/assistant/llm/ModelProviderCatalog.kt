package com.aiphone.assistant.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * 模型供应商目录。
 *
 * 数据来自 DeepSeek Harness 的渠道注册表（`src/providers/registry.json`），
 * 只保留本应用能直连的 OpenAI 兼容渠道（`format = openai-chat`），
 * 打包在 `assets/model_providers.json`。
 *
 * 它的作用是让用户不用记接口地址：选一个供应商就把 baseUrl 和常用模型带进来，
 * 模型本身仍以「获取模型列表」的实时结果为准。
 */
object ModelProviderCatalog {

    private const val TAG = "ModelProviderCatalog"
    private const val ASSET = "model_providers.json"

    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val category: String,
        val models: List<String>,
    )

    @Volatile
    private var cache: List<Provider>? = null

    fun load(context: Context): List<Provider> =
        cache ?: synchronized(this) {
            cache ?: parse(readAsset(context)).also { cache = it }
        }

    /** 当前接口地址对应的供应商；手改过地址就不属于任何一家，返回 null。 */
    fun find(providers: List<Provider>, baseUrl: String): Provider? {
        val want = normalize(baseUrl)
        if (want.isEmpty()) return null
        return providers.firstOrNull { normalize(it.baseUrl) == want }
    }

    internal fun parse(json: String): List<Provider> = runCatching {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val baseUrl = row.optString("baseUrl").trim()
                if (baseUrl.isEmpty()) continue
                val models = buildList {
                    val entries = row.optJSONArray("models") ?: return@buildList
                    for (j in 0 until entries.length()) {
                        val id = entries.optString(j, "")
                        if (id.isNotBlank()) add(id)
                    }
                }
                add(
                    Provider(
                        id = row.optString("id"),
                        name = row.optString("name").ifBlank { row.optString("id") },
                        baseUrl = baseUrl,
                        category = row.optString("category"),
                        models = models,
                    )
                )
            }
        }
    }.getOrElse { e ->
        Log.w(TAG, "解析供应商目录失败", e)
        emptyList()
    }

    private fun readAsset(context: Context): String =
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { e ->
            Log.w(TAG, "读取供应商目录失败", e)
            "[]"
        }

    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()
}
