package com.aiphone.assistant.ui

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.ContextPolicy
import com.aiphone.assistant.data.TapReviewMode
import com.aiphone.assistant.data.ThinkingMode
import com.aiphone.assistant.llm.EndpointSafety
import com.aiphone.assistant.llm.FreeLaneWire
import com.aiphone.assistant.llm.FreeModelCatalog
import com.aiphone.assistant.llm.ModelListFetcher
import com.aiphone.assistant.llm.ModelProviderCatalog
import com.aiphone.assistant.ui.design.ActionRow
import com.aiphone.assistant.ui.design.AppAlertDialog
import com.aiphone.assistant.ui.design.AppButton
import com.aiphone.assistant.ui.design.AppScaffold
import com.aiphone.assistant.ui.design.AppTextField
import com.aiphone.assistant.ui.design.Banner
import com.aiphone.assistant.ui.design.ButtonRow
import com.aiphone.assistant.ui.design.ButtonVariant
import com.aiphone.assistant.ui.design.DropdownRow
import com.aiphone.assistant.ui.design.GroupCard
import com.aiphone.assistant.ui.design.GroupDivider
import com.aiphone.assistant.ui.design.HintText
import com.aiphone.assistant.ui.design.LoadingRow
import com.aiphone.assistant.ui.design.Radius
import com.aiphone.assistant.ui.design.SectionNote
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.Size
import com.aiphone.assistant.ui.design.SliderSetting
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatBlock
import com.aiphone.assistant.ui.design.StatTile
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
                    GroupDivider()
                    // ROADMAP §3：0.9.23 下架「操作记录」时承诺的实验开关，
                    // 现在补上 —— 打开后抽屉里出现入口，输入框说「操作记录」也进得去
                    SwitchRow(
                        title = "操作记录（实验）",
                        subtitle = "录制操作并让 AI 学成可复用的技能。功能还在打磨，默认关闭。",
                        checked = s.recordingEntry,
                        onCheckedChange = { onSettingsChange(s.copy(recordingEntry = it)) },
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
 * 交互照搬 DeepSeek Harness 的模型页：**先选供应商，再选模型**。
 *
 *   - 供应商是下拉，选中自动填好接口地址和该渠道的常用模型；
 *   - 下拉最前面的「免费模型（无需 Key）」切到 opencode.ai 公共免费池；
 *   - 模型名是带下拉的输入框，既能从列表里点，也能手敲（兼容自建网关）；
 *   - 「获取模型列表」放在最下面，点一次就用当前地址+Key（免费通道不要 Key）
 *     拉实时目录。
 */
@Composable
private fun ModelSection(
    s: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val providers = remember { ModelProviderCatalog.load(context) }

    // 把免费池做成供应商下拉里的一项（排在最前）。选中它就切到免密通道：
    // 不发 Key，请求走 opencode.ai，模型清单从网关实时读取。
    val freeProvider = remember {
        ModelProviderCatalog.Provider(
            id = "our-free-model",
            name = "免费模型（无需 Key）",
            baseUrl = FreeLaneWire.BASE_URL,
            category = "free",
            models = FreeModelCatalog.ids(),
        )
    }

    val selectedProvider = if (s.useFreeModel) freeProvider
    else ModelProviderCatalog.find(providers, s.baseUrl)

    var fetching by remember { mutableStateOf(false) }
    var models by remember {
        mutableStateOf(
            if (s.useFreeModel) FreeModelCatalog.ids() else selectedProvider?.models ?: emptyList()
        )
    }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var showFreeNotice by remember { mutableStateOf(false) }

    fun enableFree(accepted: Boolean) {
        val known = FreeModelCatalog.ids()
        models = known
        onChange(
            s.copy(
                useFreeModel = true,
                freeLaneNoticeAccepted = s.freeLaneNoticeAccepted || accepted,
                modelName = s.modelName.takeIf { it in known } ?: known.first(),
            )
        )
    }

    // 切到免费通道时拉一次实时目录；失败就保留内置名单，不打断操作。
    LaunchedEffect(s.useFreeModel) {
        if (!s.useFreeModel) return@LaunchedEffect
        if (models.isEmpty()) models = FreeModelCatalog.ids()
        val result = withContext(Dispatchers.IO) { runCatching { ModelListFetcher.fetchFree() } }
        result.onSuccess { if (it.isNotEmpty()) models = it }
    }

    Column(modifier = Modifier.padding(horizontal = Space.lg)) {
        GroupCard {
            Column(modifier = Modifier.padding(Space.lg)) {
                DropdownRow(
                    title = "模型供应商",
                    subtitle = "选中后自动填入接口地址和常用模型",
                    current = selectedProvider,
                    options = listOf<ModelProviderCatalog.Provider?>(null, freeProvider) + providers,
                    optionLabel = { it?.name ?: "自定义接口" },
                    onSelect = { provider ->
                        when {
                            provider == null -> {
                                models = emptyList()
                                onChange(s.copy(useFreeModel = false))
                            }
                            provider.id == freeProvider.id -> {
                                // 免费通道首次开启前先过风险说明；已确认过就直接切。
                                if (s.freeLaneNoticeAccepted) enableFree(false) else showFreeNotice = true
                            }
                            else -> {
                                models = provider.models
                                onChange(
                                    s.copy(
                                        useFreeModel = false,
                                        baseUrl = provider.baseUrl,
                                        modelName = provider.models.firstOrNull() ?: s.modelName,
                                    )
                                )
                            }
                        }
                    },
                )

                Spacer(Modifier.height(Space.md))

                if (s.useFreeModel) {
                    HintText(
                        "免费通道不需要填接口地址和 API Key，请求直连 opencode.ai 的公共免费池。" +
                            "屏幕控件树、截图和输入的文字会发送到该第三方网关。"
                    )
                } else {
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
                }

                Spacer(Modifier.height(Space.md))

                ModelNameField(
                    value = s.modelName,
                    onValueChange = { onChange(s.copy(modelName = it)) },
                    models = models,
                )

                Spacer(Modifier.height(Space.md))

                AppButton(
                    text = stringResource(R.string.settings_model_fetch),
                    onClick = {
                        if (fetching) return@AppButton
                        fetching = true
                        fetchError = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (s.useFreeModel) {
                                        ModelListFetcher.fetchFree()
                                    } else {
                                        ModelListFetcher.fetch(
                                            s.baseUrl,
                                            s.apiKey,
                                            s.allowPublicCleartext,
                                        )
                                    }
                                }
                            }
                            fetching = false
                            result.onSuccess { list ->
                                models = list
                                if (s.modelName.isBlank() || s.modelName !in list) {
                                    list.firstOrNull()?.let { onChange(s.copy(modelName = it)) }
                                }
                            }.onFailure { e ->
                                fetchError = e.message ?: e.javaClass.simpleName
                            }
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
                Spacer(Modifier.height(Space.sm))
                HintText(
                    when {
                        s.useFreeModel && models.isEmpty() ->
                            "免费池暂时没读到模型；点「获取模型列表」重试"
                        s.useFreeModel ->
                            "免费池已载入 ${models.size} 个模型，点模型名右侧箭头选择，也可直接输入"
                        models.isEmpty() ->
                            "点「获取模型列表」后，模型名右侧会出现可选项"
                        else ->
                            "已载入 ${models.size} 个模型，点模型名右侧箭头选择，也可直接输入"
                    }
                )
            }

            GroupDivider()
            ThinkingModeSlider(
                current = s.thinking,
                freeLane = s.useFreeModel,
                baseUrl = if (s.useFreeModel) FreeLaneWire.BASE_URL else s.baseUrl,
                customEffort = s.customEffort,
                onChange = { onChange(s.copy(thinking = it)) },
                onCustomEffortChange = { onChange(s.copy(customEffort = it)) },
            )
        }
    }

    // 免费通道首次开启前的风险说明。确认后同时写入 freeLaneNoticeAccepted，
    // 避免启动时 MainActivity 再弹一次。
    if (showFreeNotice) {
        AppAlertDialog(
            title = stringResource(R.string.settings_model_free_notice_title),
            body = stringResource(R.string.settings_model_free_notice_body),
            confirmText = stringResource(R.string.settings_model_free_notice_accept),
            onConfirm = {
                showFreeNotice = false
                enableFree(true)
            },
            onDismiss = { showFreeNotice = false },
            dismissText = stringResource(R.string.settings_model_free_notice_decline),
        )
    }
}

/**
 * 模型名输入框 + 内联下拉。
 *
 * 保留手输：很多自建网关不实现 /v1/models，拉不到列表时只能靠人填。
 * 拉回来之后箭头可点，菜单直接列模型 id。
 */
@Composable
private fun ModelNameField(
    value: String,
    onValueChange: (String) -> Unit,
    models: List<String>,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppTextField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.settings_model_name),
            trailingIcon = {
                IconButton(onClick = { open = true }, enabled = models.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "选择模型",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Size.iconMd),
                    )
                }
            },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(Radius.md),
        ) {
            models.forEach { id ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = id,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onValueChange(id)
                        open = false
                    },
                )
            }
        }
    }
}

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
            // 有实时进度就显示进度（"正在打包运行 3/12…"），没有再退回通用文案
            LoadingRow(
                state.exportProgress.ifBlank {
                    stringResource(R.string.settings_export_working)
                }
            )
        }

        Spacer(Modifier.height(Space.lg))
    }
}
