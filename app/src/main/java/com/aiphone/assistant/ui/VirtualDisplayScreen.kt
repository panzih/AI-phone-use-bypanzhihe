package com.aiphone.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.display.VirtualDisplayManager
import com.aiphone.assistant.shell.AdbShell
import com.aiphone.assistant.shell.ShizukuBridge
import kotlinx.coroutines.launch

/**
 * 副屏。
 *
 * ## 这个页面当前的作用：**先把"能不能"验证出来**
 *
 * 副屏这条路压在三条系统命令上（建屏 / 按屏截图 / 按屏注入），而它们在
 * 不同 ROM 上的表现不一样。我这边没有真机可试，所以这里做成一排可点的
 * 按钮，每步都把**命令的原始输出**摆出来：
 *
 *   建副屏 → 试截一张 → 试点一下 → 打开窗口看画面 → 撤副屏
 *
 * 这样装到手机上点几下就能知道哪一步成立、哪一步不成立，
 * 不用连电脑跑命令，也不用猜。
 *
 * ## 为什么留一个"手动填 id"
 *
 * 系统没有"我刚建的屏 id 是几"这种查询，只能从 `dumpsys display` 里
 * 推断，而各 ROM 的格式不一致。推断错了的话，用户看着下面的输出
 * 自己填一个数字就能继续 —— 比假装推断一定对要诚实。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualDisplayScreen(
    onBack: () -> Unit,
    onOpenMirror: (Int) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(VirtualDisplayManager.State()) }
    var shizuku by remember { mutableStateOf(ShizukuBridge.state(context)) }
    var busy by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf("") }
    var stepResult by remember { mutableStateOf("") }

    fun refreshState() {
        scope.launch {
            busy = true
            shizuku = ShizukuBridge.state(context)
            state = runCatching { VirtualDisplayManager.refresh(context) }
                .getOrElse { VirtualDisplayManager.State(message = "取显示器列表失败：${it.message}") }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vd_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.settings_back),
                        )
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
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ---------- Shizuku 状态 ----------
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.vd_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.vd_shizuku, shizuku.label),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (shizuku == ShizukuBridge.State.READY) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (shizuku == ShizukuBridge.State.NO_PERMISSION) {
                            OutlinedButton(onClick = {
                                ShizukuBridge.requestPermission()
                                stepResult = "已弹出授权框。允许之后点「刷新状态」。"
                            }) {
                                Text(stringResource(R.string.vd_request_permission))
                            }
                        } else {
                            OutlinedButton(onClick = { refreshState() }, enabled = !busy) {
                                Text(stringResource(R.string.vd_refresh))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            item { SectionDivider() }

            // ---------- 操作 ----------
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.vd_steps_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                stepResult = "正在创建副屏 ..."
                                val st = runCatching { VirtualDisplayManager.create(context) }
                                    .getOrElse {
                                        VirtualDisplayManager.State(message = "失败：${it.message}")
                                    }
                                state = st
                                stepResult = st.message
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.vd_create)) }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val id = state.displayId
                                if (id == null) {
                                    stepResult = "还不知道副屏 id，先点「创建副屏」，或者在下面手动填一个。"
                                    return@OutlinedButton
                                }
                                scope.launch {
                                    busy = true
                                    stepResult = "正在截副屏 $id ..."
                                    val bytes = AdbShell.screenshotDisplay(context, id)
                                    stepResult = if (bytes.isEmpty()) {
                                        "❌ 截不到副屏 $id。这台设备可能不支持 screencap -d，" +
                                            "或者 id 不对（试试手动填别的数字）。"
                                    } else {
                                        "✅ 截到 ${bytes.size / 1024} KB 的 PNG，" +
                                            "说明能在应用里显示副屏画面。"
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.vd_test_capture)) }

                        OutlinedButton(
                            onClick = {
                                val id = state.displayId
                                if (id == null) {
                                    stepResult = "还不知道副屏 id。"
                                    return@OutlinedButton
                                }
                                scope.launch {
                                    busy = true
                                    stepResult = "正在往副屏 $id 中心点一下 ..."
                                    val w = state.displays.firstOrNull { it.id == id }?.width ?: 1080
                                    val h = state.displays.firstOrNull { it.id == id }?.height ?: 2400
                                    val out = AdbShell.tap(context, id, w / 2, h / 2)
                                    stepResult = if (out.contains("错误：") || out.contains("Exception")) {
                                        "❌ 注入失败：$out"
                                    } else {
                                        "✅ 命令被系统接受了（画面有没有反应要自己看）。" +
                                            "注意：**无障碍做不到这个**，副屏的点击只能靠这条命令。"
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.vd_test_tap)) }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = {
                                val id = state.displayId
                                if (id == null) stepResult = "还没有副屏 id。"
                                else onOpenMirror(id)
                            },
                            enabled = !busy && state.displayId != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.vd_open_mirror)) }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    stepResult = "正在撤销副屏 ..."
                                    val st = runCatching { VirtualDisplayManager.remove(context) }
                                        .getOrElse {
                                            VirtualDisplayManager.State(message = "失败：${it.message}")
                                        }
                                    state = st
                                    stepResult = st.message
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.vd_remove)) }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 手动指定 id（推断错了时的兜底）
                    OutlinedTextField(
                        value = manualId,
                        onValueChange = { manualId = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text(stringResource(R.string.vd_manual_id)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val id = manualId.toIntOrNull()
                            if (id == null) {
                                stepResult = "填一个数字。"
                            } else {
                                state = state.copy(displayId = id)
                                stepResult = "已把副屏 id 设为 $id。"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.vd_use_manual_id)) }

                    Spacer(Modifier.height(12.dp))
                }
            }

            // ---------- 结果 ----------
            if (stepResult.isNotBlank()) {
                item { SectionDivider() }
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.vd_result_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stepResult,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // ---------- 原始输出 ----------
            item { SectionDivider() }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.vd_raw_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.vd_raw_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.raw.ifBlank { "（空）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .padding(10.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(4.dp))
}
