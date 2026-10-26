package com.aiphone.assistant.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.aiphone.assistant.ui.design.AppButton
import com.aiphone.assistant.ui.design.AppScaffold
import com.aiphone.assistant.ui.design.AppTextField
import com.aiphone.assistant.ui.design.ButtonVariant
import com.aiphone.assistant.ui.design.EmptyState
import com.aiphone.assistant.ui.design.GroupCard
import com.aiphone.assistant.ui.design.GroupDivider
import com.aiphone.assistant.ui.design.HintText
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.SettingRow
import com.aiphone.assistant.ui.design.Size
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.Tone

/**
 * 操作记录。
 *
 * ## 这个页面解决什么
 *
 * 有些操作很固定（发朋友圈、每天打卡、走一套设置），让 AI 每次现想
 * 又慢又贵。用户手动做一遍，把过程记下来 → 交给 AI 学成一个技能 →
 * 以后一次技能调用就走完，中间不花任何模型推理。
 *
 * ## 三步走，界面就按这个顺序排
 *
 *   1. 录制   按开始，然后去别的 App 里正常操作，回来按停止
 *   2. 检查   看看录到了什么（这一步是给用户纠错的机会）
 *   3. 学习   交给 AI 起名、写说明、去掉多余的步骤，存成技能
 *
 * 页面刻意保持线性：一次只做一件事，不需要用户在几个入口之间找。
 *
 * ## 视觉上怎么表达"线性"
 *
 * 每个步骤前面有一个**带序号的圆**（1 / 2 / 3），序号的底色跟着状态变：
 * 灰 = 还没到，主色 = 当前这一步，绿 = 已完成。这样用户不读标题也知道
 * 自己走到了哪儿 —— 原来三段之间只靠一条分割线，读起来是三块平行的内容。
 */
@Composable
fun RecordingScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onLearn: (String) -> Unit,
    onDeleteMacro: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it) }
    }

    // 三段的完成状态：录制中 → 第 1 步是当前；有步骤了 → 第 1 步完成、第 2 步当前
    val hasSteps = state.recordedSteps.isNotEmpty()
    val step1State = when {
        state.recordingActive -> StepState.Current
        hasSteps -> StepState.Done
        else -> StepState.Current
    }
    val step2State = if (hasSteps) StepState.Current else StepState.Pending
    val step3State = if (state.macros.isNotEmpty()) StepState.Done else StepState.Pending

    AppScaffold(
        title = stringResource(R.string.recording_title),
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
            // ---------- 1. 录制 ----------
            item { StepTitle(1, stringResource(R.string.recording_title), step1State) }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    Column(modifier = Modifier.padding(Space.lg)) {
                        if (state.recordingActive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(text = "录制中", tone = Tone.Negative)
                                Spacer(Modifier.width(Space.sm))
                                Text(
                                    text = stringResource(
                                        R.string.recording_in_progress,
                                        state.recordedSteps.size,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.height(Space.sm))
                            HintText(stringResource(R.string.recording_in_progress_desc))
                        } else {
                            HintText(stringResource(R.string.recording_intro))
                        }
                    }
                    GroupDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Space.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.recordingActive) {
                            AppButton(
                                text = stringResource(R.string.recording_stop),
                                onClick = onStop,
                                variant = ButtonVariant.Danger,
                                icon = Icons.Filled.Stop,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            AppButton(
                                text = stringResource(R.string.recording_start),
                                onClick = onStart,
                                icon = Icons.Filled.PlayArrow,
                                modifier = Modifier.weight(1f),
                            )
                            if (hasSteps) {
                                Spacer(Modifier.width(Space.sm))
                                AppButton(
                                    text = stringResource(R.string.recording_discard),
                                    onClick = onDiscard,
                                    variant = ButtonVariant.Secondary,
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 2. 录到了什么 ----------
            if (hasSteps) {
                item { StepTitle(2, stringResource(R.string.recording_steps_title, state.recordedSteps.size), step2State) }
                item {
                    GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                        Column(modifier = Modifier.padding(Space.lg)) {
                            state.recordedSteps.forEachIndexed { i, label ->
                                Row(
                                    modifier = Modifier.padding(vertical = Space.xs),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = "${i + 1}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(22.dp),
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        GroupDivider()
                        Column(modifier = Modifier.padding(Space.lg)) {
                            AppTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = stringResource(R.string.recording_name_hint),
                            )
                            Spacer(Modifier.height(Space.md))
                            AppButton(
                                text = stringResource(
                                    if (state.learning) R.string.recording_learning else R.string.recording_learn
                                ),
                                onClick = { onLearn(name) },
                                enabled = !state.recordingActive && !state.learning,
                                loading = state.learning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(Space.sm))
                            HintText(stringResource(R.string.recording_learn_desc))
                        }
                    }
                }
            }

            // ---------- 3. 已经学会的技能 ----------
            item { StepTitle(3, stringResource(R.string.recording_macros_title), step3State) }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    if (state.macros.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.TaskAlt,
                            title = stringResource(R.string.recording_macros_empty),
                            body = "学会的技能会出现在这里，之后说一句话就能整段跑完。",
                        )
                    } else {
                        state.macros.forEachIndexed { index, macro ->
                            if (index > 0) GroupDivider()
                            SettingRow(
                                title = macro.title,
                                subtitle = stringResource(R.string.recording_macro_steps, macro.steps),
                                trailing = {
                                    IconButton(onClick = { onDeleteMacro(macro.id) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.recording_macro_delete),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(Size.iconMd),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class StepState { Pending, Current, Done }

/**
 * 步骤标题：序号圆 + 标题。
 *
 * 圆 20dp、序号 11sp —— 足够被看见，但比标题小，不会抢走阅读顺序。
 * 底色编码状态（灰/主色/绿），这是这一页唯一的"进度"表达。
 */
@Composable
private fun StepTitle(number: Int, title: String, state: StepState) {
    val bg = when (state) {
        StepState.Pending -> MaterialTheme.colorScheme.outlineVariant
        StepState.Current -> MaterialTheme.colorScheme.primary
        StepState.Done -> com.aiphone.assistant.ui.design.AppTheme.accents.success
    }
    val fg = when (state) {
        StepState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
        StepState.Current -> MaterialTheme.colorScheme.onPrimary
        StepState.Done -> androidx.compose.ui.graphics.Color.White
    }
    Row(
        modifier = Modifier.padding(start = Space.lg, end = Space.lg, top = Space.xxl, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(20.dp).background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = fg,
            )
        }
        Spacer(Modifier.width(Space.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 已学会技能的摘要（界面只需要这几个字段） */
data class MacroSummary(
    val id: String,
    val title: String,
    val steps: Int,
)
