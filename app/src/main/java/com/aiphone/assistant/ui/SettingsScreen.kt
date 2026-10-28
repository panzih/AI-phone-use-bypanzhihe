package com.aiphone.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.ContextPolicy
import com.aiphone.assistant.data.TapReviewMode
import com.aiphone.assistant.data.ThinkingMode
import com.aiphone.assistant.llm.EndpointSafety
import com.aiphone.assistant.llm.FreeModelCatalog
import com.aiphone.assistant.llm.ModelListFetcher
import com.aiphone.assistant.ui.design.ActionRow
import com.aiphone.assistant.ui.design.AppAlertDialog
import com.aiphone.assistant.ui.design.AppButton
import com.aiphone.assistant.ui.design.AppScaffold
import com.aiphone.assistant.ui.design.AppTextField
import com.aiphone.assistant.ui.design.AppTheme
import com.aiphone.assistant.ui.design.Banner
import com.aiphone.assistant.ui.design.ButtonRow
import com.aiphone.assistant.ui.design.ButtonVariant
import com.aiphone.assistant.ui.design.GroupCard
import com.aiphone.assistant.ui.design.GroupDivider
import com.aiphone.assistant.ui.design.HintText
import com.aiphone.assistant.ui.design.LoadingRow
import com.aiphone.assistant.ui.design.Radius
import com.aiphone.assistant.ui.design.SectionNote
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.SettingRow
import com.aiphone.assistant.ui.design.Size
import com.aiphone.assistant.ui.design.SliderSetting
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatBlock
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.SwitchRow
import com.aiphone.assistant.ui.design.Tone
import com.aiphone.assistant.ui.design.interactiveSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * 结构分四段，越往下越"重"：
 *
 *   模型        接口地址 / API Key（打码） / 模型 / 思考强度
 *   更多设置    清空上下文 / 副屏探针与镜像 / 步数上限 / 上下文策略 / 记忆 / 日志 / 复核
 *   开发者设置  红字警告 + 不可撤销的开关
 *   日志        统计 / 导出 / 删除
 *
 * ## 为什么用"卡片分组"而不是分割线
 *
 * 之前整页是一条竖线串起来的长列表，用户只能靠灰字标题猜哪几行是一组。
 * 现在每组一张卡：卡与卡之间 24dp，卡内行与行之间 1px 分割线 ——
 * **距离**本身就说明了归属，不需要读标题。
 *
 * ## 关于 API Key
 *
 * 手稿上写了眼睛图标，所以这里是**默认打码**的，点眼睛才明文显示。
 * 一开始做成普通输入框（明文常显），那在公共场合很糟糕 ——
 * 别人扫一眼就把 Key 看走了。
 *
 * ## 关于"开发者设置"
 *
 * 清空上下文、清空日志都是不可撤销的，所以它们统一放在红字警告下面，
 * 而不是散在日常设置里。
 */
@Composable
fun SettingsScreen(
    state: MainUiState,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onProbeVirtualDisplay: () -> Unit,
    /**
     * 看副屏画面。手动切到副屏之后主屏会回桌面，用户就看不到 AI 在那块屏上
     * 干什么了 —— 这个入口把镜像页拉起来。
     */
    onLookAtVd: () -> Unit,
    onClearContext: () -> Unit,
    onExportLogs: () -> Unit,
    onDeleteLogs: () -> Unit,
) {
    val s = state.settings
    val snackbar = remember { SnackbarHostState() }

    // 一次性提示：显示完不清状态也没关系，内容一样时不会重复弹
    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it) }
    }

    AppScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_back),
        snackbarHostState = snackbar,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(bottom = Space.huge),
        ) {
            // ================= 模型 =================
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.settings_section_model), modifier = Modifier.weight(1f))
                    if (s.useFreeModel) {
                        StatusPill(
                            text = "免费通道",
                            tone = Tone.Positive,
                            modifier = Modifier.padding(end = Space.lg),
                        )
                    }
                }
            }
            item { ModelSection(s, onSettingsChange) }

            // ================= 更多设置 =================
            item { SectionTitle(stringResource(R.string.settings_section_developer)) }
            item {
                DeveloperWarning(
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)
                )
            }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                    ActionRow(
                        title = stringResource(R.string.settings_clear_context),
                        subtitle = stringResource(R.string.settings_clear_context_sub),
                        onClick = onClearContext,
                    )
                    GroupDivider()
                    ActionRow(
                        title = "运行副屏探针",
                        subtitle = "检查副屏能否读到控件树，结果写进日志",
                        onClick = onProbeVirtualDisplay,
                    )
                    GroupDivider()
                    ActionRow(
                        title = stringResource(R.string.settings_vd_mirror),
                        subtitle = stringResource(R.string.settings_vd_mirror_sub),
                        onClick = onLookAtVd,
                    )
                }
            }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                    StepsSliderRow(
                        maxSteps = s.maxSteps,
                        onChange = { onSettingsChange(s.copy(maxSteps = it)) },
                    )
                    GroupDivider()
                    ContextPolicySlider(
                        policy = s.contextPolicy,
                        onChange = { onSettingsChange(s.copy(contextPolicy = it)) },
                    )
                    GroupDivider()
                    TapReviewSlider(
                        mode = s.tapReview,
                        onChange = { onSettingsChange(s.copy(tapReview = it)) },
                    )
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_allow_high_risk),
                        subtitle = stringResource(R.string.settings_allow_high_risk_desc),
                        checked = s.allowHighRiskActions,
                        onCheckedChange = { onSettingsChange(s.copy(allowHighRiskActions = it)) },
                    )
                }
            }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_memory_enabled),
                        subtitle = stringResource(R.string.settings_memory_enabled_desc),
                        checked = s.memoryEnabled,
                        onCheckedChange = { onSettingsChange(s.copy(memoryEnabled = it)) },
                    )
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_save_logs),
                        subtitle = stringResource(R.string.settings_save_logs_desc),
                        checked = s.saveLogs,
                        onCheckedChange = { onSettingsChange(s.copy(saveLogs = it)) },
                    )
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_save_screenshots),
                        subtitle = stringResource(R.string.settings_save_screenshots_desc),
                        checked = s.saveScreenshots,
                        onCheckedChange = { onSettingsChange(s.copy(saveScreenshots = it)) },
                    )
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_overlay_blur),
                        subtitle = stringResource(R.string.settings_overlay_blur_desc),
                        checked = s.overlayBlur,
                        onCheckedChange = { onSettingsChange(s.copy(overlayBlur = it)) },
                    )
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_local_dismiss),
                        subtitle = stringResource(R.string.settings_local_dismiss_desc),
                        checked = s.localDialogDismiss,
                        onCheckedChange = { onSettingsChange(s.copy(localDialogDismiss = it)) },
                    )
                }
            }

            // ================= 日志 =================
            item { SectionTitle(stringResource(R.string.settings_export_logs)) }
            item { SectionNote(stringResource(R.string.settings_export_logs_desc)) }
            item {
                LogSection(
                    state = state,
                    onExportLogs = onExportLogs,
                    onDeleteLogs = onDeleteLogs,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// 模型
// ----------------------------------------------------------------------

/**
 * 模型设置。
 *
 * 两条通道（自定义 / 免费）用**分段控件**切换，而不是两个并排按钮：
 * 并排的两个按钮看起来像"两个动作"，分段控件才表达"这是同一件事的两种取值"。
 */
@Composable
private fun ModelSection(
    s: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 模型列表对话框状态
    var fetching by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var showFreePicker by remember { mutableStateOf(false) }
    // 首次切到免费通道时的风险告知。确认之前不落库、不切换。
    var showFreeNotice by remember { mutableStateOf(false) }

    // 免费模型的实时列表：打开选择器时**用接口读**
    // （null 且不在读 = 读失败，退回内置名单；错误原因单独记着）
    var freeFetching by remember { mutableStateOf(false) }
    var freeLive by remember { mutableStateOf<List<FreeModel>?>(null) }
    var freeFetchError by remember { mutableStateOf<String?>(null) }

    fun loadFreeModels(force: Boolean = false) {
        // 读过一次就用缓存：同一个界面里反复开关不必重复联网
        if (!force && (freeLive != null || freeFetching)) return
        freeFetching = true
        freeFetchError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ModelListFetcher.fetchFree() }
            }
            freeFetching = false
            result.onSuccess { ids -> freeLive = ids.map { FreeModel(it, freeLabel(it)) } }
                .onFailure { e -> freeFetchError = e.message ?: e.javaClass.simpleName }
        }
    }

    fun openFreePicker() {
        showFreePicker = true
        loadFreeModels()
    }

    Column(modifier = Modifier.padding(horizontal = Space.lg)) {
        ChannelToggle(
            useFree = s.useFreeModel,
            onChange = { free ->
                when {
                    !free -> onChange(s.copy(useFreeModel = false))
                    s.freeLaneNoticeAccepted -> onChange(s.copy(useFreeModel = true))
                    else -> showFreeNotice = true
                }
            },
        )

        Spacer(Modifier.height(Space.md))

        if (!s.useFreeModel) {
            // ---------- 自定义模式 ----------
            AppTextField(
                value = s.baseUrl,
                onValueChange = { onChange(s.copy(baseUrl = it)) },
                label = stringResource(R.string.settings_model_provider),
            )

            // 公网明文 HTTP 的常驻警告（评审报告第 3 节）：
            // LlmClient 里只打日志 —— 用户不会看日志，这里钉在输入框下面。
            // 判据与 LlmClient 共用 EndpointSafety，不会出现两处说法不一致。
            EndpointSafety.cleartextWarning(s.baseUrl)?.let { warning ->
                Spacer(Modifier.height(Space.sm))
                Banner(
                    text = stringResource(R.string.settings_model_public_http_warning),
                    tone = Tone.Negative,
                )
            }

            Spacer(Modifier.height(Space.md))

            AppTextField(
                value = s.apiKey,
                onValueChange = { onChange(s.copy(apiKey = it)) },
                label = stringResource(R.string.settings_model_apikey),
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (keyVisible) R.string.settings_apikey_hide else R.string.settings_apikey_show
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Size.iconMd),
                        )
                    }
                },
            )

            Spacer(Modifier.height(Space.md))
        } else {
            // ---------- 免费模式 ----------
            HintText(stringResource(R.string.settings_model_free_hint))
            Spacer(Modifier.height(Space.sm))
        }

        AppTextField(
            value = s.modelName,
            onValueChange = { onChange(s.copy(modelName = it)) },
            label = stringResource(R.string.settings_model_name),
        )

        // 免费通道：把当前这个模型的能力摊在明面上。
        // 它决定了"AI 能不能看截图"和"思考档位调得动多少"，不该藏着。
        if (s.useFreeModel) {
            val spec = FreeModelCatalog.specFor(s.modelName)
            Spacer(Modifier.height(Space.xs))
            HintText(
                buildString {
                    append(spec.label.ifBlank { s.modelName })
                    append(" · ")
                    append(spec.summary)
                },
            )
        }

        Spacer(Modifier.height(Space.sm))

        if (!s.useFreeModel) {
            // 获取模型列表（自定义模式）：失败就地给原因，不给红框大字
            AppButton(
                text = stringResource(R.string.settings_model_fetch),
                onClick = {
                    if (fetching) return@AppButton
                    fetching = true
                    fetchError = null
                    models = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { ModelListFetcher.fetch(s.baseUrl, s.apiKey) }
                        }
                        fetching = false
                        result.onSuccess { list -> models = list }
                            .onFailure { e -> fetchError = e.message ?: e.javaClass.simpleName }
                    }
                },
                variant = ButtonVariant.Secondary,
                loading = fetching,
                enabled = !fetching,
                modifier = Modifier.fillMaxWidth(),
            )
            if (fetching) {
                Spacer(Modifier.height(Space.sm))
                LoadingRow(stringResource(R.string.settings_model_fetching))
            }
            fetchError?.let { err ->
                Spacer(Modifier.height(Space.sm))
                Banner(
                    text = stringResource(R.string.settings_model_fetch_failed, err),
                    tone = Tone.Negative,
                )
            }
        } else {
            // 免费模式：点开后**用接口实时读取**可选模型（失败退回内置名单）
            AppButton(
                text = stringResource(R.string.settings_model_free_pick),
                onClick = { openFreePicker() },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 自定义模式：拉回来的模型列表
        models?.let { list ->
            AppAlertDialog(
                title = stringResource(R.string.settings_model_dialog_title),
                onDismiss = { models = null },
                dismissText = stringResource(R.string.schedule_cancel),
                content = {
                    if (list.isEmpty()) {
                        HintText(stringResource(R.string.settings_model_empty))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(list) { m ->
                                SelectableItem(
                                    title = m,
                                    selected = m == s.modelName,
                                    onClick = {
                                        onChange(s.copy(modelName = m))
                                        models = null
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }

        // 免费模式：实时列表（用接口读；失败退回内置名单）
        if (showFreePicker) {
            val live = freeLive
            val shown = live ?: freeModels
            AppAlertDialog(
                title = stringResource(R.string.settings_model_free_pick),
                onDismiss = { showFreePicker = false },
                dismissText = stringResource(R.string.schedule_cancel),
                confirmText = stringResource(R.string.settings_model_free_reload),
                onConfirm = { loadFreeModels(force = true) },
                content = {
                    // 列表是从哪来的，如实写出来（这就是"用接口读取"的可见面）
                    when {
                        freeFetching -> LoadingRow("正在从接口读取模型列表 …")
                        live != null -> HintText("已从接口实时读取（${live.size} 个）")
                        freeFetchError != null -> Banner(
                            text = "接口读取失败：$freeFetchError（下面用内置名单）",
                            tone = Tone.Warning,
                        )
                    }
                    Spacer(Modifier.height(Space.sm))
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(shown) { m ->
                            val spec = FreeModelCatalog.specFor(m.id)
                            SelectableItem(
                                title = spec.label.ifBlank { m.label },
                                // 一行小字说清"这个模型收不收图、窗口多大" ——
                                // 以前 9 个模型在选单里长得一模一样，选错了要等 400 才知道
                                subtitle = spec.summary,
                                selected = m.id == s.modelName,
                                onClick = {
                                    onChange(s.copy(modelName = m.id))
                                    showFreePicker = false
                                },
                            )
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(Space.sm))

        ThinkingModeSlider(
            current = s.thinking,
            freeLane = s.useFreeModel,
            baseUrl = s.baseUrl,
            customEffort = s.customEffort,
            onChange = { onChange(s.copy(thinking = it)) },
            onCustomEffortChange = { onChange(s.copy(customEffort = it)) },
        )

        if (showFreeNotice) {
            AppAlertDialog(
                title = stringResource(R.string.settings_model_free_notice_title),
                body = stringResource(R.string.settings_model_free_notice_body),
                onDismiss = { showFreeNotice = false },
                confirmText = stringResource(R.string.settings_model_free_notice_accept),
                onConfirm = {
                    showFreeNotice = false
                    onChange(s.copy(useFreeModel = true, freeLaneNoticeAccepted = true))
                },
                dismissText = stringResource(R.string.settings_model_free_notice_decline),
            )
        }
    }
}

/**
 * 分段控件：同一件事的两个取值。
 *
 * 选中项用卡片色浮起来（不是变蓝），这样它读起来是"当前这个"，
 * 而不是"推荐你点这个"。
 */
@Composable
private fun ChannelToggle(useFree: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.accents.sunken, RoundedCornerShape(Radius.md))
            .padding(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Segment(
            text = stringResource(R.string.settings_model_mode_custom),
            selected = !useFree,
            onClick = { onChange(false) },
            modifier = Modifier.weight(1f),
        )
        Segment(
            text = stringResource(R.string.settings_model_mode_free),
            selected = useFree,
            onClick = { onChange(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Segment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .interactiveSurface(
                shape = RoundedCornerShape(Radius.sm),
                onClick = onClick,
            )
            .background(
                if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(Radius.sm),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 列表里的可选项（选模型用）：选中态用主色的圆点，而不是整行变蓝 */
@Composable
private fun SelectableItem(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    selected: Boolean = false,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            if (selected) {
                StatusPill(text = "当前", tone = Tone.Positive, dot = false)
            }
        },
    )
}

/**
 * 免费模式的内置模型名单（**只当兜底**）。
 *
 * 正常路径是打开选择器时用接口实时读取（[ModelListFetcher.fetchFree]）——
 * 这份名单只在读不到时顶上，按 **2026-10-27** 网关 /inference/v1/models 的
 * 实时目录校对过（当时免费池 9 个，全部收录），并剔除了三类：
 *  - 已下架：deepseek-v4-flash-free（已升级为 v4.1）、union-alpha、
 *    ling-3.0-flash-fin-free、minimax-m3.1-flash-free（10-27 核对时已不在目录里）
 *  - 中国大陆地域锁（网关直接 403 RegionError）：muse-spark-*、cat-jam-free，
 *    且 muse-spark 走的是另一套 /responses 端点，国内手机也用不了
 *  - 测试占位：test
 * 下面这些都是走标准 /chat/completions 的模型。
 */
private val freeModels = listOf(
    FreeModel("deepseek-v4.1-flash-free", "DeepSeek V4.1 Flash"),
    FreeModel("glm-5.3-flash-free", "GLM 5.3 Flash"),
    FreeModel("mimo-v2.6-flash-free", "MiMo V2.6 Flash"),
    FreeModel("nemotron-3.5-super-vl-free", "Nemotron 3.5 Super VL"),
    FreeModel("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning"),
    FreeModel("flint-40b-free", "Flint 40B"),
    FreeModel("beam-free", "Beam"),
    FreeModel("jev-1.13-free", "Jev 1.13"),
    FreeModel("big-pickle", "Big Pickle"),
)

/**
 * 接口读回来的只有 id（如 `glm-5.3-flash-free`）。
 *
 * 内置名单里有的直接用现成的显示名；没见过的（新上架模型）现拼一个顺眼的：
 * 去掉 `-free` 后缀、按连字符断词、首字母大写。少数本来就是缩写的词
 * （GLM、VL …）按全大写显示，不然会变成"Glm""Vl"。
 */
private fun freeLabel(id: String): String =
    freeModels.firstOrNull { it.id == id }?.label
        ?: id.removeSuffix("-free")
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                if (part.lowercase() in ACRONYMS) part.uppercase()
                else part.replaceFirstChar { c -> c.uppercase() }
            }

/** 拼显示名时保持全大写的词 */
private val ACRONYMS = setOf("glm", "vl", "ai", "ui", "api", "ocr", "tts")

private data class FreeModel(val id: String, val label: String)

/**
 * 思考模式滑块 —— 5 档连续：OFF → LOW → HIGH → MAX → 默认。
 *
 * 没有开关了：滑块本身就是选择器。最右一档「默认」什么参数都不发，
 * 让服务端自己挑。
 *
 * **每一档下面直接显示「接口参数」** —— 档位不是一句形容，而是具体往
 * 接口里发什么（`thinking.type` / `reasoning_effort`）。这在免费通道会
 * 显示成不一样的话（OFF 不发），因为那才是真实请求（见 [ThinkingMode.apiFor]）。
 */
@Composable
private fun ThinkingModeSlider(
    current: ThinkingMode,
    freeLane: Boolean,
    baseUrl: String,
    customEffort: String,
    onChange: (ThinkingMode) -> Unit,
    onCustomEffortChange: (String) -> Unit,
) {
    // 档位**随模型/服务商变**：DeepSeek 没有 medium，免费网关没有 max。
    // 当前选的那档不在列表里（比如刚从 DeepSeek 换到别的）就落到最右的「默认」，
    // 绝不发一个这个服务商不认的 effort 过去（那是 400，不是"档位失效"）。
    val options = ThinkingMode.levelsFor(baseUrl, freeLane)
    // 当前档不在这台服务商的列表里时落到**「默认」**而不是最右一格：
    // 最右现在是「自定义」，落过去等于把上次为别的服务商填的 effort 原样发出去
    val index = options.indexOf(current).takeIf { it >= 0 }
        ?: options.indexOf(ThinkingMode.DEFAULT).coerceAtLeast(0)
    var draft by remember(index) { mutableFloatStateOf(index.toFloat()) }
    val picked = options[draft.toInt().coerceIn(0, options.lastIndex)]

    SliderSetting(
        title = stringResource(R.string.settings_model_thinking),
        valueLabel = picked.label,
        note = picked.note,
        fact = stringResource(R.string.settings_model_thinking_api, picked.apiFor(freeLane, customEffort)),
        value = draft,
        valueRange = 0f..options.lastIndex.toFloat(),
        steps = (options.size - 2).coerceAtLeast(0),
        onValueChange = { draft = it },
        onValueChangeFinished = {
            onChange(options[draft.toInt().coerceIn(0, options.lastIndex)])
        },
        extra = if (picked == ThinkingMode.CUSTOM) {
            {
                // 选了「自定义」才出现的输入框：用户想发什么 effort 就发什么。
                // 留空 = 什么都不发（这一条在提示里写死，因为它是唯一"空"的含义）。
                AppTextField(
                    value = customEffort,
                    onValueChange = onCustomEffortChange,
                    placeholder = stringResource(R.string.settings_thinking_custom_hint),
                    supportingText = if (freeLane) {
                        stringResource(R.string.settings_thinking_custom_free_note)
                    } else {
                        stringResource(R.string.settings_thinking_custom_note)
                    },
                    modifier = Modifier.padding(top = Space.sm),
                )
            }
        } else {
            null
        },
    )
}

// ----------------------------------------------------------------------
// 上下文策略
// ----------------------------------------------------------------------

/**
 * 上下文策略的三档滑块。
 *
 * 只有三个位置，从左到右：每次重置 / 超过 24 小时 / 不限。
 * 用滑块而不是下拉，是为了让"这是一个从严格到宽松的连续选择"这件事
 * 一眼可见 —— 下拉框里三个平级的选项看不出这种关系。
 */
@Composable
private fun ContextPolicySlider(
    policy: ContextPolicy,
    onChange: (ContextPolicy) -> Unit,
) {
    val options = ContextPolicy.entries
    val current = options.indexOf(policy).coerceAtLeast(0)
    var draft by remember(current) { mutableFloatStateOf(current.toFloat()) }

    SliderSetting(
        title = stringResource(R.string.settings_context_policy),
        valueLabel = options[draft.toInt().coerceIn(0, options.lastIndex)].label,
        note = options[draft.toInt().coerceIn(0, options.lastIndex)].note,
        value = draft,
        valueRange = 0f..options.lastIndex.toFloat(),
        // 三档之间只有两个间隔点
        steps = (options.size - 2).coerceAtLeast(0),
        onValueChange = { draft = it },
        onValueChangeFinished = {
            onChange(options[draft.toInt().coerceIn(0, options.lastIndex)])
        },
    )
}

// ----------------------------------------------------------------------
// 最大步数
// ----------------------------------------------------------------------

/** 滑块左端 = 最少步数 */
private const val STEPS_MIN = 10

/**
 * 滑块右端 = **不限**。
 *
 * 所以 100 这个刻度不代表"100 步"，而是"不设上限"。存的时候写 0，
 * 全工程统一用 `maxSteps <= 0` 表示不限（见 AppSettings.maxSteps）。
 */
private const val STEPS_UNLIMITED = 100

/**
 * 点击前复核的三档滑块。
 *
 * 和上下文策略一样用滑块而不是开关：三档是一个「越来越严、也越来越慢」
 * 的连续选择，光看"开/关"看不出"只复核坐标点击"这个折中档。
 */
@Composable
private fun TapReviewSlider(
    mode: TapReviewMode,
    onChange: (TapReviewMode) -> Unit,
) {
    val options = TapReviewMode.entries
    val current = options.indexOf(mode).coerceAtLeast(0)
    var draft by remember(current) { mutableFloatStateOf(current.toFloat()) }

    SliderSetting(
        title = stringResource(R.string.settings_tap_review),
        valueLabel = options[draft.toInt().coerceIn(0, options.lastIndex)].label,
        note = options[draft.toInt().coerceIn(0, options.lastIndex)].note,
        value = draft,
        valueRange = 0f..options.lastIndex.toFloat(),
        steps = (options.size - 2).coerceAtLeast(0),
        onValueChange = { draft = it },
        onValueChangeFinished = {
            onChange(options[draft.toInt().coerceIn(0, options.lastIndex)])
        },
    )
}

/**
 * 最大步数滑块。
 *
 * 原来是个 10/20/30/50/100 的下拉框 —— 那等于替用户决定了"可以选哪几个值"。
 * 改成滑块之后可以连续调，最右边直接是"不限"。
 *
 * 拖拽过程中**不落盘**：`onValueChange` 在手指移动时每帧都触发，
 * 每一帧写一次 SharedPreferences 是浪费。松手（`onValueChangeFinished`）才存。
 */
@Composable
private fun StepsSliderRow(
    maxSteps: Int,
    onChange: (Int) -> Unit,
) {
    val stored = if (maxSteps <= 0) STEPS_UNLIMITED
    else maxSteps.coerceIn(STEPS_MIN, STEPS_UNLIMITED - 1)

    var draft by remember(stored) { mutableFloatStateOf(stored.toFloat()) }

    SliderSetting(
        title = stringResource(R.string.settings_max_steps),
        valueLabel = if (draft >= STEPS_UNLIMITED) {
            stringResource(R.string.settings_steps_unlimited)
        } else {
            stringResource(R.string.settings_steps_value, draft.toInt())
        },
        note = stringResource(R.string.settings_max_steps_desc),
        value = draft,
        valueRange = STEPS_MIN.toFloat()..STEPS_UNLIMITED.toFloat(),
        // 整数刻度：(100-10+1) 个取值 → 中间 89 个分隔点
        steps = STEPS_UNLIMITED - STEPS_MIN - 1,
        onValueChange = { draft = it },
        onValueChangeFinished = {
            val v = draft.toInt()
            // 满格 = 不限（存 0）
            onChange(if (v >= STEPS_UNLIMITED) 0 else v)
        },
    )
}

// ----------------------------------------------------------------------
// 日志
// ----------------------------------------------------------------------

@Composable
private fun LogSection(
    state: MainUiState,
    onExportLogs: () -> Unit,
    onDeleteLogs: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AppAlertDialog(
            title = stringResource(R.string.log_delete_confirm_title),
            body = stringResource(R.string.log_delete_confirm_body),
            confirmText = stringResource(R.string.log_delete),
            confirmDestructive = true,
            onConfirm = {
                confirmDelete = false
                onDeleteLogs()
            },
            onDismiss = { confirmDelete = false },
            dismissText = stringResource(R.string.schedule_cancel),
        )
    }

    Column(modifier = Modifier.padding(horizontal = Space.lg)) {
        // 统计：让用户知道"有没有东西可导"
        StatBlock(
            lines = buildList {
                if (state.logStats.isNotBlank()) add(state.logStats)
                if (state.memoryStats.isNotBlank()) add(state.memoryStats)
                if (state.autoCapStats.isNotBlank()) add(state.autoCapStats)
            },
        )

        Spacer(Modifier.height(Space.md))

        ButtonRow {
            AppButton(
                text = stringResource(R.string.settings_export_logs_button),
                onClick = onExportLogs,
                variant = ButtonVariant.Secondary,
                // 打包期间禁用 + 换文案。日志大时这一步是分钟级的，
                // 不给交代的话用户只会觉得按钮失灵
                enabled = !state.exporting,
                loading = state.exporting,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = stringResource(R.string.log_delete),
                onClick = { confirmDelete = true },
                variant = ButtonVariant.Danger,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.exporting) {
            Spacer(Modifier.height(Space.sm))
            LoadingRow(stringResource(R.string.settings_export_working))
        }

        Spacer(Modifier.height(Space.lg))
    }
}

// ----------------------------------------------------------------------
// 开发者警告
// ----------------------------------------------------------------------

/**
 * 那行红字。
 *
 * 手稿上专门用红笔写了一遍，所以这里也做成醒目的：
 * 危险色容器 + 警告图标，而不是一行小灰字。
 */
@Composable
private fun DeveloperWarning(modifier: Modifier = Modifier) {
    Banner(
        text = stringResource(R.string.settings_developer_warning),
        tone = Tone.Negative,
        modifier = modifier,
    )
}
