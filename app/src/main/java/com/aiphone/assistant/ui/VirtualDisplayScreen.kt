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
import android.graphics.BitmapFactory
import com.aiphone.assistant.BuildConfig
import com.aiphone.assistant.R
import com.aiphone.assistant.display.VirtualDisplayManager
import com.aiphone.assistant.shell.AdbShell
import com.aiphone.assistant.shell.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 副屏。
 *
 * ## 当前状态：**暂未开放**
 *
 * 副屏功能依赖 Shizuku/ADB 权限，当前版本以无障碍为主，
 * 副屏相关功能暂未配置完成，所有按钮禁用。
 *
 * 代码保留，后续版本再开放。
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
    var destroyMsg by remember { mutableStateOf("") }

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

    /**
     * 0.5.0 自检：在 shell 服务进程里把 建屏→副屏起应用→抓帧→触控→
     * 帧差异 整条闭环跑一遍，每步原始输出都收集到 [stepResult]。
     */
    fun runSelfTest() {
        scope.launch {
            busy = true
            val sb = StringBuilder()
            try {
                sb.appendLine("① createDisplay（flags=0x1fd49）…")
                val id = ShizukuBridge.createDisplay(context)
                if (id < 0) {
                    sb.appendLine("   失败：${ShizukuBridge.lastError}")
                    stepResult = sb.toString()
                    busy = false
                    return@launch
                }
                sb.appendLine("   → displayId=$id")

                val dd = ShizukuBridge.run(context, "dumpsys display | grep -iE 'PaperBoxVD|Display Id'")
                sb.appendLine("② dumpsys display：")
                sb.appendLine(dd.trim().ifBlank { "（未 grep 到）" })

                ShizukuBridge.run(context, "am force-stop com.android.settings")
                val so = ShizukuBridge.startOnDisplay(context, "com.android.settings/.Settings", id)
                sb.appendLine("③ startOnDisplay：")
                sb.appendLine(so.trim())
                kotlinx.coroutines.delay(3000)

                val ct = ShizukuBridge.run(
                    context,
                    "dumpsys activity containers | grep -nE \"Display $id name|Task=|settings\"",
                )
                sb.appendLine("④ containers：")
                sb.appendLine(ct.trim())

                val before = ShizukuBridge.grabFrame(context)
                sb.appendLine("⑤ grabFrame 点击前：${before.size} 字节")

                ShizukuBridge.run(context, "input -d $id tap 540 745")
                kotlinx.coroutines.delay(3000)
                val after = ShizukuBridge.grabFrame(context)
                sb.appendLine("⑥ input tap 后 grabFrame：${after.size} 字节")

                val ratio = withContext(Dispatchers.IO) {
                    runCatching { frameDiffRatio(before, after) }.getOrDefault(-1.0)
                }
                sb.appendLine("⑦ 点击前后帧差异率：${if (ratio < 0) "计算失败" else "%.2f%%".format(ratio * 100)}")

                withContext(Dispatchers.IO) {
                    runCatching {
                        context.cacheDir.resolve("selftest_before.png").writeBytes(before)
                        context.cacheDir.resolve("selftest_after.png").writeBytes(after)
                    }
                }
                sb.appendLine("（前后帧已存到应用 cache：selftest_before/after.png）")
            } catch (t: Throwable) {
                sb.appendLine("自检异常：${t.javaClass.simpleName} ${t.message}")
            }
            stepResult = sb.toString()
            busy = false
        }
    }

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
            // ---------- 未开放提示 ----------
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.vd_not_ready_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.vd_not_ready_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            item { SectionDivider() }

            // ---------- 操作（全部禁用）----------
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
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.vd_create)) }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.vd_test_capture)) }

                        OutlinedButton(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.vd_test_tap)) }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.vd_open_mirror)) }

                        OutlinedButton(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.vd_remove)) }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = manualId,
                        onValueChange = { },
                        label = { Text(stringResource(R.string.vd_manual_id)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = false,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.vd_use_manual_id)) }

                    Spacer(Modifier.height(12.dp))
                }
            }

            // ---------- 0.5.0 自检（仅调试可见）----------
            if (BuildConfig.DEBUG) {
                item { SectionDivider() }
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "0.5.0 自检（仅调试可见）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { runSelfTest() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (busy) "自检中…" else "运行 0.5.0 自检") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val r = ShizukuBridge.destroyDisplay(context)
                                    destroyMsg = when {
                                        r >= 0 -> "已销毁副屏 displayId=$r"
                                        r == -1 -> "当前没有副屏可销毁"
                                        else -> "销毁失败（$r）：${ShizukuBridge.lastError ?: ""}"
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("销毁副屏") }
                        if (destroyMsg.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                destroyMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
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

/**
 * 两帧 PNG 的像素差异率（每隔几个像素采样，省 CPU）。
 * @return 0~1；解码失败返回 -1
 */
private fun frameDiffRatio(a: ByteArray, b: ByteArray): Double {
    val b1 = BitmapFactory.decodeByteArray(a, 0, a.size) ?: return -1.0
    val b2 = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return -1.0
    val w = minOf(b1.width, b2.width)
    val h = minOf(b1.height, b2.height)
    var diff = 0L
    var total = 0L
    val step = 6
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            total++
            if (b1.getPixel(x, y) != b2.getPixel(x, y)) diff++
            x += step
        }
        y += step
    }
    return if (total == 0L) -1.0 else diff.toDouble() / total
}
