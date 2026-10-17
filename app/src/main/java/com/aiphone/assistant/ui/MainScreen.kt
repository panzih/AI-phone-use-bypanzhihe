package com.aiphone.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onSettingsClick: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
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
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.main_title)) },
                    navigationIcon = {
                        IconButton(onClick = { toggleDrawer() }) {
                            Icon(Icons.Filled.Menu, stringResource(R.string.drawer_open))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showClearConfirm = true }, enabled = state.logs.isNotEmpty()) {
                            Icon(Icons.Filled.AddComment, "新开对话")
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Filled.Settings, stringResource(R.string.appbar_settings))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars)),
                )
            },
            bottomBar = {
                InputBar(
                    value = state.input,
                    onValueChange = onInputChange,
                    onSubmit = onSubmit,
                    onStop = onStop,
                    isRunning = state.isRunning,
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
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("新开对话") },
                text = { Text("确定清空当前对话和操作记录、新开一段吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = { showClearConfirm = false; onNewConversation() }) { Text("清空并新开") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun BrandCenter() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painter = painterResource(R.drawable.ic_box_logo), contentDescription = stringResource(R.string.main_logo_desc), modifier = Modifier.size(148.dp))
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.main_logo_caption), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text = stringResource(R.string.main_logo_by), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AppDrawer(
    onControlPhone: () -> Unit,
    onRecording: () -> Unit,
    onSchedules: () -> Unit,
    onPermissions: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
                Image(painter = painterResource(R.drawable.ic_box_logo), contentDescription = null, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            DrawerItem(icon = Icons.Filled.PhoneAndroid, title = stringResource(R.string.drawer_control_phone), onClick = onControlPhone)
            DrawerItem(icon = Icons.Filled.FiberManualRecord, title = stringResource(R.string.drawer_recording), onClick = onRecording)
            DrawerItem(icon = Icons.Filled.Schedule, title = stringResource(R.string.drawer_schedules), onClick = onSchedules)
            Spacer(Modifier.weight(1f))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            DrawerItem(icon = Icons.Filled.Lock, title = stringResource(R.string.drawer_permissions), onClick = onPermissions)
            DrawerItem(icon = Icons.Filled.Settings, title = stringResource(R.string.drawer_settings), onClick = onSettings)
            DrawerItem(icon = Icons.Filled.Info, title = stringResource(R.string.drawer_about), onClick = onAbout)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, title: String, onClick: () -> Unit, enabled: Boolean = true) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    ListItem(
        headlineContent = { Text(title, color = contentColor) },
        leadingContent = { Icon(icon, contentDescription = null, tint = contentColor) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(28.dp)).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    )
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
            top = if (caption != null) 10.dp else 4.dp,
            bottom = 2.dp,
        ),
    ) {
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
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
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = color,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
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
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Spacer(Modifier.width(48.dp))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // "指令"不用写出来（谁发的显而易见），定时任务要 —— 它是这条消息的来路
                if (caption != LABEL_COMMAND) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(text = text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    runningHint: String,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.input_hint)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSubmit() }),
                )
                Spacer(Modifier.width(8.dp))
                if (isRunning) {
                    FilledIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Stop, stringResource(R.string.input_stop))
                    }
                } else {
                    FilledIconButton(onClick = onSubmit, enabled = value.isNotBlank(), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.input_send))
                    }
                }
            }
            if (isRunning) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(text = runningHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
