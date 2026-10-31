package com.aiphone.assistant.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
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
import com.aiphone.assistant.ui.design.Banner
import com.aiphone.assistant.ui.design.ButtonRow
import com.aiphone.assistant.ui.design.ButtonVariant
import com.aiphone.assistant.ui.design.GroupCard
import com.aiphone.assistant.ui.design.GroupDivider
import com.aiphone.assistant.ui.design.HintText
import com.aiphone.assistant.ui.design.LoadingRow
import com.aiphone.assistant.ui.design.SectionNote
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.SegmentedControl
import com.aiphone.assistant.ui.design.SettingRow
import com.aiphone.assistant.ui.design.Size
import com.aiphone.assistant.ui.design.SliderSetting
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatBlock
import com.aiphone.assistant.ui.design.StatTile
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.SwitchRow
import com.aiphone.assistant.ui.design.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * 结构分五段，越往下越「重」：
 *
 *   概览        通道 / 模型 / 步数，一眼看到现在是什么状态
 *   模型        接口地址 / API Key（打码） / 模型 / 思考强度
 *   行为        步数上限 / 上下文策略 / 点击复核 / 高风险闸门
 *   记忆与数据  记忆 / 日志 / 截图 / 悬浮条 / 端侧弹窗
 *   开发者      红字警告 + 不可撤销的动作
 *   日志        统计 / 导出 / 删除
 *
 * ## 为什么用「卡片分组」而不是分割线
 *
 * 每组一张卡：卡与卡之间 24dp，卡内行与行之间 1px 分割线 ——
 * **距离**本身就说明了归属，不需要读标题。
 *
 * ## 关于 API Key
 *
 * 默认打码，点眼睛才明文显示。一开始做成普通输入框（明文常显），
 * 那在公共场合很糟糕 —— 别人扫一眼就把 Key 看走了。
 *
 * ## 关于「开发者设置」
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
    /** 看副屏画面。手动切到副屏之后主屏会回桌面，用户就看不到 AI 在那块屏上干什么了 */
    onLookAtVd: () -> Unit,
    onClearContext: () -> Unit,
    onExportLogs: () -> Unit,
    onDeleteLogs: () -> Unit,
    onCompressMemory: () -> Unit,
    onClearMemory: () -> Unit,
) {
    val s = state.settings
    val snackbar = remember { SnackbarHostState() }
    var showClearMemory by remember { mutableStateOf(false) }

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it) }
    }

    AppScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = "改动即时保存，没有「保存」按钮",
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
            // ================= 概览 =================
            item {
                SettingsOverview(
                    s = s,
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                )
            }

            // ================= 模型 =================
            item {
                SectionTitle(
                    text = stringResource(R.string.settings_section_model),
                    icon = Icons.Filled.Tune,
                    trailing = {
                        if (s.useFreeModel) {
                            StatusPill(text = "免费通道", tone = Tone.Positive)
                        }
                    },
                )
            }
            item { ModelSection(s, onSettingsChange) }

            // ================= 行为 =================
            item { SectionTitle(text = "行为", icon = Icons.Filled.Build) }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
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

            // ================= 记忆与数据 =================
            item { SectionTitle(text = "记忆与数据", icon = Icons.Filled.Memory) }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_memory_enabled),
                        subtitle = stringResource(R.string.settings_memory_enabled_desc),
                        checked = s.memoryEnabled,
                        onCheckedChange = { onSettingsChange(s.copy(memoryEnabled = it)) },
                    )
                    GroupDivider()
                    ActionRow(
                        title = stringResource(R.string.settings_memory_compress),
                        subtitle = state.memoryStats.ifBlank {
                            stringResource(R.string.settings_memory_compress_desc)
                        },
                        onClick = onCompressMemory,
                    )
                    GroupDivider()
                    ActionRow(
                        title = stringResource(R.string.settings_memory_clear),
                        subtitle = stringResource(R.string.settings_memory_clear_desc),
                        destructive = true,
                        onClick = { showClearMemory = true },
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

            // ================= 开发者 =================
            item { SectionTitle(text = "开发者", icon = Icons.Filled.Description) }
            item {
                Banner(
                    text = stringResource(R.string.settings_developer_warning),
                    tone = Tone.Negative,
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs),
                )
            }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                    ActionRow(
                        title = stringResource(R.string.settings_clear_context),
                        subtitle = stringResource(R.string.settings_clear_context_sub),
                        showChevron = true,
                        onClick = onClearContext,
                    )
                    GroupDivider()
                    ActionRow(
                        title = "运行副屏探针",
                        subtitle = "检查副屏能否读到控件树，结果写进日志",
                        showChevron = true,
                        onClick = onProbeVirtualDisplay,
                    )
                    GroupDivider()
                    ActionRow(
                        title = stringResource(R.string.settings_vd_mirror),
                        subtitle = stringResource(R.string.settings_vd_mirror_sub),
                        showChevron = true,
                        onClick = onLookAtVd,
                    )
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_allow_browser_eval),
                        subtitle = stringResource(R.string.settings_allow_browser_eval_desc),
                        checked = s.allowBrowserEval,
                        onCheckedChange = { onSettingsChange(s.copy(allowBrowserEval = it)) },
                    )
                }
            }

            // ================= 日志 =================
            item { SectionTitle(text = stringResource(R.string.settings_export_logs), icon = Icons.Filled.Description) }
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

    if (showClearMemory) {
        AppAlertDialog(
            title = stringResource(R.string.settings_memory_clear_confirm_title),
            body = stringResource(R.string.settings_memory_clear_confirm_body),
            confirmText = stringResource(R.string.settings_memory_clear_confirm),
            confirmDestructive = true,
            onConfirm = {
                showClearMemory = false
                onClearMemory()
            },
            onDismiss = { showClearMemory = false },
        )
    }
}

// ----------------------------------------------------------------------
// 概览
// ----------------------------------------------------------------------

/**
 * 设置页顶部的三格概览。
 *
 * 用户进设置通常只想确认一件事：「我现在到底在用哪个模型、会不会花太多」。
 * 把这三个数摆在最上面，比让他往下翻三屏去找输入框快得多。
 */
@Composable
private fun SettingsOverview(s: AppSettings, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        StatTile(
            label = "通道",
            value = if (s.useFreeModel) "免费" else "自定义",
            tone = if (s.useFreeModel) Tone.Positive else Tone.Neutral,
            modifier = Modifier.weight(0.8f),
        )
        StatTile(
            label = "模型",
            value = s.modelName.ifBlank { "未设置" },
            modifier = Modifier.weight(1.4f),
        )
        StatTile(
            label = "步数上限",
            value = if (s.maxSteps <= 0) "不限" else "${s.maxSteps}",
            modifier = Modifier.weight(0.8f),
        )
    }
}

// ----------------------------------------------------------------------
// 模型
// ----------------------------------------------------------------------

/**
 * 模型设置。
 *
 * 两条通道（自定义 / 免费）用分段控件切换，而不是两个并排按钮：
 * 并排的两个按钮看起来像「两个动作」，分段控件才表达「这是同一件事的两种取值」。
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

    // 免费模型的实时列表：打开选择器时用接口读
    var freeFetching by remember { mutableStateOf(false) }
    var freeLive by remember { mutableStateOf<List<FreeModel>?>(null) }
    var freeFetchError by remember { mutableStateOf<String?>(null) }

    fun loadFreeModels(force: Boolean = false) {
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

    val customModeLabel = stringResource(R.string.settings_model_mode_custom)
    val freeModeLabel = stringResource(R.string.settings_model_mode_free)

    Column(modifier = Modifier.padding(horizontal = Space.lg)) {
        GroupCard {
            Column(modifier = Modifier.padding(Space.lg)) {
                SegmentedControl(
                    options = listOf(false, true),
                    selected = s.useFreeModel,
                    label = { if (it) freeModeLabel else customModeLabel },
                    onSelect = { free ->
                        when {
                            !free -> onChange(s.copy(useFreeModel = false))
                            s.freeLaneNoticeAccepted -> onChange(s.copy(useFreeModel = true))
                            else -> showFreeNotice = true
                        }
                    },
                )

                Spacer(Modifier.height(Space.lg))

                if (!s.useFreeModel) {
                    // ---------- 自定义模式 ----------
                    AppTextField(
                        value = s.baseUrl,
                        onValueChange = { onChange(s.copy(baseUrl = it)) },
                        label = stringResource(R.string.settings_model_provider),
                    )

                    // 公网明文 HTTP 的常驻警告：判据与 LlmClient 共用 EndpointSafety
                    EndpointSafety.cleartextWarning(s.baseUrl)?.let { warning ->
                        Spacer(Modifier.height(Space.sm))
                        Banner(text = warning, tone = Tone.Negative)
                        Spacer(Modifier.height(Space.sm))
                        SwitchRow(
                            title = stringResource(R.string.settings_allow_public_cleartext),
                            subtitle = stringResource(R.string.settings_allow_public_cleartext_desc),
                            checked = s.allowPublicCleartext,
                            onCheckedChange = { onChange(s.copy(allowPublicCleartext = it)) },
                        )
                    }

                    Spacer(Modifier.height(Space.md))

                    AppTextField(
                        value = s.apiKey,
                        onValueChange = { onChange(s.copy(apiKey = it)) },
                        label = stringResource(R.string.settings_model_apikey),
                        visualTransformation = if (keyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = stringResource(
                                        if (keyVisible) R.string.settings_apikey_hide
                                        else R.string.settings_apikey_show
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
                    Banner(
                        text = stringResource(R.string.settings_model_free_hint),
                        tone = Tone.Info,
                    )
                    Spacer(Modifier.height(Space.md))
                }

                AppTextField(
                    value = s.modelName,
                    onValueChange = { onChange(s.copy(modelName = it)) },
                    label = stringResource(R.string.settings_model_name),
                )

                // 免费通道：把当前这个模型的能力摊在明面上
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

                Spacer(Modifier.height(Space.md))

                if (!s.useFreeModel) {
                    AppButton(
                        text = stringResource(R.string.settings_model_fetch),
                        onClick = {
                            if (fetching) return@AppButton
                            fetching = true
                            fetchError = null
                            models = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        ModelListFetcher.fetch(
                                            s.baseUrl,
                                            s.apiKey,
                                            s.allowPublicCleartext,
                                        )
                                    }
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
                    AppButton(
                        text = stringResource(R.string.settings_model_free_pick),
                        onClick = { openFreePicker() },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            GroupDivider()
            ThinkingModeSlider(
                current = s.thinking,
                freeLane = s.useFreeModel,
                baseUrl = s.baseUrl,
                customEffort = s.customEffort,
                onChange = { onChange(s.copy(thinking = it)) },
                onCustomEffortChange = { onChange(s.copy(customEffort = it)) },
            )
        }
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

/** 列表里的可选项（选模型用）：选中态用胶囊，而不是整行变蓝 */
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
 * 这份名单只在读不到时顶上，按 **2026-10-27** 网关 /inference/v1/models
 * 的实时目录校对过（当时免费池 9 个，全部收录）。
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
 * 内置名单里有的直接用现成的显示名；没见过的现拼一个顺眼的：
 * 去掉 `-free` 后缀、按连字符断词、首字母大写。
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
 * **每一档下面直接显示「接口参数」** —— 档位不是一句形容，而是具体往
 * 接口里发什么（`thinking.type` / `reasoning_effort`）。
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
    val options = ThinkingMode.levelsFor(baseUrl, freeLane)
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
 * 用滑块而不是下拉，是为了让「这是一个从严格到宽松的连续选择」一眼可见。
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
 * 所以 100 这个刻度不代表「100 步」，而是「不设上限」。存的时候写 0，
 * 全工程统一用 `maxSteps <= 0` 表示不限。
 */
private const val STEPS_UNLIMITED = 100

/**
 * 点击前复核的三档滑块。
 *
 * 和上下文策略一样用滑块而不是开关：三档是一个「越来越严、也越来越慢」
 * 的连续选择，光看「开/关」看不出「只复核坐标点击」这个折中档。
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
 * 拖拽过程中**不落盘**：`onValueChange` 在手指移动时每帧都触发，
 * 每一帧写一次 SharedPreferences 是浪费。松手才存。
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
        steps = STEPS_UNLIMITED - STEPS_MIN - 1,
        onValueChange = { draft = it },
        onValueChangeFinished = {
            val v = draft.toInt()
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
    // 导出前风险提示：包里有对话、截图、定时任务，可能含验证码/支付页面。
    var showExportNotice by remember { mutableStateOf(false) }

    if (showExportNotice) {
        AppAlertDialog(
            title = stringResource(R.string.export_notice_title),
            body = stringResource(R.string.export_notice_body),
            confirmText = stringResource(R.string.export_notice_confirm),
            onConfirm = {
                showExportNotice = false
                onExportLogs()
            },
            onDismiss = { showExportNotice = false },
            dismissText = stringResource(R.string.schedule_cancel),
        )
    }

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
                onClick = { showExportNotice = true },
                variant = ButtonVariant.Secondary,
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
