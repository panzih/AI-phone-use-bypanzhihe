package com.aiphone.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.ui.design.AppAlertDialog
import com.aiphone.assistant.ui.design.Banner
import com.aiphone.assistant.ui.design.Radius
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.Tone
import com.aiphone.assistant.update.UpdateChecker

/**
 * 发现新版本时的弹窗。
 *
 * 三种状态共用这一个弹窗：还没开始下载（给按钮）、下载中（给进度）、下载失败（给原因）。
 * 不单独做「下载页」——一次更新只该有一个落点，用户点完就走。
 *
 * 视觉上的两个决定：
 *
 * 1. **下载中不隐藏进度条**，即使百分比还没拿到 —— 进度条本身就是「在动」的证据。
 * 2. **失败用整块提示而不是一行红字**。更新失败是需要用户做决定（重试/以后再说）
 *    的状态，不是一句可以扫过去的错误。
 */
@Composable
fun UpdateDialog(
    info: UpdateChecker.Info,
    progress: Int?,
    downloading: Boolean,
    error: String?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    val sizeText = info.sizeText

    AppAlertDialog(
        title = if (info.force) "需要更新后才能继续" else "发现新版本",
        onDismiss = { if (!info.force && !downloading) onLater() },
        confirmText = if (error != null) "重试" else "立即更新",
        onConfirm = onUpdate,
        confirmEnabled = !downloading,
        dismissText = "以后再说",
        showDismiss = !info.force,
        content = {
            // 版本号 + 安装包大小：用户决定「要不要现在装」就靠这两个数
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = "v${info.versionName}",
                    tone = Tone.Info,
                    dot = false,
                )
                if (sizeText.isNotBlank()) {
                    Spacer(Modifier.padding(horizontal = Space.xs))
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (info.changelog.isNotBlank()) {
                Spacer(Modifier.height(Space.md))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(Radius.md),
                        )
                        .padding(Space.md)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = info.changelog.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (downloading) {
                Spacer(Modifier.height(Space.lg))
                val pct = progress ?: 0
                if (progress == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (progress == null) "正在连接…" else "正在下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (progress != null) {
                        Text(
                            text = "$pct%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(Space.md))
                Banner(text = error, tone = Tone.Negative)
            }

            if (!info.force && !downloading) {
                Spacer(Modifier.height(Space.xs))
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.padding(start = 0.dp),
                ) {
                    Text(
                        text = "跳过这个版本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
