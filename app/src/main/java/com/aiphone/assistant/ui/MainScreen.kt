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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import kotlinx.coroutines.launch

/**
 * 主界面。
 *
 * 布局对应手稿：
 *   ┌─────────────────────────────┐
 *   │  ☰   操作手机                │  ← TopAppBar
 *   ├─────────────────────────────┤
 *   │                             │
 *   │          ╱╲                 │
 *   │         ╱  ╲   ← 纸盒 logo   │  ← 正中央
 *   │        ╲  ╱                 │
 *   │                             │
 *   │      AI 操作手机.            │
 *   │       By 潘纸盒              │
 *   ├─────────────────────────────┤
 *   │  [  告诉 AI 下一步做什么 ] ➤ │  ← 输入区
 *   └─────────────────────────────┘
 *
 * 中间那块**不放预览画面** —— 走无障碍通道时，被操作的 App 就在用户眼前，
 * 再在应用里显示一份截图没有意义，反而会和真正的界面打架。
 *
 * 只有任务跑起来、有了日志之后，中间才切成日志流，方便回看 AI 干了什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onSettingsClick: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onControlPhone: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun toggleDrawer() {
        scope.launch {
            if (drawerState.isClosed) drawerState.open() else drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                onControlPhone = {
                    onControlPhone()
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    onSettingsClick()
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            // 顶栏和底部各自处理系统栏内边距
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
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Filled.Settings, stringResource(R.string.appbar_settings))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
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
                    isRunning = state.isRunning,
                    runningHint = state.progress.ifBlank {
                        stringResource(R.string.running_hint)
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // 默认是 logo；有日志了才切成日志流
                if (state.logs.isEmpty()) {
                    BrandCenter()
                } else {
                    TaskOutputArea(state = state, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * 正中央的品牌区：纸盒 logo + 两行字。
 *
 * 这既是主界面的默认样子，也是应用图标本身 —— 同一个矢量，
 * 改了图标这里也跟着变。
 */
@Composable
private fun BrandCenter() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_box_logo),
            contentDescription = stringResource(R.string.main_logo_desc),
            modifier = Modifier.size(148.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.main_logo_caption),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.main_logo_by),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 左侧抽屉。
 *
 * 三项：
 *   1. 操作手机   ← 主功能
 *   2. 敬请期待   ← 占位，还没想好是什么
 *   3. 设置       ← 置底
 */
@Composable
private fun AppDrawer(
    onControlPhone: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 抽屉也要避开挖孔，否则标题会被摄像头挡
                .windowInsetsPadding(
                    WindowInsets.displayCutout.union(WindowInsets.statusBars)
                )
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_box_logo),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))

            DrawerItem(
                icon = Icons.Filled.PhoneAndroid,
                title = stringResource(R.string.drawer_control_phone),
                onClick = onControlPhone,
            )
            DrawerItem(
                icon = Icons.Filled.HourglassEmpty,
                title = stringResource(R.string.drawer_coming_soon),
                onClick = { /* 还没想好放什么，先占位 */ },
                enabled = false,
            )

            Spacer(Modifier.weight(1f))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            DrawerItem(
                icon = Icons.Filled.Settings,
                title = stringResource(R.string.drawer_settings),
                onClick = onSettings,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    ListItem(
        headlineContent = { Text(title, color = contentColor) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = contentColor)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(28.dp))
            .then(
                if (enabled) Modifier.clickable(onClick = onClick) else Modifier
            ),
    )
}

/**
 * 任务执行中的日志流。
 *
 * 显示 AI 的思考 / 动作 / 结果，也是"任务结束后回看刚才发生了什么"的地方。
 */
@Composable
private fun TaskOutputArea(
    state: MainUiState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.logs, key = { it.id }) { entry ->
            LogEntryRow(entry)
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val container = when (entry.kind) {
        LogKind.THOUGHT -> MaterialTheme.colorScheme.surfaceVariant
        LogKind.ACTION -> MaterialTheme.colorScheme.primaryContainer
        LogKind.RESULT -> MaterialTheme.colorScheme.secondaryContainer
        LogKind.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when (entry.kind) {
        LogKind.THOUGHT -> MaterialTheme.colorScheme.onSurfaceVariant
        LogKind.ACTION -> MaterialTheme.colorScheme.onPrimaryContainer
        LogKind.RESULT -> MaterialTheme.colorScheme.onSecondaryContainer
        LogKind.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (entry.label != null) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = onContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
            )
        }
    }
}

/** 底部输入区。 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    runningHint: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 导航栏和输入法取并集（union 是逐边取较大值）：
                // 键盘没弹时用导航栏高度，弹起来时用键盘高度。
                // 只写 navigationBars 的话输入框会被键盘盖住 —— 实际踩到过。
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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { onSubmit() }
                    ),
                )
                Spacer(Modifier.width(8.dp))

                if (isRunning) {
                    FilledIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Stop, stringResource(R.string.input_stop))
                    }
                } else {
                    FilledIconButton(
                        onClick = onSubmit,
                        enabled = value.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            stringResource(R.string.input_send),
                        )
                    }
                }
            }

            if (isRunning) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = runningHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
