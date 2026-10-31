package com.aiphone.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.OperationMode
import com.aiphone.assistant.ui.design.AppButton
import com.aiphone.assistant.ui.design.AppScaffold
import com.aiphone.assistant.ui.design.ButtonVariant
import com.aiphone.assistant.ui.design.DropdownRow
import com.aiphone.assistant.ui.design.GroupCard
import com.aiphone.assistant.ui.design.GroupDivider
import com.aiphone.assistant.ui.design.HintText
import com.aiphone.assistant.ui.design.IconBadge
import com.aiphone.assistant.ui.design.Radius
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.SettingRow
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.Tone

/**
 * 权限页 —— **全应用唯一的权限申请入口**。
 *
 * 以前权限散在「设置 → 操作授权」和「定时任务」两处，还有一条通知权限
 * 是发任务时静默弹的；用户想「把权限都开一遍」得自己找齐。现在集中到这一页，
 * 其他任何位置都不再发起权限申请（只显示状态，最多给一句「到「权限」里开」）。
 *
 * ## 这一版加了什么
 *
 * 顶部先给一个**总进度**：「3 / 4 已开启」。用户不用逐行读，
 * 一眼就知道还差几个、值不值得现在开。
 *
 * ## 视觉约定
 *
 * 状态**只用三种胶囊**表达：已开启（绿）、待开启（灰）、不可用（灰）。
 * 「已开启」是胶囊，「待开启」是操作按钮 —— 形状不同，含义就不同：
 * **能点的才长得像能点的**。
 */
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

    AppScaffold(
        title = stringResource(R.string.permissions_title),
        subtitle = "全应用唯一的权限申请入口",
        onBack = onBack,
        backLabel = stringResource(R.string.settings_back),
        snackbarHostState = snackbar,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(bottom = Space.xxl),
        ) {
            item {
                PermissionOverview(
                    authorized = state.authorized,
                    overlayGranted = state.overlayGranted,
                    notifGranted = state.notifGranted,
                    exactAlarmGranted = state.exactAlarmGranted,
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                )
            }

            item {
                HintText(
                    text = stringResource(R.string.permissions_intro),
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs),
                )
            }

            // ================= 操作通道 =================
            item {
                SectionTitle(
                    text = stringResource(R.string.permissions_section_channel),
                    icon = Icons.Filled.PhoneAndroid,
                )
            }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    DropdownRow(
                        title = stringResource(R.string.settings_mode_label),
                        subtitle = s.mode.note,
                        current = s.mode,
                        options = OperationMode.entries.toList(),
                        // ADB / Shizuku 只有 Shizuku 真的在跑时才让选；灰着的时候
                        // 直接把「缺什么」写在选项上。
                        optionLabel = { mode ->
                            when {
                                !mode.available -> "${mode.label}（未接入）"
                                mode == OperationMode.ADB && !state.shizukuReady ->
                                    "${mode.label}（先启动 Shizuku）"
                                else -> mode.label
                            }
                        },
                        optionEnabled = { mode ->
                            mode.available && (mode != OperationMode.ADB || state.shizukuReady)
                        },
                        onSelect = { onSettingsChange(s.copy(mode = it)) },
                    )
                    GroupDivider()
                    PermissionRow(
                        icon = Icons.Filled.Accessibility,
                        title = stringResource(R.string.settings_a11y_label),
                        desc = if (s.mode == OperationMode.ADB) {
                            "ADB 模式下它是可选的：开着读控件树（定位更准），不开也能跑（只看截图用坐标）。"
                        } else {
                            null
                        },
                        granted = state.authorized,
                        enabled = s.mode.available,
                        actionText = stringResource(R.string.settings_goto_auth),
                        grantedText = stringResource(R.string.settings_auth_granted),
                        unavailableText = stringResource(R.string.settings_auth_unavailable),
                        onAction = onGotoAccessibility,
                    )
                    GroupDivider()
                    VirtualDisplayStatusRow(
                        shizukuState = state.shizukuState,
                        onClick = onShizukuClick,
                    )
                }
            }

            // ================= 系统权限 =================
            item {
                SectionTitle(
                    text = stringResource(R.string.permissions_section_system),
                    icon = Icons.Filled.Layers,
                )
            }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    PermissionRow(
                        icon = Icons.Filled.Layers,
                        title = stringResource(R.string.settings_overlay_label),
                        desc = stringResource(R.string.settings_overlay_desc),
                        granted = state.overlayGranted,
                        actionText = stringResource(R.string.settings_overlay_open),
                        grantedText = stringResource(R.string.settings_overlay_granted),
                        onAction = onOpenOverlaySettings,
                    )
                    GroupDivider()
                    PermissionRow(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.permissions_notif_label),
                        desc = stringResource(R.string.permissions_notif_desc),
                        granted = state.notifGranted,
                        actionText = stringResource(R.string.permissions_grant),
                        grantedText = stringResource(R.string.permissions_granted),
                        onAction = onRequestNotification,
                    )
                    GroupDivider()
                    PermissionRow(
                        icon = Icons.Filled.Alarm,
                        title = stringResource(R.string.permissions_exact_label),
                        desc = stringResource(R.string.permissions_exact_desc),
                        granted = state.exactAlarmGranted,
                        actionText = stringResource(R.string.permissions_grant),
                        grantedText = stringResource(R.string.permissions_granted),
                        onAction = onRequestExactAlarm,
                    )
                }
            }

            item {
                Spacer(Modifier.height(Space.lg))
                Text(
                    text = "权限只需要开一次，之后由系统记住。换手机或重装才需要重来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = Space.lg),
                )
            }
        }
    }
}

/**
 * 顶部的总进度。
 *
 * 只统计「必须开」的四项（无障碍 / 悬浮窗 / 通知 / 精确闹钟），
 * 副屏是可选高级通道，不进来凑数 —— 否则用户会以为不开就缺一块。
 */
@Composable
private fun PermissionOverview(
    authorized: Boolean,
    overlayGranted: Boolean,
    notifGranted: Boolean,
    exactAlarmGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    val granted = listOf(authorized, overlayGranted, notifGranted, exactAlarmGranted).count { it }
    val total = 4
    val allReady = granted == total
    val shape = RoundedCornerShape(Radius.lg)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (allReady) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                shape,
            )
            .padding(Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (allReady) Icons.Filled.CheckCircle else Icons.Filled.Layers,
                contentDescription = null,
                tint = if (allReady) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(horizontal = Space.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (allReady) "全部就绪" else "还差 ${total - granted} 项",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (allReady) {
                        "AI 已经可以读屏幕、点按钮，任务进行时也能随时停下。"
                    } else {
                        "先把下面标着「去开启」的几项开完，再发第一条任务。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = "$granted / $total",
                tone = if (allReady) Tone.Positive else Tone.Neutral,
                dot = false,
            )
        }
        Spacer(Modifier.height(Space.md))
        LinearProgressIndicator(
            progress = { granted.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/**
 * 权限行 —— 无障碍 / 悬浮窗 / 通知 / 精确闹钟**共用同一个组件**。
 *
 * 这些行长得一样不是巧合：它们都是「未授权 → 去系统设置开 → 已授权」。
 * 各写一遍的后果就是改一处漏一处。
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    desc: String?,
    granted: Boolean,
    actionText: String,
    grantedText: String,
    enabled: Boolean = true,
    unavailableText: String = "",
    onAction: () -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = desc,
        enabled = enabled,
        leading = {
            IconBadge(
                icon = icon,
                tone = when {
                    !enabled -> Tone.Neutral
                    granted -> Tone.Positive
                    else -> Tone.Warning
                },
            )
        },
        trailing = {
            when {
                !enabled -> StatusPill(
                    text = unavailableText.ifBlank { "不可用" },
                    tone = Tone.Neutral,
                    dot = false,
                )

                granted -> StatusPill(text = grantedText, tone = Tone.Positive)

                else -> AppButton(
                    text = actionText,
                    onClick = onAction,
                    variant = ButtonVariant.Ghost,
                )
            }
        },
    )
}

/**
 * 副屏状态行 —— 贴在「无障碍服务」行下面。
 *
 * 副屏是一条**可选**的高级操作通道（Shizuku），不是必须授予的权限，
 * 所以右侧不显示「去授权」按钮、也不做跳页箭头，只显示 Shizuku 当前状态。
 */
@Composable
private fun VirtualDisplayStatusRow(
    shizukuState: String,
    onClick: () -> Unit,
) {
    SettingRow(
        title = stringResource(R.string.vd_title),
        subtitle = stringResource(R.string.settings_vd_entry_desc),
        onClick = onClick,
        leading = {
            IconBadge(
                icon = Icons.Filled.PhoneAndroid,
                tone = if (shizukuState.contains("就绪") || shizukuState.contains("已")) {
                    Tone.Info
                } else {
                    Tone.Neutral
                },
            )
        },
        trailing = {
            StatusPill(
                text = shizukuState.ifBlank { "—" },
                tone = Tone.Neutral,
                dot = false,
            )
        },
    )
}
