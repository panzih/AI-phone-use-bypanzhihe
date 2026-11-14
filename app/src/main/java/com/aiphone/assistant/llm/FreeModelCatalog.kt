package com.aiphone.assistant.llm

/**
 * 免费池里每个模型**真正能干什么**。
 *
 * ## 为什么需要这张表
 *
 * 网关的 `/inference/v1/models` 只给一个 id，别的一个字都没有。以前 App 就
 * 把这 9 个模型当成同一台机器用 —— 后果全是 400/500，而且报错看不出原因：
 *
 * | 差异 | 不管它会怎样 |
 * |---|---|
 * | **看不看图** | `nemotron-3.5-lightning` / `jev` / `beam` / `flint` 不吃图片，带截图就报错 |
 * | **能不能调思考强度** | `jev-1.13` 根本没有推理，发 `reasoning_effort` 会被拒 |
 * | **可接受的档位不一样** | `nemotron-3.5-super` 只认到 `xhigh`，发 `max` 回 `400 invalid_value`（网关会**校验**这个字段，不是忽略） |
 * | 上下文窗口 | 从 32K 到 1M 差 30 倍，直接影响什么时候该停 |
 *
 * 数据来源是上游插件的 `src/catalog.js`：它按 models.dev 的 opencode 条目 +
 * 自己的实测覆盖（比如 `deepseek-v4.1-flash-free` 实测吃下 102 万 token 的
 * prompt，所以窗口记 1M 而不是 models.dev 公布的 200K）。
 *
 * ## 为什么不干脆只留"能看图"的模型
 *
 * 免费池一共就 9 个，砍掉一半等于自断选择。正确做法是按模型能力调整请求：
 * 不支持看图就不发图、没有推理就不发档位 —— 用户照常能用，只是那一项自动关掉。
 */
data class FreeModelSpec(
    val id: String,
    /** 给人看的名字 */
    val label: String,
    /** 能不能吃截图 */
    val vision: Boolean,
    /** 有没有推理，以及能不能用 `reasoning_effort` 调 */
    val reasoning: Boolean,
    /** 上下文窗口（token） */
    val contextWindow: Int,
    /** 单次最大输出（token） */
    val maxOutput: Int,
    /**
     * 这个模型**自己声明**的思考档位，按由弱到强排列；`null` = 它没有可调档位。
     *
     * 不在这个表里的值不要发 —— 网关会拿它跟模型的声明比对，不认就 400。
     */
    val efforts: List<String>?,
) {
    /** picker 里那行小字：一眼看出这个模型收不收截图、窗口多大 */
    val summary: String
        get() = buildString {
            append(if (vision) "支持截图" else "纯文本")
            append(" · ")
            append(if (contextWindow >= 1_000_000) "${contextWindow / 1_000_000}M" else "${contextWindow / 1000}K")
            append(" 上下文")
            if (!reasoning) append(" · 无推理")
        }
}

object FreeModelCatalog {

    /** 档位强弱顺序。用来把"用户选的那档"映射到"模型认的那档"。 */
    private val LADDER = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /**
     * 当前免费池里会出现的模型（2026-11-10 从网关 `/inference/v1/models` 实测）。
     *
     * 只列网关真在服务的 id；一旦某个 id 下架，这里留着也不会被选中
     * （选择器用的是实时目录），所以宁可多留也不要让新 id 落进默认值。
     */
    private val EXACT: Map<String, FreeModelSpec> = listOf(
        FreeModelSpec(
            id = "deepseek-v4.1-flash-free",
            label = "DeepSeek V4.1 Flash",
            vision = true,
            reasoning = true,
            contextWindow = 200_000,
            maxOutput = 32_000,
            efforts = listOf("low", "high", "max"),
        ),
        FreeModelSpec(
            id = "glm-5.3-flash-free",
            label = "GLM 5.3 Flash",
            vision = true,
            reasoning = true,
            contextWindow = 200_000,
            maxOutput = 32_000,
            efforts = listOf("low", "high", "max"),
        ),
        FreeModelSpec(
            id = "mimo-v2.6-flash-free",
            label = "MiMo V2.6 Flash",
            vision = true,
            reasoning = true,
            contextWindow = 200_000,
            maxOutput = 32_000,
            efforts = null,
        ),
        FreeModelSpec(
            id = "minimax-m3.1-flash-free",
            label = "MiniMax M3.1 Flash",
            vision = true,
            reasoning = true,
            contextWindow = 1_000_000,
            maxOutput = 512_000,
            efforts = listOf("low", "medium", "high", "xhigh", "max"),
        ),
        FreeModelSpec(
            id = "nemotron-3.5-super-vl-free",
            label = "Nemotron 3.5 Super VL",
            vision = true,
            reasoning = true,
            contextWindow = 1_048_576,
            maxOutput = 131_072,
            efforts = listOf("none", "minimal", "low", "medium", "high", "xhigh"),
        ),
        FreeModelSpec(
            id = "nemotron-3.5-lightning-free",
            label = "Nemotron 3.5 Lightning",
            vision = false,
            reasoning = true,
            contextWindow = 262_144,
            maxOutput = 262_144,
            efforts = null,
        ),
        FreeModelSpec(
            id = "big-pickle",
            label = "Big Pickle",
            vision = false,
            reasoning = true,
            contextWindow = 200_000,
            maxOutput = 32_000,
            efforts = null,
        ),
        FreeModelSpec(
            id = "longcat-2.5-free",
            label = "LongCat 2.5",
            vision = false,
            reasoning = true,
            contextWindow = 1_000_000,
            maxOutput = 262_144,
            efforts = null,
        ),
        FreeModelSpec(
            id = "iceman-free",
            label = "Iceman",
            vision = false,
            reasoning = true,
            contextWindow = 262_144,
            maxOutput = 131_072,
            efforts = null,
        ),
        FreeModelSpec(
            id = "inkling-2-small-free",
            label = "Inkling 2 Small",
            vision = false,
            reasoning = true,
            contextWindow = 1_048_576,
            maxOutput = 131_072,
            efforts = listOf("none", "minimal", "low", "medium", "high", "max"),
        ),
        FreeModelSpec(
            id = "beam-free",
            label = "Beam",
            vision = false,
            reasoning = true,
            contextWindow = 1_048_576,
            maxOutput = 131_072,
            efforts = listOf("low", "medium", "high", "xhigh", "max"),
        ),
        FreeModelSpec(
            id = "flint-40b-free",
            label = "Flint 40B",
            vision = false,
            reasoning = true,
            contextWindow = 262_144,
            maxOutput = 65_536,
            efforts = listOf("low", "medium", "high", "xhigh", "max"),
        ),

        // ---- Kilo 免密免费池（2026-11-14 网关 /models 的 isFree 行）----
        FreeModelSpec("kilo-auto/free", "Kilo Auto Free", false, true, 256_000, 32_768, null),
        FreeModelSpec("openrouter/free", "OpenRouter Free Router", true, true, 200_000, 32_768, null),
        FreeModelSpec("nvidia/nemotron-3.5-super-vl:free", "Nemotron 3.5 Super VL", true, true, 1_000_000, 131_072, null),
        FreeModelSpec("stealth/glyph-cluster", "Glyph Cluster", false, true, 256_000, 256_000, null),
        FreeModelSpec("inclusionai/ling-3.2-flash:free", "Ling 3.2 Flash", true, true, 1_048_576, 262_144, null),
        FreeModelSpec("baidu/ernie-5.5-flash-preview:free", "ERNIE 5.5 Flash Preview", false, true, 1_024_000, 921_600, null),
        FreeModelSpec("liquid/lfm-3-1.7b:free", "LFM3 1.7B", false, true, 262_144, 8_192, null),
        FreeModelSpec("particle-ai/particle-1:free", "Particle-1", true, true, 1_048_576, 131_072, null),
        FreeModelSpec("dots-studio/dots-3-note-preview:free", "Dots3 Note Preview", true, true, 512_000, 460_800, null),
        FreeModelSpec("nvidia/nemotron-3.5-lightning:free", "Nemotron 3.5 Lightning", false, true, 1_000_000, 65_536, null),
        FreeModelSpec("cohere/north-mini-code:free", "North Mini Code", false, true, 256_000, 64_000, null),
        FreeModelSpec("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra", false, true, 1_000_000, 65_536, null),
        FreeModelSpec("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", "Nemotron 3 Nano Omni", true, true, 256_000, 65_536, null),
        FreeModelSpec("reflection/beam-free", "Beam 1.0", false, true, 1_048_576, 131_072, null),
    ).associateBy { it.id }

    /** 已知的免费模型 id，按上面的声明顺序 —— 实时目录拉不到时的兜底。 */
    fun ids(): List<String> = EXACT.keys.filter { !it.contains("/") }

    /** Kilo 免费池的 id（含 `org/model:free` 与两个自动路由）。 */
    fun kiloIds(): List<String> = EXACT.keys.filter { it.contains("/") }

    /**
     * 表里没有的 id 用这套保守值。
     *
     * 保守的意思是"当它纯文本、当它没有可调档位" —— 少发一个可选字段不会出错，
     * 多发一个不认识的字段会 400。反过来猜就糟糕了。
     */
    private val FALLBACK = FreeModelSpec(
        id = "",
        label = "",
        vision = false,
        reasoning = false,
        contextWindow = 131_072,
        maxOutput = 32_768,
        efforts = null,
    )

    /** 查一个模型的能力。认不出来的 id 走 [FALLBACK]。 */
    fun specFor(modelId: String): FreeModelSpec = EXACT[baseId(modelId)] ?: FALLBACK

    /**
     * 把用户选的那一档映射到**这个模型认的**那一档。
     *
     * - 模型没有可调档位 → `null`（一个字段都别发）
     * - 选的那档正好在名单里 → 原样发
     * - 不在名单里 → 降到**比它弱一级里最强的那个**（找不到就取最弱的）
     *
     * 为什么往弱里降：档位代表"愿意为这一屏多花多少推理"。用户选 MED 时，
     * 模型只有 low/high 两档，降成 low 是"照原意少花点"，升成 high 是
     * 自作主张加钱。宁可保守。
     */
    fun resolveEffort(modelId: String, wanted: String?): String? {
        val spec = specFor(modelId)
        if (!spec.reasoning) return null
        val menu = spec.efforts ?: return null
        val want = wanted?.trim()?.lowercase() ?: return null
        if (menu.contains(want)) return want
        val target = LADDER.indexOf(want)
        if (target < 0) return menu.first()
        // 从目标档往回找第一个模型认的
        for (i in target downTo 0) {
            val level = LADDER[i]
            if (menu.contains(level)) return level
        }
        return menu.first()
    }

    /** 这个模型能不能吃截图 */
    fun supportsImages(modelId: String): Boolean = specFor(modelId).vision

    // ------------------------------------------------------------------
    // Kilo 的思考控制
    // ------------------------------------------------------------------

    /**
     * Kilo 的关闭档能不能发 `reasoning.enabled=false`。
     *
     * liquid / stepfun / thinkingmachines 的端点强制思考（实测返回 400），
     * 两个自动路由会忽略关闭请求，所以这些模型不提供 OFF 档。
     */
    fun kiloCanDisableThinking(modelId: String): Boolean {
        val id = baseId(modelId)
        if (id.startsWith("liquid/") || id.startsWith("stepfun/") || id.startsWith("thinkingmachines/")) {
            return false
        }
        return id != "kilo-auto/free" && id != "openrouter/free"
    }

    /** 把 App 的档位映射到 Kilo 的 `reasoning.effort`（低/中/高），认不出就不发。 */
    fun kiloEffort(wanted: String?): String? = when (wanted?.trim()?.lowercase()) {
        "low", "minimal" -> "low"
        "medium" -> "medium"
        "high", "xhigh", "max" -> "high"
        else -> null
    }

    /** 去掉 `(level)` 这类后缀，和上游 baseModelId 一致 */
    private fun baseId(modelId: String): String =
        modelId.replace(Regex("\\([^()]+\\)\\s*$"), "").trim()
}
