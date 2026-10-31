package com.aiphone.assistant.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ======================================================================
// 页面骨架
// ======================================================================

/**
 * 每个二级页共用的骨架。
 *
 * 统一了五件以前各页各写一遍的事：顶栏、状态栏/刘海内边距、
 * 画布底色、Snackbar 宿主、大标题排版。
 *
 * 标题不再挤在 56dp 的 AppBar 里，而是单独占一行大字号 ——
 * 页面一打开先看到"这是什么页"，而不是一行被压缩的小标题。
 *
 * @param onBack null 表示没有返回键（主界面用抽屉，不走这个骨架）
 */
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "返回",
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(
                        WindowInsets.displayCutout.union(WindowInsets.statusBars)
                    )
                    .padding(horizontal = Space.lg),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        Surface(
                            onClick = onBack,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = backLabel,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(Size.iconMd),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                }
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Space.xs),
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.xs),
                    )
                }
                Spacer(Modifier.height(Space.md))
            }
        },
        bottomBar = bottomBar,
        content = content,
    )
}

// ======================================================================
// 分组
// ======================================================================

/**
 * 分组小标题。
 *
 * 可选的图标 + 主色小字，上方留 28dp、下方留 10dp：
 * 标题离上一组远、离自己这组近，分组关系才看得出来。
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.lg, top = Space.xxl, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Size.iconSm),
            )
            Spacer(Modifier.width(Space.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/** 分组说明（跟在 [SectionTitle] 下面的一句灰字） */
@Composable
fun SectionNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = Space.lg,
            end = Space.lg,
            bottom = Space.sm,
        ),
    )
}

/**
 * 把若干行收进一张卡里。
 *
 * 卡片 = 一个"这些行属于同一件事"的视觉承诺。内边距只加垂直方向：
 * 水平留白由行自己控制，这样行的点击热区能通到卡片边缘。
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(vertical = Space.xs),
        content = content,
    )
}

/** 卡片内部的分隔线，左边缩进，和文字起点对齐 */
@Composable
fun GroupDivider(modifier: Modifier = Modifier, indent: Int = 20) {
    HorizontalDivider(
        modifier = modifier.padding(start = indent.dp, end = Space.lg),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

// ======================================================================
// 交互态：悬停 / 聚焦 / 按下
// ======================================================================

/**
 * 可点区域的三态背景。
 *
 * 按下最重（0.08）、悬停/聚焦次之（0.04）、其余透明。
 * 用 onSurface 加透明度而不是另一个灰，深浅色主题下都不用换色。
 */
@Composable
fun Modifier.interactiveSurface(
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Radius.md),
    onClick: (() -> Unit)? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()

    val base = MaterialTheme.colorScheme.onSurface
    val tint = when {
        !enabled -> Color.Transparent
        pressed -> base.copy(alpha = 0.08f)
        hovered || focused -> base.copy(alpha = 0.04f)
        else -> Color.Transparent
    }

    var result = this
        .clip(shape)
        .background(tint, shape)

    if (onClick != null) {
        result = result.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }
    return result
}

// ======================================================================
// 行
// ======================================================================

/**
 * 通用设置行 —— 全应用所有"一行文字 + 右边一个东西"都由它长出来。
 *
 * @param subtitle 次要说明，可为空。有它的时候行会变高。
 * @param onClick  null 表示这一行不可点（只显示），此时不加悬停/按下反馈。
 * @param showChevron 右侧补一个箭头，表示"点了会去别处"。
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    trailingGap: androidx.compose.ui.unit.Dp = Space.md,
    showChevron: Boolean = false,
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val subColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Size.rowMinHeight)
            .interactiveSurface(
                enabled = enabled && onClick != null,
                shape = RoundedCornerShape(Radius.md),
                onClick = onClick,
            )
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Space.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(Space.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(trailingGap))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(Space.xs))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(Size.iconMd),
            )
        }
    }
}

/** 开关行 —— 整行可点（点在文字上也算），热区比只点开关大得多 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        modifier = modifier,
        leading = leading,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

/** 动作行（点了执行某件事，不跳页），[destructive] 用危险色 */
@Composable
fun ActionRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Size.rowMinHeight)
            .interactiveSurface(
                enabled = enabled,
                shape = RoundedCornerShape(Radius.md),
                onClick = onClick,
            )
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Space.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(Space.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showChevron) {
            Spacer(Modifier.width(Space.sm))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(Size.iconMd),
            )
        }
    }
}

/**
 * 下拉选择行。
 *
 * 用最朴素的 [DropdownMenu] 而不是 ExposedDropdownMenuBox —— 后者的
 * menuAnchor 签名在几个版本之间改过，而这个场景只需要"点一下弹列表"。
 */
@Composable
fun <T> DropdownRow(
    title: String,
    current: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    optionEnabled: (T) -> Boolean = { true },
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }

    SettingRow(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        modifier = modifier,
        onClick = { open = true },
        trailing = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = Space.md, vertical = Space.xs),
                ) {
                    Text(
                        text = optionLabel(current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(Space.xxs))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(Size.iconMd),
                    )
                }

                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    shape = RoundedCornerShape(Radius.md),
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = optionLabel(option),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (optionEnabled(option)) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    },
                                )
                            },
                            enabled = optionEnabled(option),
                            onClick = {
                                onSelect(option)
                                open = false
                            },
                        )
                    }
                }
            }
        },
    )
}

// ======================================================================
// 分段控件
// ======================================================================

/**
 * 同一件事的多个取值。
 *
 * 选中项用卡片色浮起来，而不是变蓝 —— 它读起来是"当前这个"，
 * 而不是"推荐你点这个"。
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surface
                        else Color.Transparent
                    )
                    .interactiveSurface(
                        enabled = enabled,
                        shape = RoundedCornerShape(Radius.sm),
                        onClick = { if (!isSelected) onSelect(option) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        isSelected -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ======================================================================
// 按钮
// ======================================================================

enum class ButtonVariant { Primary, Secondary, Ghost, Danger }

/**
 * 全应用唯一的按钮。
 *
 * 四种变体对应四个语义层级，不允许页面自己配色：
 *
 *   Primary    一屏只有一个（"开始跑"、"保存"）
 *   Secondary  并列的次要动作
 *   Ghost      行内的小动作、对话框里的"取消"
 *   Danger     不可撤销的破坏性动作
 *
 * [loading] 与 [enabled] 是两件事：加载中不改变视觉层级，只是换成转圈。
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(Radius.md)
    val contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.md)
    val effectiveEnabled = enabled && !loading

    val body: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Size.iconSm),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(Space.sm))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Size.iconMd))
            Spacer(Modifier.width(Space.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }

    when (variant) {
        ButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = effectiveEnabled,
            shape = shape,
            contentPadding = contentPadding,
            modifier = modifier.heightIn(min = Size.buttonHeight),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            ),
            content = body,
        )

        ButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = effectiveEnabled,
            shape = shape,
            contentPadding = contentPadding,
            modifier = modifier.heightIn(min = Size.buttonHeight),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            ),
            content = body,
        )

        ButtonVariant.Ghost -> TextButton(
            onClick = onClick,
            enabled = effectiveEnabled,
            shape = shape,
            contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.sm),
            modifier = modifier.heightIn(min = Size.touchTarget),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            ),
            content = body,
        )

        ButtonVariant.Danger -> OutlinedButton(
            onClick = onClick,
            enabled = effectiveEnabled,
            shape = shape,
            contentPadding = contentPadding,
            modifier = modifier.heightIn(min = Size.buttonHeight),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            ),
            content = body,
        )
    }
}

// ======================================================================
// 输入
// ======================================================================

/**
 * 统一输入框。
 *
 * 固定圆角、固定错误呈现方式、固定"标签常驻"。
 * 标签常驻很重要 —— 用 placeholder 当标签的话，用户一开始打字标签就没了。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    enabled: Boolean = true,
    error: String? = null,
    supportingText: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isError = error != null,
        singleLine = singleLine,
        maxLines = maxLines,
        shape = RoundedCornerShape(Radius.md),
        textStyle = MaterialTheme.typography.bodyLarge,
        // 容器色必须显式给：OutlinedTextField 靠它把边框在标签位置"挖"出一个缺口。
        // 留透明的话边框线会从浮起来的标签中间穿过去。
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        label = label?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        placeholder = placeholder?.let { {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        supportingText = when {
            error != null -> {
                { Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            supportingText != null -> {
                { Text(supportingText, style = MaterialTheme.typography.bodySmall) }
            }
            else -> null
        },
    )
}

/** 一屏里没有东西时的统一空状态（图标 + 一句解释 + 可选的下一步） */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xxl, vertical = Space.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, size = 64.dp)
            Spacer(Modifier.height(Space.lg))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Space.lg))
            action()
        }
    }
}

/** 圆形图标底：给行、空状态、权限页一个统一的"图标位" */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    size: androidx.compose.ui.unit.Dp = Size.badge,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tone.container())
            .border(1.dp, tone.color().copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tone.color(),
            modifier = Modifier.size(if (size >= 56.dp) Size.iconLg else Size.iconMd),
        )
    }
}
