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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.aiphone.assistant.ui.design.Radius
import com.aiphone.assistant.ui.design.SectionNote
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.SettingRow
import com.aiphone.assistant.ui.design.Size
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.SwitchRow
import com.aiphone.assistant.ui.design.Tone
import com.aiphone.assistant.ui.design.interactiveSurface

/**
 * 定时任务。
 *
 * ## 页面要说清楚的一件事
 *
 * 到点时**手机必须是解锁、亮屏状态**，AI 才操作得了 —— 无障碍读不到
 * 锁屏后面的界面。所以这不是「半夜自动干活」，而是「到点提醒你，
 * 你看着它干」。
 *
 * ## 这一版的改动
 *
 * 时间不再藏在「选时间 → 直接保存」的弹窗里。现在先把时间选好、
 * 显示在卡片上，确认无误再点「添加」。用户可以在保存前改主意，
 * 而不是点错一次就多出一条要删的任务。
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
    var onVirtualDisplay by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(9) }
    var minute by remember { mutableIntStateOf(0) }
    val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it) }
    }

    if (pickerOpen) {
        AppAlertDialog(
            title = stringResource(R.string.schedule_pick_time),
            onDismiss = { pickerOpen = false },
            dismissText = stringResource(R.string.schedule_cancel),
            confirmText = "确定",
            onConfirm = {
                pickerOpen = false
                hour = timeState.hour
                minute = timeState.minute
            },
            content = { TimePicker(state = timeState) },
        )
    }

    AppScaffold(
        title = stringResource(R.string.schedule_title),
        subtitle = "到点提醒你，你看着它执行",
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

                    // 精确闹钟状态。这里**只提示、不申请** ——
                    // 全应用的权限申请统一在「权限」页。
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
            item { SectionTitle(text = stringResource(R.string.schedule_new), icon = Icons.Filled.Add) }
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
                        Spacer(Modifier.height(Space.md))
                        TimePickerRow(
                            hour = hour,
                            minute = minute,
                            onClick = { pickerOpen = true },
                        )
                    }
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(R.string.schedule_daily),
                        subtitle = if (daily) "每天这个时间提醒一次" else "只执行一次，跑完自动关闭",
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
                        text = "添加定时任务",
                        onClick = {
                            onAdd(task.trim(), hour, minute, daily, onVirtualDisplay)
                            task = ""
                        },
                        enabled = task.isNotBlank(),
                        icon = Icons.Filled.Add,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Space.lg),
                    )
                }
            }

            // ---- 已有 ----
            item {
                SectionTitle(
                    text = stringResource(R.string.schedule_existing),
                    icon = Icons.Filled.Schedule,
                    trailing = {
                        if (state.schedules.isNotEmpty()) {
                            StatusPill(
                                text = "${state.schedules.count { it.enabled }} / ${state.schedules.size} 开启",
                                tone = Tone.Neutral,
                                dot = false,
                            )
                        }
                    },
                )
            }
            if (state.schedules.isEmpty()) {
                item {
                    GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                        EmptyState(
                            icon = Icons.Filled.Schedule,
                            title = stringResource(R.string.schedule_empty),
                            body = "到点会先发通知提醒你，解锁之后再开始操作。",
                        )
                    }
                }
            } else {
                items(state.schedules.size, key = { state.schedules[it].id }) { index ->
                    val s = state.schedules[index]
                    ScheduleCard(
                        schedule = s,
                        onToggle = { onToggle(s, it) },
                        onDelete = { onDelete(s.id) },
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs),
                    )
                }
            }

            item {
                Spacer(Modifier.height(Space.md))
                SectionNote(stringResource(R.string.schedule_footer))
            }
        }
    }
}

/** 新建任务里的时间行：大字时间 + 右侧「修改」提示 */
@Composable
private fun TimePickerRow(hour: Int, minute: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.md))
            .interactiveSurface(shape = RoundedCornerShape(Radius.md), onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Size.iconMd),
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = "%02d:%02d".format(hour, minute),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        StatusPill(text = "修改时间", tone = Tone.Info, dot = false)
    }
}

/**
 * 一条定时任务。
 *
 * 时间用大字号、等宽数字 —— 它是这一行里**用户唯一要核对**的东西
 * （「到底是 9:00 还是 9:30」）。任务内容反而是附注，所以小一号、灰一档。
 */
@Composable
private fun ScheduleCard(
    schedule: Schedule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GroupCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.lg, end = Space.xs, top = Space.md, bottom = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(88.dp)) {
                Text(
                    text = "%02d:%02d".format(schedule.hour, schedule.minute),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (schedule.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    },
                )
                Text(
                    text = if (schedule.repeatDaily) "每天" else "仅一次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schedule.task,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (schedule.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    maxLines = 2,
                )
                if (schedule.useVirtualDisplay) {
                    Spacer(Modifier.height(Space.xs))
                    StatusPill(
                        text = stringResource(R.string.schedule_badge_vd),
                        tone = Tone.Info,
                        dot = false,
                    )
                }
            }
            Spacer(Modifier.width(Space.sm))
            Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.schedule_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(Size.iconMd),
                )
            }
        }
    }
}
