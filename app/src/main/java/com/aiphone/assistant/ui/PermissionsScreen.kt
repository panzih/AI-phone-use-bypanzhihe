package com.aiphone.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.SettingRow
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.Tone

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
 * ## 视觉约定（本次重写统一）
 *
 * 状态**只用三种胶囊**表达：已开启（绿）、待开启（灰）、不可用（灰）。
 * 以前这里是"绿勾 + 文字"和"描边按钮"混着用，同一个"没开"有两种长相。
 * 现在「已开启」是胶囊，「待开启」是操作按钮 —— 形状不同，含义就不同：
 * **能点的才长得像能点的**。
 *
 * ## 三处刻意的保留
 *
 * 1. **无障碍那行的 enabled 跟的是"操作方式"**：如果哪天某条操作方式在当前
 *    版本不可用（OperationMode.*.available = false），右侧要显示成"当前版本未接入"
 *    而不是"去授权"。⚠️ 顺带一条：ADB / Shizuku 那项**还要 Shizuku 真的在跑**
 *    才让选，否则标签上直接写「先启动 Shizuku」—— 用户点不动的那一项，
 *    得能一眼看出缺什么。
 * 2. **副屏不是必选项**：它是 Shizuku 提供的高级通道（产品边界：普通用户永远
 *    不需要 Shizuku），所以文案里写明"可选"，右侧只显示状态、不做成待办事项。
 * 3. **本页只读 [MainUiState] 注入的状态**，不自己去问系统 —— 状态回查统一在
 *    `MainActivity` 的 ON_RESUME 里做，免得又出现第二个状态读取点。
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
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    HintText(stringResource(R.string.permissions_intro))
                }
            }

            // ================= 操作通道 =================
            item { SectionTitle(stringResource(R.string.permissions_section_channel)) }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    DropdownRow(
                        title = stringResource(R.string.settings_mode_label),
                        subtitle = s.mode.note,
                        current = s.mode,
                        options = OperationMode.entries.toList(),
                        // ADB / Shizuku 只有 Shizuku 真的在跑时才让选；灰着的时候
                        // 直接把"缺什么"写在选项上 —— 用户点不动的那一项，
                        // 得能一眼看出为什么点不动。
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
            item { SectionTitle(stringResource(R.string.permissions_section_system)) }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    PermissionRow(
                        title = stringResource(R.string.settings_overlay_label),
                        desc = stringResource(R.string.settings_overlay_desc),
                        granted = state.overlayGranted,
                        actionText = stringResource(R.string.settings_overlay_open),
                        grantedText = stringResource(R.string.settings_overlay_granted),
                        onAction = onOpenOverlaySettings,
                    )
                    GroupDivider()
                    PermissionRow(
                        title = stringResource(R.string.permissions_notif_label),
                        desc = stringResource(R.string.permissions_notif_desc),
                        granted = state.notifGranted,
                        actionText = stringResource(R.string.permissions_grant),
                        grantedText = stringResource(R.string.permissions_granted),
                        onAction = onRequestNotification,
                    )
                    GroupDivider()
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
 * 权限行 —— 无障碍 / 悬浮窗 / 通知 / 精确闹钟**共用同一个组件**。
 *
 * 这些行长得一样不是巧合：它们都是"未授权 → 去系统设置开 → 已授权"。
 * 各写一遍的后果就是改一处漏一处。
 *
 * @param desc 可为空。无障碍那行在非 ADB 模式下不写说明，
 *             因为上面的「操作方式」下拉里已经有同样一段话，重复两遍反而乱
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
    SettingRow(
        title = title,
        subtitle = desc,
        enabled = enabled,
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
 * 所以右侧不显示"去授权"按钮、也不做跳页箭头，只显示 Shizuku 当前状态；
 * 整行可点，点击后的动作由上层按状态决定（未授权→弹授权框，没启动→提示怎么启动）。
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
        trailing = {
            StatusPill(
                text = shizukuState.ifBlank { "—" },
                tone = Tone.Neutral,
                dot = false,
            )
        },
    )
}
