package com.aiphone.assistant.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    appVersion: String,
    autoCheck: Boolean,
    checking: Boolean,
    checkHint: String,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { AppHeader(appVersion) }
            item {
                UpdateSection(
                    autoCheck = autoCheck,
                    checking = checking,
                    checkHint = checkHint,
                    onAutoCheckChange = onAutoCheckChange,
                    onCheckUpdate = onCheckUpdate,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }
            item { LicenseSection() }
        }
    }
}

@Composable
private fun AppHeader(appVersion: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_box_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_about_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        InfoRow(stringResource(R.string.settings_about_version), appVersion.ifBlank { "?" })
        InfoRow(stringResource(R.string.settings_about_author), AppInfo.AUTHOR)
        InfoRow(stringResource(R.string.settings_about_license), AppInfo.LICENSE)
        if (AppInfo.REPO_URL.isNotBlank()) {
            InfoRow(stringResource(R.string.settings_about_repo), AppInfo.REPO_URL)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class LicenseItem(val name: String, val license: String, val note: String)

private val licenses = listOf(
    LicenseItem("AndroidX Compose", "Apache-2.0", "UI 框架"),
    LicenseItem("org.json", "JSON License", "JSON 解析"),
    LicenseItem("Our Free Model（参考）", "MIT", "免费模型网关协议参考"),
)

@Composable
private fun LicenseSection() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.about_licenses_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.about_licenses_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        licenses.forEach { item ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.name, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${item.license} · ${item.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 更新区：手动检查 + 启动时自动检查开关。
 *
 * 放在「关于」页而不是设置页：这里已经有版本号，用户想更新时先看的就是这一页。
 */
@Composable
private fun UpdateSection(
    autoCheck: Boolean,
    checking: Boolean,
    checkHint: String,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "更新",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onCheckUpdate, enabled = !checking) {
                Text(if (checking) "检查中…" else "检查更新", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (checkHint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = checkHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启动时自动检查", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "打开时查一次，有新版本会提示。关掉也能在这里手动查。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoCheck, onCheckedChange = onAutoCheckChange)
        }
    }
}
