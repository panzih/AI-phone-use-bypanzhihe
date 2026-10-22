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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.shell.ShizukuBridge
import com.aiphone.assistant.ui.AutoReturnOnForegroundEffect
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
                // 需求 F 的触发源之一。用户点纸盒图标时落到的是这个页面而不是
                // 主界面，所以主界面挂了还不够 —— 见 ui/AutoReturnEffect.kt 的注释。
                AutoReturnOnForegroundEffect()
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

/** 连续几次抓不到就认定副屏已经没了（不再无限重试） */
private const val GIVE_UP_AFTER_FAILS = 20

/**
 * 「抓不到」的两种含义必须分开（2026-10-22 在 AVD 上踩到）：
 *   1. 副屏刚建好、上面**还没有任何画面**（应用还没切过来）—— ImageReader 里
 *      本来就没有帧，这时候说"副屏已经不在了"是瞎报，用户会以为功能坏了；
 *   2. 真的没了（binder 断/副屏被销毁）—— 连续几十秒都没有帧。
 * 所以前几次说"还没有画面"，久一点才下"副屏已经不在了"的结论。
 */

@Composable
private fun MirrorScreen(displayId: Int, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("正在取副屏画面 ...") }
    var okCount by remember { mutableIntStateOf(0) }
    var failCount by remember { mutableIntStateOf(0) }
    // 「重试」不是把计数器清零就完事 —— 那是自欺欺人（循环本来就在跑）。
    // 这里用一个 tick 当 LaunchedEffect 的 key，点一下真的重启整条抓帧循环。
    var retryTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    LaunchedEffect(displayId, retryTick) {
        if (displayId < 0) {
            status = "没有拿到副屏 id，退回去先创建副屏。"
            return@LaunchedEffect
        }
        // 只在 STARTED 期间抓帧：用户按 HOME / 锁屏 / 被别的应用盖住时停下来。
        // 以前这个 while 会一直跑到 composition 销毁 —— 页面在后台每秒一次
        // binder + 解码，纯烧电；副屏都没了还在抓。
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            failCount = 0
            while (isActive) {
                val bytes = ShizukuBridge.grabFrame(context)
                if (bytes.isEmpty()) {
                    failCount++
                    // 抓不到就**必须**把 shell 侧的原因带出来（binder 断开？
                    // 服务里没有 ImageReader？）—— 只说"抓不到"根本没法查
                    android.util.Log.w(
                        "MirrorActivity",
                        "抓帧失败（第 ${failCount} 次）：${ShizukuBridge.lastError ?: "无原因"}",
                    )
                    status = if (failCount >= GIVE_UP_AFTER_FAILS) {
                        "副屏已经没有画面了（连续 ${failCount} 次抓不到，约 " +
                            "${failCount * MirrorActivity.REFRESH_MS / 1000} 秒）。" +
                            "如果任务还在跑，可能是副屏上的应用没起来；点「重试」再试一次。"
                    } else {
                        "副屏上还没有画面（第 ${failCount} 次）。" +
                            "应用刚切过去时正常会有 1~3 秒空白，再等等。"
                    }
                    if (failCount >= GIVE_UP_AFTER_FAILS) break
                } else {
                    // 1080×2400 的 JPEG 解码放主线程是实打实的掉帧来源 ——
                    // 每秒一次，低端机上直接卡成幻灯片
                    val bmp = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (bmp != null) {
                        frame?.let { /* 旧帧交给 GC：ImageBitmap 包着 Bitmap，无法手动 recycle */ }
                        frame = bmp.asImageBitmap()
                        okCount++
                        failCount = 0
                        status = "副屏 $displayId · 已刷新 $okCount 帧 · " +
                            "${bmp.width}x${bmp.height} · ${bytes.size / 1024} KB"
                    } else {
                        failCount++
                        status = "拿到了 ${bytes.size} 字节，但解不出图片（第 ${failCount} 次）。"
                    }
                }
                delay(MirrorActivity.REFRESH_MS)
            }
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
                onClick = { retryTick++ },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.vd_mirror_retry))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
