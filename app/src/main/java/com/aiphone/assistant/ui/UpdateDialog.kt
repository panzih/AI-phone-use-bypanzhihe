package com.aiphone.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.update.UpdateChecker

/**
 * 发现新版本时的弹窗。
 *
 * 三种状态共用这一个弹窗：还没开始下载（给按钮）、下载中（给进度）、下载失败（给原因）。
 * 不单独做「下载页」——一次更新只该有一个落点，用户点完就走。
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

    AlertDialog(
        onDismissRequest = { if (!info.force && !downloading) onLater() },
        title = {
            Text(
                text = if (info.force) "需要更新后才能继续" else "发现新版本",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(
                    text = buildString {
                        append("v${info.versionName}")
                        if (sizeText.isNotBlank()) append(" · $sizeText")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (info.changelog.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = info.changelog.trim(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (downloading) {
                    Spacer(Modifier.height(16.dp))
                    val pct = progress ?: 0
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (progress == null) "正在连接…" else "正在下载 $pct%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (!info.force && !downloading) {
                    Spacer(Modifier.height(6.dp))
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
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = !downloading) {
                Text(if (error != null) "重试" else "立即更新")
            }
        },
        dismissButton = if (info.force) {
            null
        } else {
            {
                TextButton(onClick = onLater, enabled = !downloading) {
                    Text("以后再说")
                }
            }
        },
    )
}
