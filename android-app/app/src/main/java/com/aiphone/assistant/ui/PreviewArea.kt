package com.aiphone.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R

/**
 * 中间那块：ADB 预览通道。
 *
 * 按需求：
 *   - 选 ADB  → 显示手机屏幕画面
 *   - 选无障碍 → 不显示画面，直接操作目标应用
 *
 * 三种状态各自有对应的界面：
 *   1. 无障碍通道  → 说明文字，不显示画面
 *   2. ADB 未就绪  → 原因 + 一个操作按钮（授权 / 安装 / 连接）
 *   3. ADB 就绪    → 显示画面
 */
@Composable
fun PreviewArea(
    state: MainUiState,
    onRefresh: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    AdbPreview(state.preview, onRefresh, onPrimaryAction)
}

@Composable
private fun AdbPreview(
    preview: PreviewState,
    onRefresh: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (preview.status) {
            ChannelStatus.READY -> {
                if (preview.hasFrame) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (preview.isProbablySecure) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.preview_secure_window),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                        FrameView(preview)
                    }
                } else {
                    HintBlock(
                        title = stringResource(R.string.preview_no_frame),
                        message = null,
                        primaryLabel = stringResource(R.string.preview_refresh),
                        onPrimary = onRefresh,
                    )
                }
            }

            ChannelStatus.CONNECTING -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.preview_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ChannelStatus.UNAVAILABLE -> {
                HintBlock(
                    title = stringResource(R.string.preview_unavailable),
                    message = preview.message,
                    primaryLabel = actionLabel(preview.message),
                    onPrimary = onPrimaryAction,
                )
            }

            ChannelStatus.UNKNOWN -> {
                HintBlock(
                    title = stringResource(R.string.preview_title),
                    message = null,
                    primaryLabel = stringResource(R.string.preview_connect),
                    onPrimary = onPrimaryAction,
                )
            }
        }
    }
}

/** 根据不可用原因，决定按钮该写什么 */
@Composable
private fun actionLabel(message: String?): String = when {
    message == null -> stringResource(R.string.preview_connect)
    message.contains("无障碍服务未开启") -> stringResource(R.string.preview_open_a11y)
    else -> stringResource(R.string.preview_reconnect)
}

/**
 * 显示一帧画面。
 *
 * 用屏幕真实宽高比约束，避免拉伸变形。
 * 手机屏幕通常是 9:20 左右的长条，所以预览区会是细长的。
 */
@Composable
private fun FrameView(preview: PreviewState) {
    val bmp = preview.frame ?: return
    val ratio = if (preview.screenWidth > 0 && preview.screenHeight > 0) {
        preview.screenWidth.toFloat() / preview.screenHeight.toFloat()
    } else {
        bmp.width.toFloat() / bmp.height.toFloat()
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.preview_title),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

/** 通用的提示块：标题 + 原因 + 一个操作按钮 */
@Composable
private fun HintBlock(
    title: String,
    message: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.PhoneAndroid,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = onPrimary) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(primaryLabel)
        }
    }
}
