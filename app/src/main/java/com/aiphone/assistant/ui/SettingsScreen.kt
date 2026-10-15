package com.aiphone.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppInfo
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.ContextPolicy
import com.aiphone.assistant.data.ThinkingMode
import com.aiphone.assistant.llm.ModelListFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * 结构严格按手稿，三段：
 *
 *   模型          接口地址 / API Key（打码） / 模型 / 图片精度
 *   更多设置      清空上下文 / 记忆 / 日志 / 副屏探针
 *   开发者设置    红字警告 / 清空上下文 / 自动清空 / 记忆三项 / 日志四项
 *
 * ## 关于 API Key
 *
 * 手稿上写了眼睛图标，所以这里是**默认打码**的，点眼睛才明文显示。
 * 一开始我把它做成普通输入框（明文常显），那在公共场合很糟糕 ——
 * 别人扫一眼就把 Key 看走了。
 *
 * ## 关于"开发者设置"
 *
 * 这四组东西（清空上下文、记忆、日志、导出）对普通用户是危险的：
 * 清空上下文、清空日志都是不可撤销的。所以它们统一放在红字警告下面，
 * 而不是散在日常设置里。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.settings_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.displayCutout.union(WindowInsets.statusBars)
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ================= 模型 =================
            item { SectionHeader(stringResource(R.string.settings_section_model)) }
            item { ModelSection(s, onSettingsChange) }

            item { SectionDivider() }

            // ================= 更多设置 =================
            item { SectionHeader(stringResource(R.string.settings_section_developer)) }
            item { DeveloperWarning() }

            item {
                ActionRow(
                    title = stringResource(R.string.settings_clear_context),
                    subtitle = stringResource(R.string.settings_clear_context_sub),
                    onClick = onClearContext,
                )
            }

            item {
                ActionRow(
                    title = "运行副屏探针",
                    subtitle = "检查副屏能否读到控件树，结果写进日志",
                    onClick = onProbeVirtualDisplay,
                )
            }

            item {
                ActionRow(
                    title = stringResource(R.string.settings_vd_mirror),
                    subtitle = stringResource(R.string.settings_vd_mirror_sub),
                    onClick = onLookAtVd,
                )
            }

            item {
                StepsSliderRow(
                    maxSteps = s.maxSteps,
                    onChange = { onSettingsChange(s.copy(maxSteps = it)) },
                )
            }

            item {
                ContextPolicySlider(
                    policy = s.contextPolicy,
                    onChange = { onSettingsChange(s.copy(contextPolicy = it)) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_memory_enabled),
                    subtitle = stringResource(R.string.settings_memory_enabled_desc),
                    checked = s.memoryEnabled,
                    onCheckedChange = { onSettingsChange(s.copy(memoryEnabled = it)) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_save_logs),
                    subtitle = stringResource(R.string.settings_save_logs_desc),
                    checked = s.saveLogs,
                    onCheckedChange = { onSettingsChange(s.copy(saveLogs = it)) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_save_screenshots),
                    subtitle = stringResource(R.string.settings_save_screenshots_desc),
                    checked = s.saveScreenshots,
                    onCheckedChange = { onSettingsChange(s.copy(saveScreenshots = it)) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_local_dismiss),
                    subtitle = stringResource(R.string.settings_local_dismiss_desc),
                    checked = s.localDialogDismiss,
                    onCheckedChange = { onSettingsChange(s.copy(localDialogDismiss = it)) },
                )
            }

            item { SectionDivider() }

            // ================= 日志 =================
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

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        // 顶部切换：自定义 / 免费
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onChange(s.copy(useFreeModel = false)) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_model_mode_custom)) }
            Button(
                onClick = { onChange(s.copy(useFreeModel = true)) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_model_mode_free)) }
        }

        Spacer(Modifier.height(12.dp))

        if (!s.useFreeModel) {
            // ---------- 自定义模式 ----------
            OutlinedTextField(
                value = s.baseUrl,
                onValueChange = { onChange(s.copy(baseUrl = it)) },
                label = { Text(stringResource(R.string.settings_model_provider)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = s.apiKey,
                onValueChange = { onChange(s.copy(apiKey = it)) },
                label = { Text(stringResource(R.string.settings_model_apikey)) },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (keyVisible) R.string.settings_apikey_hide else R.string.settings_apikey_show
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))
        } else {
            // ---------- 免费模式 ----------
            Text(
                text = stringResource(R.string.settings_model_free_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = s.modelName,
            onValueChange = { onChange(s.copy(modelName = it)) },
            label = { Text(stringResource(R.string.settings_model_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.height(4.dp))

        if (!s.useFreeModel) {
            // 获取模型列表按钮（自定义模式）
            OutlinedButton(
                onClick = {
                    if (fetching) return@OutlinedButton
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_model_fetching))
                } else {
                    Text(stringResource(R.string.settings_model_fetch))
                }
            }

            fetchError?.let { err ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_model_fetch_failed, err),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            // 免费模式：点开固定列表
            OutlinedButton(
                onClick = { showFreePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_model_free_pick)) }
        }

        // 自定义模式：拉回来的模型列表
        models?.let { list ->
            AlertDialog(
                onDismissRequest = { models = null },
                title = { Text(stringResource(R.string.settings_model_dialog_title)) },
                text = {
                    if (list.isEmpty()) {
                        Text(stringResource(R.string.settings_model_empty))
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn {
                            items(list) { m ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onChange(s.copy(modelName = m))
                                            models = null
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                ) { Text(m, style = MaterialTheme.typography.bodyLarge) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { models = null }) {
                        Text(stringResource(R.string.schedule_cancel))
                    }
                },
            )
        }

        // 免费模式：固定列表
        if (showFreePicker) {
            AlertDialog(
                onDismissRequest = { showFreePicker = false },
                title = { Text(stringResource(R.string.settings_model_free_pick)) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(freeModels) { m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onChange(s.copy(modelName = m.id))
                                        showFreePicker = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Column {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(m.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showFreePicker = false }) {
                        Text(stringResource(R.string.schedule_cancel))
                    }
                },
            )
        }

        Spacer(Modifier.height(4.dp))

        ThinkingModeSlider(
            current = s.thinking,
            onChange = { onChange(s.copy(thinking = it)) },
        )

        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 免费模式可选的模型（id 要和 opencode.ai 网关认的一致）。
 *
 * 这份清单按 2026-10-15 网关 /zen/v1/models 实时目录更新，并剔除了两类：
 *  - 已下架：deepseek-v4-flash-free（已升级为 v4.1）、union-alpha、ling-3.0-flash-fin-free
 *  - 中国大陆地域锁（网关直接 403 RegionError）：muse-spark-*、cat-jam-free，
 *    且 muse-spark 走的是另一套 /responses 端点，国内手机也用不了
 * 下面这些都是走标准 /chat/completions 的模型。
 */
private val freeModels = listOf(
    FreeModel("deepseek-v4.1-flash-free", "DeepSeek V4.1 Flash"),
    FreeModel("mimo-v2.6-flash-free", "MiMo V2.6 Flash"),
    FreeModel("space-bunny-free", "Space Bunny"),
    FreeModel("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning"),
    FreeModel("jev-1.13-free", "Jev 1.13"),
    FreeModel("step-5-preview-free", "Step 5 Preview"),
    FreeModel("longcat-2.5-preview-free", "LongCat 2.5 Preview"),
    FreeModel("big-pickle", "Big Pickle"),
)

private data class FreeModel(val id: String, val label: String)

/**
 * 思考模式滑块 —— 5 档连续：OFF → LOW → HIGH → MAX → 默认。
 *
 * 没有开关了：滑块本身就是选择器。最右一档「默认」什么参数都不发，
 * 让服务端自己挑。
 */
@Composable
private fun ThinkingModeSlider(
    current: ThinkingMode,
    onChange: (ThinkingMode) -> Unit,
) {
    val options = ThinkingMode.entries
    val index = options.indexOf(current).coerceAtLeast(0)
    var draft by remember(index) { mutableFloatStateOf(index.toFloat()) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_model_thinking),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = options[draft.toInt().coerceIn(0, options.lastIndex)].label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                onChange(options[draft.toInt().coerceIn(0, options.lastIndex)])
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
        )

        Text(
            text = options[draft.toInt().coerceIn(0, options.lastIndex)].note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_context_policy),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = options[draft.toInt().coerceIn(0, options.lastIndex)].label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                onChange(options[draft.toInt().coerceIn(0, options.lastIndex)])
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            // 三档之间只有两个间隔点
            steps = (options.size - 2).coerceAtLeast(0),
        )

        Text(
            text = options[draft.toInt().coerceIn(0, options.lastIndex)].note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_max_steps),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (draft >= STEPS_UNLIMITED) {
                    stringResource(R.string.settings_steps_unlimited)
                } else {
                    stringResource(R.string.settings_steps_value, draft.toInt())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                val v = draft.toInt()
                // 满格 = 不限（存 0）
                onChange(if (v >= STEPS_UNLIMITED) 0 else v)
            },
            valueRange = STEPS_MIN.toFloat()..STEPS_UNLIMITED.toFloat(),
            // 整数刻度：(100-10+1) 个取值 → 中间 89 个分隔点
            steps = STEPS_UNLIMITED - STEPS_MIN - 1,
        )

        Text(
            text = stringResource(R.string.settings_max_steps_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.log_delete_confirm_title)) },
            text = { Text(stringResource(R.string.log_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteLogs()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.log_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            },
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_export_logs),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_export_logs_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        // 统计：让用户知道"有没有东西可导"
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = state.logStats,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.memoryStats.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.memoryStats,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.autoCapStats.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.autoCapStats,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onExportLogs,
                // 打包期间禁用 + 换文案。日志大时这一步是分钟级的，
                // 不给交代的话用户只会觉得按钮失灵
                enabled = !state.exporting,
                modifier = Modifier.weight(1f),
            ) {
                if (state.exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_working))
                } else {
                    Text(stringResource(R.string.settings_export_logs_button))
                }
            }
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.log_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ----------------------------------------------------------------------
// 通用行
// ----------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * 那行红字。
 *
 * 手稿上专门用红笔写了一遍，所以这里也做成醒目的：
 * 红底 + 警告图标，而不是一行小灰字。
 */
@Composable
private fun DeveloperWarning() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_developer_warning),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 下拉选择行。
 *
 * 没用 Material3 的 ExposedDropdownMenuBox —— 那个 API 在各版本之间
 * 改过好几次（menuAnchor 的签名变过），而这个场景只需要"点一下弹出列表"，
 * 用最朴素的 DropdownMenu 就够，且不依赖实验性 API。
 */
@Composable
internal fun <T> DropdownRow(
    title: String,
    subtitle: String?,
    current: T,
    options: List<T>,
    optionLabel: (T) -> String,
    optionEnabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        Box {
            TextButton(onClick = { open = true }) {
                Text(optionLabel(current))
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }

            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        // 不可用项灰显、点不动（防止把 mode 选成 ADB）
                        enabled = optionEnabled(option),
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
