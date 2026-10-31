package com.aiphone.assistant.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// ======================================================================
// 语义提示
// ======================================================================

enum class Tone { Neutral, Positive, Warning, Negative, Info }

/** 语义色的前景色。供 [IconBadge] 等组件复用。 */
@Composable
internal fun Tone.color(): Color = when (this) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Positive -> AppTheme.accents.success
    Tone.Warning -> AppTheme.accents.warning
    Tone.Negative -> MaterialTheme.colorScheme.error
    Tone.Info -> AppTheme.accents.info
}

/** 语义色的容器底色。 */
@Composable
internal fun Tone.container(): Color = when (this) {
    Tone.Neutral -> AppTheme.accents.sunken
    Tone.Positive -> AppTheme.accents.successContainer
    Tone.Warning -> AppTheme.accents.warningContainer
    Tone.Negative -> MaterialTheme.colorScheme.errorContainer
    Tone.Info -> AppTheme.accents.infoContainer
}

@Composable
private fun Tone.onContainer(): Color = when (this) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Positive -> AppTheme.accents.onSuccessContainer
    Tone.Warning -> AppTheme.accents.onWarningContainer
    Tone.Negative -> MaterialTheme.colorScheme.onErrorContainer
    Tone.Info -> AppTheme.accents.onInfoContainer
}

/**
 * 常驻提示块。
 *
 * 三个使用场景都是"不做就会出事"的信息：公网明文 HTTP、开发者危险区、
 * 到点不一定能跑。这类信息不能只做成一句灰字（会被扫过去），也不能弹窗
 * （会打断），所以做成**贴在内容旁边、一直看得到**的一条。
 *
 * 左边那条 3dp 的竖线是这一版加的：一眼能看出"这是提示，不是正文"。
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Warning,
    icon: ImageVector? = Icons.Filled.Warning,
    action: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(tone.container(), shape),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(tone.color(), shape),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.md, vertical = Space.md),
            verticalAlignment = Alignment.Top,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tone.color(),
                    modifier = Modifier.padding(top = 1.dp).size(Size.iconSm),
                )
                Spacer(Modifier.width(Space.sm))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = tone.onContainer(),
                )
                if (action != null) {
                    Spacer(Modifier.height(Space.xs))
                    action()
                }
            }
        }
    }
}

/**
 * 状态胶囊 —— 权限"已授权"、模型"免费通道"、任务"运行中"这类极短状态。
 *
 * 用词固定成三档（已开启 / 待授权 / 不可用），不允许各页自己写"已完成"
 * "已获得"—— 同一个状态有两种说法，用户会以为是两件事。
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    dot: Boolean = true,
) {
    Row(
        modifier = modifier
            .background(tone.container(), RoundedCornerShape(Radius.pill))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(tone.color(), CircleShape)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone.onContainer(),
        )
    }
}

/**
 * 统计块（日志条数、记忆条数那种"只读的一小段事实"）。
 *
 * 用凹陷色而不是卡片：它不含任何操作，不该长得像可点的东西。
 */
@Composable
fun StatBlock(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppTheme.accents.sunken, RoundedCornerShape(Radius.md))
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
        lines.forEachIndexed { index, line ->
            if (index > 0) Spacer(Modifier.height(2.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一格统计：上面是标签、下面是值。
 *
 * 用在设置页 / 权限页顶部，让用户先看到"现在是什么状态"，
 * 再决定要不要往下翻。
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
) {
    Column(
        modifier = modifier
            .background(tone.container(), RoundedCornerShape(Radius.md))
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tone.onContainer().copy(alpha = 0.72f),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = tone.onContainer(),
            maxLines = 1,
        )
    }
}

// ======================================================================
// 加载 / 进度
// ======================================================================

/**
 * 行内加载。
 *
 * 尺寸和文字基线对齐，出现在哪一行都不顶行高。
 * [text] 必须说"在做什么"，只转圈不说话等于没交代。
 */
@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ======================================================================
// 滑块
// ======================================================================

/**
 * 带标题和说明的滑块 —— 设置页里 5 个滑块共用一个实现。
 *
 * 标题固定左、当前值固定右（主色胶囊），说明固定一行灰字。
 * [note] 是"人话"，[fact] 是"这一档到底发什么参数"，两者都不是可选装饰。
 */
@Composable
fun SliderSetting(
    title: String,
    valueLabel: String,
    note: String?,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    fact: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(Radius.pill),
                    )
                    .padding(horizontal = Space.md, vertical = 3.dp),
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ),
        )

        if (!note.isNullOrBlank()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!fact.isNullOrBlank()) {
            Spacer(Modifier.height(Space.xxs))
            Text(
                text = fact,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (extra != null) extra()
    }
}

// ======================================================================
// 对话框
// ======================================================================

/**
 * 统一对话框。
 *
 * 三件事固定下来了：圆角 28、标题 20sp Medium、按钮用 Ghost 变体放在右下。
 * 危险操作（清空、删除）的确认键用红色 —— 只靠文案说"不可撤销"不够，
 * 颜色是最后一道防线。
 */
@Composable
fun AppAlertDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmDestructive: Boolean = false,
    dismissText: String = "取消",
    showDismiss: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.xl),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (content != null) {
                    if (body != null) Spacer(Modifier.height(Space.md))
                    content()
                }
            }
        },
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                    Text(
                        text = confirmText,
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            !confirmEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            confirmDestructive -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        },
        dismissButton = if (showDismiss) {
            {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = dismissText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            null
        },
    )
}

/** 一小段说明文字（用于表单下方、统计块下方） */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 横向排列的一组按钮（等宽） */
@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        content()
    }
}
