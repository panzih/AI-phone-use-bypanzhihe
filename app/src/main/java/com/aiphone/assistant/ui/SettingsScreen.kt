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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.AutoClear
import com.aiphone.assistant.data.OperationMode

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
    onClearContext: () -> Unit,
    onExportLatest: () -> Unit,
    onExportAll: () -> Unit,
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

            // ================= 操作授权 =================
            item { SectionHeader(stringResource(R.string.settings_section_auth)) }
            item {
                AuthSection(
                    current = s.mode,
                    authorized = state.authorized,
                    onSelect = { onSettingsChange(s.copy(mode = it)) },
                    onGotoAuth = onGotoAuth,
                )
            }
            item {
                OverlayPermissionRow(
                    granted = state.overlayGranted,
                    onOpen = onOpenOverlaySettings,
                )
            }

            item { SectionDivider() }

            // ================= 开发者设置 =================
            item { SectionHeader(stringResource(R.string.settings_section_developer)) }
            item { DeveloperWarning() }

            item {
                ActionRow(
                    title = stringResource(R.string.settings_clear_context),
                    subtitle = null,
                    onClick = onClearContext,
                )
            }

            item {
                DropdownRow(
                    title = stringResource(R.string.settings_max_steps),
                    subtitle = stringResource(R.string.settings_max_steps_desc),
                    current = s.maxSteps,
                    options = listOf(10, 20, 30, 50, 100),
                    // 注意：这里不能用 stringResource —— optionLabel 是普通 lambda，
                    // 不是 @Composable 上下文，调 @Composable 函数编译不过。
                    optionLabel = { "$it 步" },
                    onSelect = { onSettingsChange(s.copy(maxSteps = it)) },
                )
            }

            item {
                DropdownRow(
                    title = stringResource(R.string.settings_auto_clear),
                    subtitle = stringResource(R.string.settings_auto_clear_desc),
                    current = s.autoClear,
                    options = AutoClear.entries.toList(),
                    optionLabel = { it.label },
                    onSelect = { onSettingsChange(s.copy(autoClearMinutes = it.minutes)) },
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
                    title = stringResource(R.string.settings_keep_memory),
                    subtitle = stringResource(R.string.settings_keep_memory_desc),
                    checked = s.keepMemory,
                    onCheckedChange = { onSettingsChange(s.copy(keepMemory = it)) },
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
                    onExportLatest = onExportLatest,
                    onExportAll = onExportAll,
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

        Spacer(Modifier.height(12.dp))

        DropdownRow(
            title = stringResource(R.string.settings_model_detail),
            subtitle = "original 保留原图；low 压到 512×512（手机 UI 文字多，建议 original）",
            current = s.detail,
            options = listOf("original", "low"),
            optionLabel = { it },
            onSelect = { onChange(s.copy(detail = it)) },
            horizontalPadding = 0.dp,
        )

        Spacer(Modifier.height(4.dp))
    }
}

// ----------------------------------------------------------------------
// 操作授权
// ----------------------------------------------------------------------

@Composable
private fun AuthSection(
    current: OperationMode,
    authorized: Boolean,
    onSelect: (OperationMode) -> Unit,
    onGotoAuth: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        DropdownRow(
            title = stringResource(R.string.settings_mode_label),
            subtitle = current.note,
            current = current,
            options = OperationMode.entries.toList(),
            optionLabel = { if (it.available) it.label else "${it.label}（未接入）" },
            onSelect = onSelect,
            horizontalPadding = 0.dp,
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onGotoAuth,
                enabled = current.available,
            ) {
                Text(stringResource(R.string.settings_goto_auth))
            }

            Spacer(Modifier.width(12.dp))

            // 授权状态：说清楚是"系统里开着"还是"真的连上了"
            if (current.available) {
                Icon(
                    imageVector = if (authorized) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Warning
                    },
                    contentDescription = null,
                    tint = if (authorized) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (authorized) R.string.settings_auth_granted
                        else R.string.settings_auth_missing
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (authorized) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_auth_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 悬浮窗授权行。
 *
 * 单独列出来是因为它是个**特殊权限**：代码申请不了，必须跳系统设置手动开，
 * 而且各家 ROM 的入口名字都不一样（小米叫"显示在其他应用上层"，
 * ColorOS 还会额外锁"受限制的设置"）。不给用户一个明确入口，
 * 他根本不知道该去哪开。
 */
@Composable
private fun OverlayPermissionRow(
    granted: Boolean,
    onOpen: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_overlay_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_overlay_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))

            if (granted) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.settings_overlay_granted),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                OutlinedButton(onClick = onOpen) {
                    Text(stringResource(R.string.settings_overlay_open))
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 日志
// ----------------------------------------------------------------------

@Composable
private fun LogSection(
    state: MainUiState,
    onExportLatest: () -> Unit,
    onExportAll: () -> Unit,
) {
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
                if (state.insightCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_insight_stats, state.insightCount),
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
            Button(onClick = onExportLatest, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_export_latest))
            }
            OutlinedButton(onClick = onExportAll, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_export_all))
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
