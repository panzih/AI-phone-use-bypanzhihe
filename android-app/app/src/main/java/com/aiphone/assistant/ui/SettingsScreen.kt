package com.aiphone.assistant.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.touch.TouchKind

/**
 * 设置页。
 *
 * 结构按需求：
 *   1. 模型配置（第一行）
 *   2. 触控方式清单
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    model: ModelConfig,
    onModelChange: (ModelConfig) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ---------- 模型 ----------
            item {
                SectionHeader(stringResource(R.string.settings_section_model))
            }
            item {
                ModelSection(
                    model = model,
                    onModelChange = onModelChange,
                )
            }

            // ---------- 触控 ----------
            item {
                SectionHeader(stringResource(R.string.settings_section_touch))
            }
            item {
                TouchSummary()
            }

            // 当前可用的
            item {
                SubHeader(
                    stringResource(R.string.settings_touch_available),
                    TouchKind.availableNow.size,
                )
            }
            items(TouchKind.availableNow, key = { "ok_${it.id}" }) { kind ->
                TouchMethodRow(kind, usable = true)
            }

            // 需要额外组件的
            item {
                SubHeader(
                    stringResource(R.string.settings_touch_extra),
                    TouchKind.needsExtra.size,
                )
            }
            items(TouchKind.needsExtra, key = { "x_${it.id}" }) { kind ->
                TouchMethodRow(kind, usable = false)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SubHeader(text: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 模型配置。对应需求里"第一行应该是配置模型项的"。 */
@Composable
private fun ModelSection(
    model: ModelConfig,
    onModelChange: (ModelConfig) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model.baseUrl,
            onValueChange = { onModelChange(model.copy(baseUrl = it)) },
            label = { Text(stringResource(R.string.settings_model_provider)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = model.apiKey,
            onValueChange = { onModelChange(model.copy(apiKey = it)) },
            label = { Text(stringResource(R.string.settings_model_apikey)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = model.modelName,
            onValueChange = { onModelChange(model.copy(modelName = it)) },
            label = { Text(stringResource(R.string.settings_model_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = model.detail,
            onValueChange = { onModelChange(model.copy(detail = it)) },
            label = { Text(stringResource(R.string.settings_model_detail)) },
            supportingText = { Text("original 保留原图；low 压到 512×512") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

/** 触控方式总览，一句话说清现状。 */
@Composable
private fun TouchSummary() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "当前走无障碍通道：双指缩放、中文输入、多指手势都能直接做，" +
                "不需要任何额外组件。限制是截不到银行/支付类页面的内容" +
                "（系统安全保护），以及截图有约 1 秒的平台限流。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** 一行触控方式。 */
@Composable
private fun TouchMethodRow(kind: TouchKind, usable: Boolean) {
    ListItem(
        headlineContent = {
            Text(kind.label, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Column {
                Text(kind.description, style = MaterialTheme.typography.bodySmall)
                if (!usable && kind.extraNote != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = kind.extraNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (usable) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (usable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(22.dp),
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
