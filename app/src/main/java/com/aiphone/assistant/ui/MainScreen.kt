package com.aiphone.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.ui.design.AppAlertDialog
import com.aiphone.assistant.ui.design.AppButton
import com.aiphone.assistant.ui.design.AppTheme
import com.aiphone.assistant.ui.design.Banner
import com.aiphone.assistant.ui.design.ButtonVariant
import com.aiphone.assistant.ui.design.IconBadge
import com.aiphone.assistant.ui.design.Radius
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.Size
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.Tone
import com.aiphone.assistant.ui.design.interactiveSurface
import kotlinx.coroutines.launch

/**
 * 主界面：一屏只回答一个问题 ——「我把事说给它，它办得怎么样」。
 *
 * 这一版把首屏从"一张 logo + 一个输入框"改成了**可以立刻开始的入口**：
 * 没开无障碍时先把该开的权限摆在最上面，开好了就给几句可以直接点的示例。
 * 用户不需要先想"我该说点什么"，点一句就能看到 AI 怎么动。
 *
 * 视觉上的三层，从下往上：
 *
 *   顶栏   我是谁、现在用哪条通道、去哪
 *   对话   唯一的正文区，AI 说的话是正文，做的事是脚注
 *   输入栏 常驻在底部拇指区，运行中长出暂停/急停
 *
 * 配色和字号全部走 `ui.design` 的令牌，这一层不再出现任何裸颜色值。
 */
@Composable
fun MainScreen(
    state: MainUiState,
    onSettingsClick: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onPauseToggle: () -> Unit,
    isPaused: Boolean,
    /** 悬浮窗按「补充」时主界面递增的时间戳；变化 = 把输入框亮起来等用户打字 */
    supplementTick: Long = 0L,
    onControlPhone: () -> Unit,
    onRecording: () -> Unit,
    onSchedules: () -> Unit,
    onNewConversation: () -> Unit,
    onPermissionsClick: () -> Unit,
    onAboutClick: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }

    fun toggleDrawer() {
        scope.launch {
            if (drawerState.isClosed) drawerState.open() else drawerState.close()
        }
    }

    fun closeDrawerThen(action: () -> Unit) {
        action()
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                useFreeModel = state.settings.useFreeModel,
                onControlPhone = { closeDrawerThen(onControlPhone) },
                onRecording = { closeDrawerThen(onRecording) },
                onSchedules = { closeDrawerThen(onSchedules) },
                onPermissions = { closeDrawerThen(onPermissionsClick) },
                onSettings = { closeDrawerThen(onSettingsClick) },
                onAbout = { closeDrawerThen(onAboutClick) },
            )
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                MainTopBar(
                    state = state,
                    onMenu = { toggleDrawer() },
                    onNewConversation = { showClearConfirm = true },
                    onSettings = onSettingsClick,
                )
            },
            bottomBar = {
                Composer(
                    value = state.input,
                    onValueChange = onInputChange,
                    onSubmit = onSubmit,
                    onStop = onStop,
                    onPauseToggle = onPauseToggle,
                    isRunning = state.isRunning,
                    isPaused = isPaused,
                    supplementTick = supplementTick,
                    runningHint = state.progress.ifBlank { stringResource(R.string.running_hint) },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (state.logs.isEmpty()) {
                    Welcome(
                        state = state,
                        onSuggestion = onInputChange,
                        onPermissionsClick = onPermissionsClick,
                    )
                } else {
                    Conversation(state = state, modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (showClearConfirm) {
            AppAlertDialog(
                title = "新开对话",
                body = "确定清空当前对话和操作记录、新开一段吗？此操作不可撤销。",
                confirmText = "清空并新开",
                confirmDestructive = true,
                onConfirm = {
                    showClearConfirm = false
                    onNewConversation()
                },
                onDismiss = { showClearConfirm = false },
            )
        }
    }
}

// ======================================================================
// 顶栏
// ======================================================================

/**
 * 顶栏：抽屉入口 + 品牌 + 当前通道 + 两个动作。
 *
 * 通道不再藏在设置页里 —— 用户随时得知道"这句话发给谁、要不要花钱"。
 * 权限没开时不在这里塞红字，留给下面的欢迎页说清楚。
 */
@Composable
private fun MainTopBar(
    state: MainUiState,
    onMenu: () -> Unit,
    onNewConversation: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
            .padding(horizontal = Space.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .interactiveSurface(shape = CircleShape, onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.drawer_open),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(Size.iconMd),
                )
            }
            Spacer(Modifier.width(Space.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(Space.sm))
                    StatusPill(
                        text = if (state.settings.useFreeModel) "免费通道" else "自定义",
                        tone = if (state.settings.useFreeModel) Tone.Positive else Tone.Neutral,
                        dot = false,
                    )
                }
                Text(
                    text = state.settings.modelName.ifBlank { "还没选模型" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNewConversation, enabled = state.logs.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.AddComment,
                    contentDescription = "新开对话",
                    tint = if (state.logs.isNotEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    },
                    modifier = Modifier.size(Size.iconMd),
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.appbar_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Size.iconMd),
                )
            }
        }
    }
}

// ======================================================================
// 欢迎页
// ======================================================================

/** 欢迎页上的示例。点一下只填进输入框，不自动发送 —— 发送必须是用户自己的动作。 */
private val SUGGESTIONS = listOf(
    "打开微信，给妈妈发一句「我到家了」",
    "看看今天天气，顺便提醒我带伞",
    "把屏幕亮度调到最高",
)

@Composable
private fun Welcome(
    state: MainUiState,
    onSuggestion: (String) -> Unit,
    onPermissionsClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.xl,
            end = Space.xl,
            top = Space.lg,
            bottom = Space.xxl,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Spacer(Modifier.height(Space.huge))
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.background,
                            )
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_box_logo),
                    contentDescription = stringResource(R.string.main_logo_desc),
                    modifier = Modifier.size(96.dp),
                )
            }
            Spacer(Modifier.height(Space.xxl))
            Text(
                text = "把手机交给它",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                text = "说一句话，AI 替你在手机上完成。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.xxl))
        }

        if (!state.authorized) {
            item {
                Banner(
                    text = "无障碍服务还没开。开了之后 AI 才能读屏幕、点按钮。",
                    tone = Tone.Warning,
                    action = {
                        AppButton(
                            text = "去开启",
                            onClick = onPermissionsClick,
                            variant = ButtonVariant.Ghost,
                        )
                    },
                )
                Spacer(Modifier.height(Space.xxl))
            }
        }

        item {
            Text(
                text = "试试这样说",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm),
            )
        }
        items(SUGGESTIONS) { text ->
            SuggestionCard(text = text, onClick = { onSuggestion(text) })
            Spacer(Modifier.height(Space.sm))
        }
    }
}

@Composable
private fun SuggestionCard(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.lg))
            .interactiveSurface(shape = RoundedCornerShape(Radius.lg), onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Size.iconSm),
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(Size.iconSm),
        )
    }
}

// ======================================================================
// 抽屉
// ======================================================================

/**
 * 侧边抽屉。
 *
 * 上面是"干活的地方"（操作手机 / 定时任务），下面是"看状态和改配置"
 * （权限 / 设置 / 关于）。中间用 `weight(1f)` 撑开 —— 空白是分组，
 * 没有它六个入口会连成一片。
 */
@Composable
private fun AppDrawer(
    useFreeModel: Boolean,
    onControlPhone: () -> Unit,
    onRecording: () -> Unit,
    onSchedules: () -> Unit,
    onPermissions: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(304.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(topEnd = Radius.xl, bottomEnd = Radius.xl),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Space.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.xl),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Radius.md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_box_logo),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(Space.md))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (useFreeModel) "免费通道 · 无需 Key" else "自定义模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle(text = "工作台", modifier = Modifier.padding(top = 0.dp))
            DrawerItem(
                icon = Icons.Filled.PhoneAndroid,
                title = stringResource(R.string.drawer_control_phone),
                onClick = onControlPhone,
            )
            if (RECORDING_ENTRY_ENABLED) {
                DrawerItem(
                    icon = Icons.Filled.FiberManualRecord,
                    title = stringResource(R.string.drawer_recording),
                    onClick = onRecording,
                )
            }
            DrawerItem(
                icon = Icons.Filled.Schedule,
                title = stringResource(R.string.drawer_schedules),
                onClick = onSchedules,
            )

            Spacer(Modifier.weight(1f))

            SectionTitle(text = "配置")
            DrawerItem(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.drawer_permissions),
                onClick = onPermissions,
            )
            DrawerItem(
                icon = Icons.Filled.Settings,
                title = stringResource(R.string.drawer_settings),
                onClick = onSettings,
            )
            DrawerItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.drawer_about),
                onClick = onAbout,
            )
            Text(
                text = "日志、截图和记忆默认只留在本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.lg),
            )
        }
    }
}

/** 抽屉里的一项：图标 20dp、文字 16sp，行高 48dp */
@Composable
private fun DrawerItem(icon: ImageVector, title: String, onClick: () -> Unit, enabled: Boolean = true) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .interactiveSurface(
                enabled = enabled,
                shape = RoundedCornerShape(Radius.md),
                onClick = onClick,
            )
            .height(Size.touchTarget + 4.dp)
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else color,
            modifier = Modifier.size(Size.iconMd),
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(Size.iconSm),
        )
    }
}

// ======================================================================
// 对话
// ======================================================================

/**
 * 中间那块对话。
 *
 * ## 为什么是倒着排的
 *
 * 列表用倒序布局（`reverseLayout` + `asReversed()`）：**下标 0 就是最新一条**，
 * 它贴着底部。这样"打开应用就停在最后一条"不需要任何滚动动作 —— 那是列表的
 * **初始位置**。
 *
 * 新消息永远是"从下面长出来"，不会把正在看的那几条往上挤走。
 */
@Composable
private fun Conversation(state: MainUiState, modifier: Modifier = Modifier) {
    val logs = state.logs
    val listState = rememberLazyListState()

    /**
     * 用户是不是"停在最新一条上"。
     *
     * 倒序列表里最新一条的下标是 0，所以"它完整露着、没被下边缘切掉"
     * 就等于"他还在底部"。
     */
    val atTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val newest = info.visibleItemsInfo.firstOrNull()
            newest != null && newest.index == 0 && newest.offset >= info.viewportStartOffset
        }
    }

    /**
     * 组合阶段的一份快照。必须在这里取，不能留到 effect 里现读：
     * 新消息的路径是"写进 logs → 重组 → 最后才排版"。
     */
    val wasAtTail = atTail

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && wasAtTail && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(state.isRunning) {
        if (state.isRunning && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        contentPadding = PaddingValues(
            start = Space.lg,
            end = Space.lg,
            top = Space.md,
            bottom = Space.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        items(logs.asReversed(), key = { it.id }) { entry -> LogEntryRow(entry) }
    }
}

/** 用户那一侧的消息（手动指令 / 定时任务） */
private val USER_SIDE_LABELS = setOf(LABEL_COMMAND, LABEL_SCHEDULE)

/** 「第 3 步」「第 3 步 · 思考」「第 3 步 · 动作」这类标签不显示，底部进度已经报着 */
private val ROUTINE_STEP_LABEL = Regex("^第 \\d+ 步( · (思考|动作))?$")

/** 只带步数、没有别的话的标签，对应"正在请求模型 ..."这种过程播报 */
private val STATUS_STEP_LABEL = Regex("^第 \\d+ 步$")

/**
 * 一条对话记录。
 *
 * 按"谁在说话"分三类：
 *
 *   - AI 的话（THOUGHT）＝ 正文：小头像 + 一段字，不套气泡；
 *   - 动作 / 结果 / 系统消息 ＝ 一行小灰字，前面一个图标；
 *   - 用户自己发的话 ＝ 靠右的气泡，唯一有大面积底色的一块。
 */
@Composable
private fun LogEntryRow(entry: LogEntry) {
    val caption = entry.label?.takeIf { !ROUTINE_STEP_LABEL.matches(it) }
    val status = entry.kind == LogKind.THOUGHT &&
        entry.label != null && STATUS_STEP_LABEL.matches(entry.label)
    when {
        caption != null && caption in USER_SIDE_LABELS ->
            CommandBubble(text = entry.text, caption = caption)

        status -> QuietLine(
            icon = Icons.Filled.Sync,
            tone = Tone.Neutral,
            title = null,
            body = entry.text,
        )

        entry.kind == LogKind.THOUGHT ->
            Narration(text = entry.text, caption = caption)

        else -> QuietLine(
            icon = when (entry.kind) {
                LogKind.ACTION -> Icons.Filled.TouchApp
                LogKind.RESULT -> Icons.Filled.TaskAlt
                LogKind.ERROR -> Icons.Filled.ErrorOutline
                else -> Icons.Filled.Info
            },
            tone = when (entry.kind) {
                LogKind.ERROR -> Tone.Negative
                LogKind.RESULT -> Tone.Positive
                LogKind.ACTION -> Tone.Info
                else -> Tone.Neutral
            },
            title = caption,
            body = entry.text,
        )
    }
}

/** AI 说的话：小头像 + 正文，顶多带一行小灰字的上标（"端侧处理"之类） */
@Composable
private fun Narration(text: String, caption: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (caption != null) Space.md else Space.sm, bottom = Space.xxs),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(
            icon = Icons.Filled.AutoAwesome,
            tone = Tone.Positive,
            size = 28.dp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(Space.sm))
        Column(modifier = Modifier.weight(1f)) {
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.xxs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 一行小灰字（动作 / 结果 / 系统消息 / 报错）。
 *
 * [title] 有值时当小标题、正文另起一行；没有时并成一行 ——
 * 绝大多数动作只有一句话（"点击「搜索框」"），并排更紧凑。
 */
@Composable
private fun QuietLine(icon: ImageVector, tone: Tone, title: String?, body: String) {
    val color = toneColor(tone)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(icon = icon, tone = tone, size = 24.dp)
        Spacer(Modifier.width(Space.sm))
        Column(modifier = Modifier.weight(1f).padding(top = 1.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
                Spacer(Modifier.height(1.dp))
            }
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Positive -> AppTheme.accents.success
    Tone.Warning -> AppTheme.accents.warning
    Tone.Negative -> MaterialTheme.colorScheme.error
    Tone.Info -> AppTheme.accents.info
}

/**
 * 用户发的那句话：靠右的气泡。
 *
 * 左边留一道固定空白，气泡**最大**只能长到那儿 —— 用
 * `weight(1f, fill = false)` 而不是写死最大宽度：短句子收着、
 * 长句子换行，屏幕变宽变窄都不用改数字。
 */
@Composable
private fun CommandBubble(text: String, caption: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xxs),
        horizontalArrangement = Arrangement.End,
    ) {
        Spacer(Modifier.width(56.dp))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(
                topStart = Radius.lg,
                topEnd = Radius.lg,
                bottomStart = Radius.lg,
                bottomEnd = Radius.xs,
            ),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Column(modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
                // "指令"不用写出来（谁发的显而易见），定时任务要 —— 它是这条消息的来路
                if (caption != LABEL_COMMAND) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(Space.xxs))
                }
                Text(text = text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ======================================================================
// 底部输入栏
// ======================================================================

/**
 * 底部输入栏。
 *
 * 三件事必须一直成立：
 *
 * 1. **输入框永远在**（哪怕正在跑）—— 暂停的意义就是边看边补一句话；
 * 2. **主操作在右下角拇指区**，并且只有 48dp 的一个圆，不抢正文；
 * 3. **运行/暂停/停止是三种不同的颜色**：主色"发"、中性"停一下"、危险色"结束"。
 */
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onPauseToggle: () -> Unit,
    isRunning: Boolean,
    isPaused: Boolean,
    supplementTick: Long = 0L,
    runningHint: String,
) {
    // 悬浮窗按「补充」→ 主界面把光标放进输入框并弹起键盘。
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(supplementTick) {
        if (supplementTick == 0L) return@LaunchedEffect
        runCatching { inputFocus.requestFocus() }
        keyboard?.show()
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .windowInsetsPadding(WindowInsets.displayCutout),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (isRunning) {
                RunningStrip(
                    isPaused = isPaused,
                    hint = runningHint,
                    onPauseToggle = onPauseToggle,
                    onStop = onStop,
                )
            }

            Row(
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).focusRequester(inputFocus),
                    placeholder = {
                        Text(
                            text = stringResource(
                                if (isRunning && isPaused) R.string.input_hint_paused
                                else R.string.input_hint
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        )
                    },
                    maxLines = 4,
                    shape = RoundedCornerShape(Radius.xl),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSubmit() }),
                )
                Spacer(Modifier.width(Space.sm))
                if (!isRunning || isPaused) {
                    FilledIconButton(
                        onClick = onSubmit,
                        enabled = value.isNotBlank(),
                        modifier = Modifier.size(Size.touchTarget),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.input_send),
                            modifier = Modifier.size(Size.iconMd),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 运行中的一条状态带：进度 + 暂停 + 急停。
 *
 * 暂停和急停都用**带文字的胶囊**，不再只是两个圆图标 ——
 * "哪个是停一下、哪个是结束"是这一屏最不能猜错的事。
 */
@Composable
private fun RunningStrip(
    isPaused: Boolean,
    hint: String,
    onPauseToggle: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.accents.sunken)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPaused) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(AppTheme.accents.warning, CircleShape)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(Space.sm))
        Text(
            text = if (isPaused) stringResource(R.string.running_paused_hint) else hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Space.sm))
        RunAction(
            icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = stringResource(if (isPaused) R.string.input_resume else R.string.input_pause),
            onClick = onPauseToggle,
        )
        Spacer(Modifier.width(Space.xs))
        RunAction(
            icon = Icons.Filled.Stop,
            label = stringResource(R.string.input_stop),
            onClick = onStop,
            danger = true,
        )
    }
}

@Composable
private fun RunAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val fg = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val bg = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(bg, RoundedCornerShape(Radius.pill))
            .interactiveSurface(shape = RoundedCornerShape(Radius.pill), onClick = onClick)
            .padding(horizontal = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(Size.iconSm),
        )
        Spacer(Modifier.width(Space.xxs))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
