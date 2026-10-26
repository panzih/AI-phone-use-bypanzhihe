package com.aiphone.assistant.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.schedule.Schedule
import com.aiphone.assistant.ui.design.AppAlertDialog
import com.aiphone.assistant.ui.design.AppButton
import com.aiphone.assistant.ui.design.AppScaffold
import com.aiphone.assistant.ui.design.AppTextField
import com.aiphone.assistant.ui.design.Banner
import com.aiphone.assistant.ui.design.ButtonVariant
import com.aiphone.assistant.ui.design.EmptyState
import com.aiphone.assistant.ui.design.GroupCard
import com.aiphone.assistant.ui.design.GroupDivider
import com.aiphone.assistant.ui.design.HintText
import com.aiphone.assistant.ui.design.SectionNote
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.SettingRow
import com.aiphone.assistant.ui.design.Size
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.SwitchRow
import com.aiphone.assistant.ui.design.Tone

/**
 * 定时任务。
 *
 * ## 页面要说清楚的一件事
 *
 * 到点时**手机必须是解锁、亮屏状态**，AI 才操作得了 —— 无障碍读不到
 * 锁屏后面的界面。所以这不是"半夜自动干活"，而是"到点提醒你，
 * 你看着它干"。这句话必须写在界面上，否则用户设了三个半夜的任务，
 * 第二天发现一个都没跑，只会以为软件坏了。
 *
 * ## 精确闹钟
 *
 * Android 14 默认拒绝精确闹钟。拿不到就只能用不精确的（可能晚几分钟）。
 * 界面顶部如实显示现在是哪种，并给一个去授权的入口 ——
 * 不然"设了 9 点怎么 9 点 07 才动"会变成一个查不出来的问题。
 *
 * ## 视觉上的三段
 *
 * 前提说明（灰字）→ 新建（一张卡）→ 已有（一张卡，一条一行）。
 * 空列表不再是"一行灰字挂在标题下面"，而是一个带图标的空状态 ——
 * 空的时候更需要解释，而不是更少。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onAdd: (task: String, hour: Int, minute: Int, daily: Boolean, onVirtualDisplay: Boolean) -> Unit,
    onToggle: (Schedule, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var task by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf(true) }

    // 在副屏上跑：逐条任务可选。默认关 —— 副屏读不到控件树，
    // 定位精度不如主屏，只有简单流程才划算
    var onVirtualDisplay by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it) }
    }

    if (pickerOpen) {
        AppAlertDialog(
            title = stringResource(R.string.schedule_pick_time),
            onDismiss = { pickerOpen = false },
            dismissText = stringResource(R.string.schedule_cancel),
            confirmText = stringResource(R.string.schedule_save),
            onConfirm = {
                pickerOpen = false
                onAdd(task.trim(), timeState.hour, timeState.minute, daily, onVirtualDisplay)
                task = ""
            },
            content = { TimePicker(state = timeState) },
        )
    }

    AppScaffold(
        title = stringResource(R.string.schedule_title),
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
            // ---- 前提说明 ----
            item {
                Column(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    HintText(stringResource(R.string.schedule_intro))

                    // 精确闹钟状态。
                    // ⚠️ 这里**只提示、不申请** —— 全应用的权限申请统一在「权限」页，
                    // 免得用户在三个地方各被弹一次不同的授权框。
                    if (!state.exactAlarmGranted) {
                        Spacer(Modifier.height(Space.md))
                        Banner(
                            text = stringResource(R.string.schedule_inexact_warning) + "。" +
                                stringResource(R.string.schedule_exact_goto_permissions),
                            tone = Tone.Warning,
                        )
                    }
                }
            }

            // ---- 新建 ----
            item { SectionTitle(stringResource(R.string.schedule_new)) }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    Column(modifier = Modifier.padding(Space.lg)) {
                        AppTextField(
                            value = task,
                            onValueChange = { task = it },
                            label = stringResource(R.string.schedule_task_hint),
                            maxLines = 3,
                            singleLine = false,
                        )
                    }
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.schedule_daily),
                        checked = daily,
                        onCheckedChange = { daily = it },
                    )
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.schedule_use_vd),
                        subtitle = stringResource(R.string.schedule_use_vd_desc),
                        checked = onVirtualDisplay,
                        onCheckedChange = { onVirtualDisplay = it },
                    )
                    GroupDivider()
                    AppButton(
                        text = stringResource(R.string.schedule_pick_time),
                        onClick = { pickerOpen = true },
                        enabled = task.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Space.lg),
                    )
                }
            }

            // ---- 已有 ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.schedule_existing), modifier = Modifier.weight(1f))
                    if (state.schedules.isNotEmpty()) {
                        StatusPill(
                            text = "${state.schedules.count { it.enabled }} / ${state.schedules.size} 开启",
                            tone = Tone.Neutral,
                            dot = false,
                            modifier = Modifier.padding(end = Space.lg),
                        )
                    }
                }
            }
            item {
                if (state.schedules.isEmpty()) {
                    GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                        EmptyState(
                            icon = Icons.Filled.Schedule,
                            title = stringResource(R.string.schedule_empty),
                            body = "到点会先发通知提醒你，解锁之后再开始操作。",
                        )
                    }
                } else {
                    GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                        state.schedules.forEachIndexed { index, s ->
                            if (index > 0) GroupDivider()
                            ScheduleRow(
                                schedule = s,
                                onToggle = { onToggle(s, it) },
                                onDelete = { onDelete(s.id) },
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(Space.md))
                SectionNote(stringResource(R.string.schedule_footer))
            }
        }
    }
}

/**
 * 一条定时任务。
 *
 * 时间用等宽数字、字号比正文大一档 —— 它是这一行里**用户唯一要核对**的东西
 * （"到底是 9:00 还是 9:30"）。任务内容反而是附注，所以小一号、灰一档。
 */
@Composable
private fun ScheduleRow(
    schedule: Schedule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.xs, top = Space.sm, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = schedule.timeLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (schedule.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                if (schedule.useVirtualDisplay) {
                    Spacer(Modifier.width(Space.sm))
                    StatusPill(
                        text = stringResource(R.string.schedule_badge_vd),
                        tone = Tone.Info,
                        dot = false,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = schedule.task,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(Space.sm))
        Switch(checked = schedule.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.schedule_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(Size.iconMd),
            )
        }
    }
}
