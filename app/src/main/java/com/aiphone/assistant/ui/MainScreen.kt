package com.aiphone.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.ui.design.AppAlertDialog
import com.aiphone.assistant.ui.design.AppTheme
import com.aiphone.assistant.ui.design.Radius
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.Size
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.interactiveSurface

import kotlinx.coroutines.launch

/**
 * 主界面：一屏只回答一个问题 ——「我把事说给它，它办得怎么样」。
 *
 * 视觉上的三层，从下往上：
 *
 *   顶栏   我是谁、去哪（菜单）、两个不常用动作（新开对话、设置）
 *   对话   唯一的正文区，AI 说的话是正文，做的事是脚注
 *   输入栏 常驻在底部拇指区，运行中长出暂停/急停
 *
 * 配色和字号全部走 `ui.design` 的令牌，这一层不再出现任何裸颜色值。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                onControlPhone = { onControlPhone(); scope.launch { drawerState.close() } },
                onRecording = { onRecording(); scope.launch { drawerState.close() } },
                onSchedules = { onSchedules(); scope.launch { drawerState.close() } },
                onPermissions = { onPermissionsClick(); scope.launch { drawerState.close() } },
                onSettings = { onSettingsClick(); scope.launch { drawerState.close() } },
                onAbout = { onAboutClick(); scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.main_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { toggleDrawer() }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.drawer_open),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showClearConfirm = true },
                            enabled = state.logs.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddComment,
                                contentDescription = "新开对话",
                                tint = if (state.logs.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                },
                            )
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.appbar_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.displayCutout.union(WindowInsets.statusBars)
                    ),
                )
            },
            bottomBar = {
                InputBar(
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
                if (state.logs.isEmpty()) BrandCenter()
                else TaskOutputArea(state = state, modifier = Modifier.fillMaxSize())
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

/**
 * 空状态：品牌留在正中，四周全部空着。
 *
 * 这一屏没有任何"引导卡片""推荐任务"—— 用户打开就是为了说一句话，
 * 塞满东西只会把输入框的意义盖掉。字号刻意收着（18sp），
 * 让 Logo 是唯一的主体。
 */
@Composable
private fun BrandCenter() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Space.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_box_logo),
            contentDescription = stringResource(R.string.main_logo_desc),
            modifier = Modifier.size(128.dp),
        )
        Spacer(Modifier.height(Space.lg))
        Text(
            text = stringResource(R.string.main_logo_caption),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            text = stringResource(R.string.main_logo_by),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 侧边抽屉。
 *
 * 结构上刻意分成两段：上面是"干活的地方"（控制手机 / 定时任务），
 * 中间用 `weight(1f)` 撑开，下面是"看状态和改配置"（权限 / 设置 / 关于）。
 * 中间那段空白不是浪费 —— 它是分组，没有它八个入口会连成一片。
 */
@Composable
private fun AppDrawer(
    onControlPhone: () -> Unit,
    onRecording: () -> Unit,
    onSchedules: () -> Unit,
    onPermissions: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(296.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(topEnd = Radius.xl, bottomEnd = Radius.xl),
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Spacer(Modifier.height(Space.xl))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_box_logo),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(Space.md))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(Space.lg))
            DrawerItem(
                icon = Icons.Filled.PhoneAndroid,
                title = stringResource(R.string.drawer_control_phone),
                onClick = onControlPhone,
            )
            // 录制入口已下架（见 RECORDING_ENTRY_ENABLED 的说明）：功能还在，入口先收起来
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

            SectionTitle(text = "更多", modifier = Modifier.padding(top = 0.dp))
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
            Spacer(Modifier.height(Space.lg))
        }
    }
}

/** 抽屉里的一项：图标 20dp、文字 15sp，行高 48dp，圆角只在悬停时看得出来 */
@Composable
private fun DrawerItem(icon: ImageVector, title: String, onClick: () -> Unit, enabled: Boolean = true) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm)
            .interactiveSurface(
                enabled = enabled,
                shape = RoundedCornerShape(Radius.md),
                onClick = onClick,
            )
            .height(Size.touchTarget)
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color,
            modifier = Modifier.size(Size.iconMd),
        )
        Spacer(Modifier.width(Space.md))
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/**
 * 中间那块对话。
 *
 * ## 为什么是倒着排的
 *
 * 列表用倒序布局（[LazyColumn] 的 `reverseLayout` + `asReversed()`）：
 * **下标 0 就是最新一条，它贴着底部**。
 *
 * 这样"打开应用就停在最后一条"不需要任何额外的滚动动作 —— 那是列表的
 * **初始位置**。反过来做（正序 + 打开后滚一次）会有两个毛病：
 * 第一帧先画顶部、下一帧才跳下去，看得见闪一下；而且"滚到最后一条"
 * 只会把最后一条顶到视口**顶端**，它下面空一大片，像界面坏了。
 *
 * 还有一个顺带的好处：新消息永远是"从下面长出来"，不会把正在看的
 * 那几条往上挤走。
 */
@Composable
private fun TaskOutputArea(state: MainUiState, modifier: Modifier = Modifier) {
    val logs = state.logs
    val listState = rememberLazyListState()

    /**
     * 用户是不是"停在最新一条上"。
     *
     * 倒序列表里最新一条的下标是 0，所以"它完整露着、没被下边缘切掉"
     * 就等于"他还在底部"。
     *
     * 不用 `canScrollBackward`：内容不满一屏时它的锚点会被顶到第二条，
     * 把"用户根本没动"误判成"用户翻走了"，于是新消息一条都不跟。
     */
    val atTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val newest = info.visibleItemsInfo.firstOrNull()
            newest != null && newest.index == 0 && newest.offset >= info.viewportStartOffset
        }
    }

    /**
     * 组合阶段的一份快照。
     *
     * ⚠️ **必须在这里取，不能留到下面的 effect 里现读。**
     * 新消息的路径是"写进 logs → 重组 → 最后才排版"；等 effect 跑起来时
     * 排版已经完成，那时看到的已经是"下面多了一条没露出来的消息"，
     * 现读只会得到"用户翻走了"，新消息一次都跟不上。
     * 组合发生在排版之前，这里取到的才是"这条消息进来之前他停在哪"。
     */
    val wasAtTail = atTail

    // 新消息进来 → 跟着滚到底部；用户自己翻上去看历史时不去拽他
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && wasAtTail && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(0)
        }
    }

    // 用户点了发送（任务开跑）→ 无论刚才翻到了哪儿，都回到最新一条。
    // 这里用瞬移而不是动画：翻出去很远时，动画会把整段历史拖一遍，很怪
    LaunchedEffect(state.isRunning) {
        if (state.isRunning && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        // 间距串起"谁跟着谁"：动作和它的结果挨得紧，段落之间松一点
        contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        items(logs.asReversed(), key = { it.id }) { entry -> LogEntryRow(entry) }
    }
}

/**
 * 对话里"用户那一侧"的标签（[LABEL_COMMAND] / [LABEL_SCHEDULE]）。
 *
 * 手动输入的任务和到点自己跑起来的定时任务——两者都是用户（或者用户设好的
 * 规矩）说出口的话，显示成靠右的气泡，和 AI 的输出一眼分开。
 */
private val USER_SIDE_LABELS = setOf(LABEL_COMMAND, LABEL_SCHEDULE)

/**
 * 「第 3 步」「第 3 步 · 思考」「第 3 步 · 动作」这类标签**不显示**。
 *
 * 步数已经由底部那条进度（"AI 正在执行 · 第 3 / 30 步"）实时报着，
 * 一条消息再挂一个就只是噪音。带信息的照常显示（"第 3 步 · 失败"、
 * "端侧处理"、"防幻觉"…）。
 */
private val ROUTINE_STEP_LABEL = Regex("^第 \\d+ 步( · (思考|动作))?$")

/**
 * 只带步数、没有别的话的标签（"第 3 步"），对应"正在请求模型 ..."这种**过程播报**。
 *
 * 它不是 AI 说的话，所以不按正文显示，收成一行小灰字（见 [LogEntryRow]）。
 */
private val STATUS_STEP_LABEL = Regex("^第 \\d+ 步$")

/**
 * 一条对话记录。
 *
 * ## 显示上刻意"安静"
 *
 * 旧版把**每一条**都画成一张带底色的卡片，一屏里七八种颜色互相打架 ——
 * 用户真正要读的（AI 说了什么）和顺带要看的（AI 做了什么）分不出主次。
 * 现在按"谁在说话"分三类：
 *
 *   - AI 的话（THOUGHT）＝**正文**：不套气泡、不铺底色，就是一段字；
 *   - 动作 / 结果 / 系统消息 ＝ 一行小灰字，前面一个小图标，
 *     像日志一样从正文里"沉"下去；
 *   - 用户自己发的话（"指令"/"定时任务"）＝ 靠右的气泡，唯一有颜色的一块。
 *
 * 出错单独给红色（ERROR）：那是必须被看见的一条。
 */
@Composable
private fun LogEntryRow(entry: LogEntry) {
    val caption = entry.label?.takeIf { !ROUTINE_STEP_LABEL.matches(it) }
    // "正在请求模型 ..."：AI 没说话，只是过程在走 —— 收成一行小灰字
    val status = entry.kind == LogKind.THOUGHT && entry.label != null && STATUS_STEP_LABEL.matches(entry.label)
    when {
        caption != null && caption in USER_SIDE_LABELS ->
            CommandBubble(text = entry.text, caption = caption)

        status -> QuietLine(
            icon = Icons.Filled.Sync,
            title = null,
            body = entry.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            title = caption,
            body = entry.text,
            color = if (entry.kind == LogKind.ERROR) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** AI 说的话：正文样式，顶多带一行小灰字的上标（"端侧处理"之类） */
@Composable
private fun Narration(text: String, caption: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            // 带上标（"端侧处理"/"重发"）时上面空得多一点：那一行属于下面这段话，
            // 不能让它跟上一段贴得比自己的正文还近
            top = if (caption != null) Space.md else Space.xs,
            bottom = 2.dp,
        ),
    ) {
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 一行小灰字（动作 / 结果 / 系统消息 / 报错）。
 *
 * [title] 有值时当小标题、正文另起一行；没有时标题和正文并成一行 ——
 * 绝大多数动作只有一句话（"点击「搜索框」"），并排更紧凑。
 */
@Composable
private fun QuietLine(icon: ImageVector, title: String?, body: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp).size(Size.iconSm),
        )
        Spacer(Modifier.width(Space.sm))
        Column(modifier = Modifier.weight(1f)) {
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

/**
 * 用户发的那句话：靠右的气泡。
 *
 * 左边留一道固定空白，气泡**最大**只能长到那儿 —— 用
 * `weight(1f, fill = false)` 而不是写死最大宽度：短句子收着、
 * 长句子换行，屏幕变宽变窄都不用改数字。
 *
 * 气泡是整屏唯一有大面积底色的东西，因为它是唯一"用户亲手输入"的内容。
 */
@Composable
private fun CommandBubble(text: String, caption: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = 2.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Spacer(Modifier.width(48.dp))
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
                    Spacer(Modifier.height(2.dp))
                }
                Text(text = text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * 底部输入栏。
 *
 * 三件事必须一直成立：
 *
 * 1. **输入框永远在**（哪怕正在跑）—— 暂停的意义就是边看边补一句话；
 * 2. **主操作在右下角拇指区**，并且只有 48dp 的一个圆，不抢正文；
 * 3. **运行/暂停/停止是三种不同的颜色**：主色"发"、中性"停一下"、危险色"结束"。
 *    以前三个都是黑圆形，用户得读图标才知道哪个会真的把任务杀掉。
 */
@Composable
private fun InputBar(
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
    //
    // 光把 Activity 拉到前台是不够的：用户看到的还是一个没光标的输入框，
    // 得再点一下才能打字 —— 「补充」就白按了。
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
            modifier = Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = Space.md, vertical = Space.sm),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
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
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    },
                    maxLines = 4,
                    shape = RoundedCornerShape(Radius.lg),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSubmit() }),
                )
                Spacer(Modifier.width(Space.sm))
                if (isRunning) {
                    // 暂停期间必须有「发送」：用户停下来的目的就是补一句话，
                    // 没有发送键的话他只能靠输入法的回车，等于把功能藏起来
                    if (isPaused) {
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
                        Spacer(Modifier.width(Space.xs))
                    }
                    // 暂停 / 继续：中性色。它不是"结束"，不该长得像主操作
                    FilledTonalIconButton(
                        onClick = onPauseToggle,
                        modifier = Modifier.size(Size.touchTarget),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = AppTheme.accents.sunken,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = stringResource(
                                if (isPaused) R.string.input_resume else R.string.input_pause
                            ),
                            modifier = Modifier.size(Size.iconMd),
                        )
                    }
                    Spacer(Modifier.width(Space.xs))
                    // 急停：真的结束这一趟，不是"停一下"。危险色 + 实心，和暂停拉开
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(Size.touchTarget),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.input_stop),
                            modifier = Modifier.size(Size.iconMd),
                        )
                    }
                } else {
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

            if (isRunning) {
                Spacer(Modifier.height(Space.sm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Space.sm),
                ) {
                    if (isPaused) {
                        Box(
                            modifier = Modifier
                                .size(Size.iconSm)
                                .background(AppTheme.accents.warning, CircleShape)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Size.iconSm),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = if (isPaused) stringResource(R.string.running_paused_hint) else runningHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
