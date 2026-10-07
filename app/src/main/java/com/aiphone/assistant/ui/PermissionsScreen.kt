package com.aiphone.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.OperationMode

/**
 * 权限页 —— **全应用唯一的权限申请入口**。
 *
 * 以前权限散在「设置 → 操作授权」和「定时任务」两处，还有一条通知权限
 * 是发任务时静默弹的；用户想"把权限都开一遍"得自己找齐。现在集中到这一页，
 * 其他任何位置都不再发起权限申请（只显示状态，最多给一句"到「权限」里开"）。
 *
 * 两组：
 *
 *   操作通道   操作方式（下拉） / 无障碍服务 / 副屏（Shizuku，可选）
 *   系统权限   悬浮窗 / 通知 / 精确闹钟
 *
 * ## 三处刻意的保留
 *
 * 1. **无障碍那行的 `enabled` 跟的是"操作方式"**：ADB 通道当前未接入
 *    （`OperationMode.ADB.available = false`），这时按钮要显示成"当前版本未接入"
 *    而不是"去授权"。
 * 2. **副屏不是必选项**：它是 Shizuku 提供的高级通道（产品边界：普通用户永远
 *    不需要 Shizuku），所以文案里写明"可选"，右侧只显示状态、不做成待办事项。
 * 3. **本页只读 [MainUiState] 注入的状态**，不自己去问系统 —— 状态回查统一在
 *    `MainActivity` 的 ON_RESUME 里做，免得又出现第二个状态读取点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onGotoAccessibility: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onShizukuClick: () -> Unit,
) {
    val s = state.settings
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_title)) },
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
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.permissions_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ================= 操作通道 =================
            item { SectionHeader(stringResource(R.string.permissions_section_channel)) }
            item {
                DropdownRow(
                    title = stringResource(R.string.settings_mode_label),
                    subtitle = s.mode.note,
                    current = s.mode,
                    options = OperationMode.entries.toList(),
                    optionLabel = { if (it.available) it.label else "${it.label}（未接入）" },
                    optionEnabled = { it.available },
                    onSelect = { onSettingsChange(s.copy(mode = it)) },
                )
            }
            item {
                PermissionRow(
                    title = stringResource(R.string.settings_a11y_label),
                    desc = null,
                    granted = state.authorized,
                    enabled = s.mode.available,
                    actionText = stringResource(R.string.settings_goto_auth),
                    grantedText = stringResource(R.string.settings_auth_granted),
                    unavailableText = stringResource(R.string.settings_auth_unavailable),
                    onAction = onGotoAccessibility,
                )
            }
            item {
                VirtualDisplayStatusRow(
                    shizukuState = state.shizukuState,
                    onClick = onShizukuClick,
                )
            }

            item { SectionDivider() }

            // ================= 系统权限 =================
            item { SectionHeader(stringResource(R.string.permissions_section_system)) }
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
            item {
                PermissionRow(
                    title = stringResource(R.string.permissions_notif_label),
                    desc = stringResource(R.string.permissions_notif_desc),
                    granted = state.notifGranted,
                    actionText = stringResource(R.string.permissions_grant),
                    grantedText = stringResource(R.string.permissions_granted),
                    onAction = onRequestNotification,
                )
            }
            item {
                PermissionRow(
                    title = stringResource(R.string.permissions_exact_label),
                    desc = stringResource(R.string.permissions_exact_desc),
                    granted = state.exactAlarmGranted,
                    actionText = stringResource(R.string.permissions_grant),
                    grantedText = stringResource(R.string.permissions_granted),
                    onAction = onRequestExactAlarm,
                )
            }
        }
    }
}

/**
 * 权限行 —— 无障碍 / 悬浮窗 / 通知 / 精确闹钟**共用同一个组件**。
 *
 * 这些行长得一样不是巧合：它们都是"未授权 → 去系统设置开 → 已授权"。
 * 各写一遍的后果就是改一处漏一处。
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
 * 副屏状态行 —— 贴在「无障碍服务」行正下方。
 *
 * 副屏是一条**可选**的高级操作通道（Shizuku），不是必须授予的权限，
 * 所以右侧不显示"去授权"按钮、也不做跳页箭头，只显示 Shizuku 当前状态；
 * 整行可点，点击后的动作由上层按状态决定（未授权→弹授权框，没启动→提示怎么启动）。
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
// 分段标题 —— 跟随本工程既有约定：每个页面各自一份（见 SettingsScreen
// 的两个同名私有组件）。做成 internal 会和 ScheduleScreen / RecordingScreen
// 里的同名私有组件撞车（conflicting overloads），所以不共享。
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
