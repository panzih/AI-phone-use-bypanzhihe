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
import com.aiphone.assistant.data.OperationMode
import com.aiphone.assistant.data.ThinkingMode

/**
 * 设置页。
 *
 * 结构严格按手稿，三段：
 *
 *   模型          接口地址 / API Key（打码） / 模型 / 图片精度
 *   操作授权      操作方式（下拉） / 前往授权
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
    onGotoAuth: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onShizukuClick: () -> Unit,
    onProbeVirtualDisplay: () -> Unit,
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
            // ================= 关于 =================
            item { AboutSection(appVersion = state.appVersion) }

            item { SectionDivider() }

            // ================= 模型 =================
            item { SectionHeader(stringResource(R.string.settings_section_model)) }
            item { ModelSection(s, onSettingsChange) }

            item { SectionDivider() }

            // ================= 操作授权 =================
            item { SectionHeader(stringResource(R.string.settings_section_auth)) }
            item {
                AuthSection(
                    current = s.mode,
                    authorized = state.authorized,
                    shizukuState = state.shizukuState,
                    onSelect = { onSettingsChange(s.copy(mode = it)) },
                    onGotoAuth = onGotoAuth,
                    onShizukuClick = onShizukuClick,
                )
            }
            item {
                PermissionRow(
                    title = stringResource(R.string.settings_overlay_label),
                    desc = stringResource(R.string.settings_overlay_desc),
                    granted = state.overlayGranted,
                    actionText = stringResource(R.string.settings_overlay_open),
                    grantedText = stringResource(R.string.settings_overlay_granted),
                    onAction = onOpenOverlaySettings,
                )
            }

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

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = s.baseUrl,
            onValueChange = { onChange(s.copy(baseUrl = it)) },
            label = { Text(stringResource(R.string.settings_model_provider)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.height(12.dp))

        // API Key：默认打码，点眼睛才明文
        OutlinedTextField(
            value = s.apiKey,
            onValueChange = { onChange(s.copy(apiKey = it)) },
            label = { Text(stringResource(R.string.settings_model_apikey)) },
            singleLine = true,
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = stringResource(
                            if (keyVisible) R.string.settings_apikey_hide
                            else R.string.settings_apikey_show
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = s.modelName,
            onValueChange = { onChange(s.copy(modelName = it)) },
            label = { Text(stringResource(R.string.settings_model_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.height(4.dp))

        ThinkingModeSlider(
            current = s.thinking,
            customEnabled = s.customThinkingEnabled,
            onCustomEnabledChange = { onChange(s.copy(customThinkingEnabled = it)) },
            onChange = { onChange(s.copy(thinking = it)) },
        )

        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 思考模式滑块 —— 和「最大步数」「上下文」同一种交互。
 *
 * 四档从最省到最贵：OFF / LOW / HIGH / MAX。用滑块而不是下拉，
 * 是因为这四档是一条**强度轴**，位置本身就表示了"调高会更贵更慢"。
 *
 * 右边加一个开关：关着的时候滑块隐藏，用默认值 OFF；开着的时候
 * 显示滑块，用户可以自己调。
 */
@Composable
private fun ThinkingModeSlider(
    current: ThinkingMode,
    customEnabled: Boolean,
    onCustomEnabledChange: (Boolean) -> Unit,
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
            Switch(
                checked = customEnabled,
                onCheckedChange = onCustomEnabledChange,
            )
        }

        if (customEnabled) {
            Spacer(Modifier.height(4.dp))

            Text(
                text = options[draft.toInt().coerceIn(0, options.lastIndex)].label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )

            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = {
                    onChange(options[draft.toInt().coerceIn(0, options.lastIndex)])
                },
                valueRange = 0f..options.lastIndex.toFloat(),
                // 四档之间三个间隔点
                steps = (options.size - 2).coerceAtLeast(0),
            )

            Text(
                text = options[draft.toInt().coerceIn(0, options.lastIndex)].note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "关闭时使用官方默认档位（HIGH）：充分推理，复杂任务更稳",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------------
// 操作授权
// ----------------------------------------------------------------------

@Composable
private fun AuthSection(
    current: OperationMode,
    authorized: Boolean,
    shizukuState: String,
    onSelect: (OperationMode) -> Unit,
    onGotoAuth: () -> Unit,
    onShizukuClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        DropdownRow(
            title = stringResource(R.string.settings_mode_label),
            subtitle = current.note,
            current = current,
            options = OperationMode.entries.toList(),
            optionLabel = { if (it.available) it.label else "${it.label}（未接入）" },
            // 不可用的操作方式（ADB）只能看、不能选中
            optionEnabled = { it.available },
            onSelect = onSelect,
            horizontalPadding = 0.dp,
        )

        Spacer(Modifier.height(4.dp))

        PermissionRow(
            title = stringResource(R.string.settings_a11y_label),
            desc = null,
            granted = authorized,
            enabled = current.available,
            actionText = stringResource(R.string.settings_goto_auth),
            grantedText = stringResource(R.string.settings_auth_granted),
            unavailableText = stringResource(R.string.settings_auth_unavailable),
            onAction = onGotoAuth,
        )

        Spacer(Modifier.height(4.dp))

        // 副屏是「通道」不是「权限」：不做跳页箭头，
        // 在无障碍行正下方贴一行 Shizuku 状态，点一下按状态处理
        VirtualDisplayStatusRow(
            shizukuState = shizukuState,
            onClick = onShizukuClick,
        )
    }
}

/**
 * 权限行 —— 无障碍和悬浮窗**共用同一个组件**。
 *
 * 之前这两行是两个各写一遍的 Row：无障碍用实心 `Button`、悬浮窗用
 * `OutlinedButton`，摆在同一个列表里看着像两种不同性质的东西，
 * 其实它们是一回事 —— 都是"未授权 → 去系统设置开 → 已授权"。
 * 抄成两遍的后果就是改一处漏一处，现在合成一个。
 *
 * @param desc 可为空。无障碍那行不写说明，因为上面的「操作方式」下拉里
 *             已经有同样一段话，重复两遍反而乱
 * @param enabled false 表示这个能力当前版本还没接入
 */
@Composable
private fun PermissionRow(
    title: String,
    desc: String?,
    granted: Boolean,
    actionText: String,
    grantedText: String,
    enabled: Boolean = true,
    unavailableText: String = "",
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        when {
            !enabled -> Text(
                text = unavailableText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            granted -> {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = grantedText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> OutlinedButton(onClick = onAction) {
                Text(actionText)
            }
        }
    }
}

/**
 * 副屏状态行 —— 贴在「无障碍」行正下方。
 *
 * 副屏是一条操作通道（Shizuku 高级通道），不是要用户授予的权限，
 * 所以右侧不显示「去授权」按钮、也不做跳页箭头，只显示 Shizuku
 * 当前状态；整行可点，点击后的动作由上层按状态决定。
 */
@Composable
private fun VirtualDisplayStatusRow(
    shizukuState: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.vd_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_vd_entry_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = shizukuState.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------------
// 关于
// ----------------------------------------------------------------------

/**
 * 关于本软件。
 *
 * 放在设置页最顶上，是"打开设置第一眼要看到的东西"：这是什么应用、
 * 什么版本、什么协议、去哪找源码。出问题时报版本号也是第一步。
 */
@Composable
private fun AboutSection(appVersion: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 和主界面、启动图标共用同一份矢量，不做第二份图标资源
            Icon(
                painter = painterResource(R.drawable.ic_box_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_about_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        AboutRow(stringResource(R.string.settings_about_version), appVersion.ifBlank { "?" })
        AboutRow(stringResource(R.string.settings_about_author), AppInfo.AUTHOR)
        AboutRow(stringResource(R.string.settings_about_license), AppInfo.LICENSE)

        // 仓库地址留空时不显示这一行 —— 免得点开一个 404
        if (AppInfo.REPO_URL.isNotBlank()) {
            AboutRow(stringResource(R.string.settings_about_repo), AppInfo.REPO_URL)
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
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
            OutlinedButton(onClick = onExportLogs, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_export_logs_button))
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
private fun <T> DropdownRow(
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
