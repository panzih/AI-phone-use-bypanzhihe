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
     * 当前免费池里会出现的模型。
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
            contextWindow = 1_048_576,
            maxOutput = 384_000,
            efforts = listOf("none", "low", "high", "max"),
        ),
        FreeModelSpec(
            id = "glm-5.3-flash-free",
            label = "GLM 5.3 Flash",
            vision = true,
            reasoning = true,
            contextWindow = 1_048_576,
            maxOutput = 131_072,
            // 声明里没有 none，而且实测 OFF 档也照样花推理 token，所以不提供
            efforts = listOf("low", "high", "max"),
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
            contextWindow = 131_072,
            maxOutput = 32_768,
            // 上游没给它声明档位菜单 → 不发 reasoning_effort（猜一个可能 400）
            efforts = null,
        ),
        FreeModelSpec(
            id = "mimo-v2.6-flash-free",
            label = "MiMo V2.6 Flash",
            vision = true,
            reasoning = true,
            contextWindow = 1_048_576,
            maxOutput = 131_072,
            efforts = null,
        ),
        FreeModelSpec(
            id = "big-pickle",
            label = "Big Pickle",
            vision = true,
            reasoning = true,
            contextWindow = 262_144,
            maxOutput = 131_072,
            efforts = null,
        ),
        FreeModelSpec(
            id = "jev-1.13-free",
            label = "Jev 1.13",
            vision = false,
            // 这个模型没有推理，发档位字段会被拒
            reasoning = false,
            contextWindow = 32_768,
            maxOutput = 4_096,
            efforts = null,
        ),
        FreeModelSpec(
            id = "beam-free",
            label = "Beam",
            vision = false,
            reasoning = true,
            contextWindow = 131_072,
            maxOutput = 32_768,
            efforts = null,
        ),
        FreeModelSpec(
            id = "flint-40b-free",
            label = "Flint 40B",
            vision = false,
            reasoning = true,
            contextWindow = 131_072,
            maxOutput = 32_768,
            efforts = null,
        ),
    ).associateBy { it.id }

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

    /** 去掉 `(level)` 这类后缀，和上游 baseModelId 一致 */
    private fun baseId(modelId: String): String =
        modelId.replace(Regex("\\([^()]+\\)\\s*$"), "").trim()
}
