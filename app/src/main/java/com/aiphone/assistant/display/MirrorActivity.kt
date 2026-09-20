package com.aiphone.assistant.display

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.shell.ShizukuBridge
import com.aiphone.assistant.ui.theme.AiPhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 副屏的"窗口"。
 *
 * ## 为什么要单独一个 Activity
 *
 * 用户要的是"像微信视频号那样独立弹出来的一个窗口"。做法是把副屏的
 * 画面**一张张截出来，贴在这个界面上** —— 也就是自己做一个投屏。
 *
 * 为什么不直接把应用起在副屏上就完事：因为副屏很可能根本不在物理屏幕上
 * 显示（overlay display 在某些设备上要靠"开发者选项 → 模拟副屏"才会
 * 显示出来）。我们要的是"在手机主屏上看着 AI 在副屏里操作"，
 * 所以必须自己把画面搬过来。
 *
 * ## 刷新是"抓帧循环"，不是投屏
 *
 * 每秒从 shell 服务要一张副屏当前帧（ImageReader 回读，见
 * ShizukuBridge.grabFrame）。比 MediaProjection 简单得多
 * （不需要用户授权 + 常驻通知），代价是每秒一帧的带宽和延迟。
 * 对"看着 AI 操作"这个用途够用。
 */
class MirrorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        setContent {
            AiPhoneTheme {
                MirrorScreen(displayId = displayId, onExit = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_DISPLAY_ID = "display_id"

        /** 刷新间隔。1 秒是"看着跟手"和"别太费电"之间的折中 */
        const val REFRESH_MS = 1000L

        fun intent(context: Context, displayId: Int): Intent =
            Intent(context, MirrorActivity::class.java)
                .putExtra(EXTRA_DISPLAY_ID, displayId)
    }
}

@Composable
private fun MirrorScreen(displayId: Int, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("正在取副屏画面 ...") }
    var okCount by remember { mutableIntStateOf(0) }
    var failCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(displayId) {
        if (displayId < 0) {
            status = "没有拿到副屏 id，退回去先创建副屏。"
            return@LaunchedEffect
        }
        while (isActive) {
            val bytes = ShizukuBridge.grabFrame(context)
            if (bytes.isEmpty()) {
                failCount++
                status = "抓不到副屏画面（第 $failCount 次失败）。" +
                    "副屏可能已经没了，或者 shell 服务断开了。"
            } else {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    // decode 之后可以立刻回收字节，画面留在 Bitmap 里
                    frame = bmp.asImageBitmap()
                    okCount++
                    status = "副屏 $displayId · 已刷新 $okCount 帧 · " +
                        "${bmp.width}x${bmp.height} · ${bytes.size / 1024} KB"
                } else {
                    failCount++
                    status = "拿到了 ${bytes.size} 字节，但解不出图片（第 $failCount 次）。"
                }
            }
            delay(MirrorActivity.REFRESH_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.statusBars))
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // 状态条
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = if (frame == null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        // 画面
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            if (f != null) {
                Image(
                    bitmap = f,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(R.string.vd_mirror_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 底部操作
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onExit, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vd_mirror_exit))
            }
            Button(
                onClick = { failCount = 0 },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.vd_mirror_retry))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
