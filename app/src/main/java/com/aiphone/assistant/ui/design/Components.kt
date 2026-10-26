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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
 * 统一了四件以前各页各写一遍的事：顶栏样式、状态栏/刘海内边距、
 * 画布底色、Snackbar 宿主。少一处不一致，就少一个"这页看着怪怪的"。
 *
 * @param onBack null 表示没有返回键（主界面用抽屉，不走这个骨架）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
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
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = backLabel,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.displayCutout.union(WindowInsets.statusBars)
                ),
            )
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
 * 用主色的 14sp Medium，而不是全大写灰字 —— 中文没有大小写，
 * 靠"颜色+字重"而不是"字形变化"来和正文拉开。上方留 24dp 是刻意的：
 * 标题离上一组远、离自己这组近，分组关系才看得出来。
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(
            start = Space.lg,
            end = Space.lg,
            top = Space.xxl,
            bottom = Space.sm,
        ),
    )
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
 * 卡片 = 一个"这些行属于同一件事"的视觉承诺。之前所有行都直接摊在画布上，
 * 用户只能靠分割线猜哪几行是一组。
 *
 * 内边距只加垂直方向：水平留白由行自己控制，这样行的点击热区能通到卡片边缘
 * （贴着卡片边点也算点到，而不是点到"卡片的空白"）。
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
fun GroupDivider(modifier: Modifier = Modifier, indent: Int = 16) {
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
 * Android 手机上没有鼠标，但平板接键盘、DeX、以及"鼠标模式"下都真的有
 * 悬停和键盘焦点 —— 之前一律不管，表现为"鼠标划过去毫无反应，不知道哪块能点"。
 *
 * 三档透明度是刻意的：按下最重（0.08）、悬停/聚焦次之（0.04）、其余透明。
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
 * @param subtitle 次要说明，可为空。有它的时候行会变高，这是对的：
 *                 两行信息本来就该占两行的高度。
 * @param onClick  null 表示这一行不可点（只显示），此时不加悬停/按下反馈 ——
 *                 不可点的东西给出可点的反馈，等于骗人去点。
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
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val subColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Size.rowMinHeight)
            .interactiveSurface(enabled = enabled && onClick != null, shape = RoundedCornerShape(Radius.sm), onClick = onClick)
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
                Spacer(Modifier.height(2.dp))
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
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        modifier = modifier,
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
            .interactiveSurface(enabled = enabled, shape = RoundedCornerShape(Radius.sm), onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 下拉选择行。
 *
 * 用最朴素的 [DropdownMenu] 而不是 ExposedDropdownMenuBox —— 后者的
 * menuAnchor 签名在几个版本之间改过，而这个场景只需要"点一下弹列表"。
 * 不可用项灰显且点不动，理由见 PermissionsScreen 的注释。
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
                        .interactiveSurface(enabled = enabled, shape = RoundedCornerShape(Radius.sm))
                        .padding(horizontal = Space.sm, vertical = Space.xs),
                ) {
                    Text(
                        text = optionLabel(current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
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
 * [loading] 与 [enabled] 是两件事：加载中不改变视觉层级（还是那个主按钮），
 * 只是换成转圈并把内容替换掉 —— 用户看到的应该是"它正在做"，而不是"它变灰了"。
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
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
 * 只做三件事：固定圆角、固定错误呈现方式、固定"标签常驻"。
 * 标签常驻很重要 —— 用 placeholder 当标签的话，用户一开始打字标签就没了，
 * 打到一半忘了这一栏要填什么。
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
        // 留透明的话边框线会从浮起来的标签中间穿过去，字看着像被划掉了。
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.background,
            unfocusedContainerColor = MaterialTheme.colorScheme.background,
            disabledContainerColor = MaterialTheme.colorScheme.background,
        ),
        label = label?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
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
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(Space.md))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
        if (action != null) {
            Spacer(Modifier.height(Space.lg))
            action()
        }
    }
}
