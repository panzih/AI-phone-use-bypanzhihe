package com.aiphone.assistant.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppInfo
import com.aiphone.assistant.data.AppSettings
import com.aiphone.assistant.data.ContextPolicy
import com.aiphone.assistant.data.ThinkingMode
import com.aiphone.assistant.llm.ModelListFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: MainUiState,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onProbeVirtualDisplay: () -> Unit,
    onLookAtVd: () -> Unit,
    onClearContext: () -> Unit,
    onExportLogs: () -> Unit,
    onDeleteLogs: () -> Unit,
) {
    val s = state.settings
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back))
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
            item { SectionHeader(stringResource(R.string.settings_section_model)) }
            item { ModelSection(s, onSettingsChange) }

            item { SectionDivider() }

            item { SectionHeader(stringResource(R.string.settings_section_developer)) }
            item { DeveloperWarning() }

            item {
                ActionRow(
                    title = stringResource(R.string.settings_clear_context),
                    subtitle = stringResource(R.string.settings_clear_context_sub),
                    onClick = onClearContext,
                )
            }

            item {
                ActionRow(
                    title = "运行副屏探针",
                    subtitle = "检查副屏能否读到控件树，结果写进日志",
                    onClick = onProbeVirtualDisplay,
                )
            }

            item {
                ActionRow(
                    title = stringResource(R.string.settings_vd_mirror),
                    subtitle = stringResource(R.string.settings_vd_mirror_sub),
                    onClick = onLookAtVd,
                )
            }

            item {
                StepsSliderRow(
                    maxSteps = s.maxSteps,
                    onChange = { onSettingsChange(s.copy(maxSteps = it)) },
                )
            }

            item {
                ContextPolicySlider(
                    policy = s.contextPolicy,
                    onChange = { onSettingsChange(s.copy(contextPolicy = it)) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_memory_enabled),
                    subtitle = stringResource(R.string.settings_memory_enabled_desc),
                    checked = s.memoryEnabled,
                    onCheckedChange = { onSettingsChange(s.copy(memoryEnabled = it)) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_save_logs),
                    subtitle = stringResource(R.string.settings_save_logs_desc),
                    checked = s.saveLogs,
                    onCheckedChange = { onSettingsChange(s.copy(saveLogs = it)) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_save_screenshots),
                    subtitle = stringResource(R.string.settings_save_screenshots_desc),
                    checked = s.saveScreenshots,
                    onCheckedChange = { onSettingsChange(s.copy(saveScreenshots = it)) },
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.settings_local_dismiss),
                    subtitle = stringResource(R.string.settings_local_dismiss_desc),
                    checked = s.localDialogDismiss,
                    onCheckedChange = { onSettingsChange(s.copy(localDialogDismiss = it)) },
                )
            }

            item { SectionDivider() }

            item {
                LogSection(
                    state = state,
                    onExportLogs = onExportLogs,
                    onDeleteLogs = onDeleteLogs,
                )
            }
        }
    }
}

@Composable
private fun ModelSection(
    s: AppSettings,
    onChange: (AppSettings) -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var fetching by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var showFreePicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onChange(s.copy(useFreeModel = false)) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_model_mode_custom)) }
            Button(
                onClick = { onChange(s.copy(useFreeModel = true)) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_model_mode_free)) }
        }

        Spacer(Modifier.height(12.dp))

        if (!s.useFreeModel) {
            OutlinedTextField(
                value = s.baseUrl,
                onValueChange = { onChange(s.copy(baseUrl = it)) },
                label = { Text(stringResource(R.string.settings_model_provider)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = s.apiKey,
                onValueChange = { onChange(s.copy(apiKey = it)) },
                label = { Text(stringResource(R.string.settings_model_apikey)) },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (keyVisible) R.string.settings_apikey_hide else R.string.settings_apikey_show
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))
        } else {
            Text(
                text = stringResource(R.string.settings_model_free_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = s.modelName,
            onValueChange = { onChange(s.copy(modelName = it)) },
            label = { Text(stringResource(R.string.settings_model_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.height(4.dp))

        if (!s.useFreeModel) {
            OutlinedButton(
                onClick = {
                    if (fetching) return@OutlinedButton
                    fetching = true
                    fetchError = null
                    models = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { ModelListFetcher.fetch(s.baseUrl, s.apiKey) }
                        }
                        fetching = false
                        result.onSuccess { list -> models = list }
                            .onFailure { e -> fetchError = e.message ?: e.javaClass.simpleName }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_model_fetching))
                } else {
                    Text(stringResource(R.string.settings_model_fetch))
                }
            }

            fetchError?.let { err ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_model_fetch_failed, err),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            OutlinedButton(
                onClick = { showFreePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_model_free_pick)) }
        }

        models?.let { list ->
            AlertDialog(
                onDismissRequest = { models = null },
                title = { Text(stringResource(R.string.settings_model_dialog_title)) },
                text = {
                    if (list.isEmpty()) {
                        Text(stringResource(R.string.settings_model_empty))
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn {
                            items(list) { m ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onChange(s.copy(modelName = m))
                                            models = null
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                ) { Text(m, style = MaterialTheme.typography.bodyLarge) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { models = null }) {
                        Text(stringResource(R.string.schedule_cancel))
                    }
                },
            )
        }

        if (showFreePicker) {
            AlertDialog(
                onDismissRequest = { showFreePicker = false },
                title = { Text(stringResource(R.string.settings_model_free_pick)) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(freeModels) { m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onChange(s.copy(modelName = m.id))
                                        showFreePicker = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Column {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(m.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showFreePicker = false }) {
                        Text(stringResource(R.string.schedule_cancel))
                    }
                },
            )
        }

        Spacer(Modifier.height(4.dp))

        ThinkingModeSlider(
            current = s.thinking,
            onChange = { onChange(s.copy(thinking = it)) },
        )

        Spacer(Modifier.height(4.dp))
    }
}

private val freeModels = listOf(
    FreeModel("deepseek-v4-flash-free", "DeepSeek V4 Flash"),
    FreeModel("mimo-v2.6-flash-free", "MiMo V2.6 Flash"),
    FreeModel("muse-spark-1.3-contributor-free", "Muse Spark 1.3"),
    FreeModel("space-bunny-free", "Space Bunny"),
    FreeModel("union-alpha", "Union Alpha"),
    FreeModel("ling-3.0-flash-fin-free", "Ling 3.0 Flash"),
)

private data class FreeModel(val id: String, val label: String)

@Composable
private fun ThinkingModeSlider(
    current: ThinkingMode,
    onChange: (ThinkingMode) -> Unit,
) {
    val options = ThinkingMode.entries
    val index = options.indexOf(current).coerceAtLeast(0)
    var draft by remember(index) { mutableFloatStateOf(index.toFloat()) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_model_thinking),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = options[draft.toInt().coerceIn(0, options.lastIndex)].label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                onChange(options[draft.toInt().coerceIn(0, options.lastIndex)])
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
        )

        Text(
            text = options[draft.toInt().coerceIn(0, options.lastIndex)].note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContextPolicySlider(
    policy: ContextPolicy,
    onChange: (ContextPolicy) -> Unit,
) {
    val options = ContextPolicy.entries
    val current = options.indexOf(policy).coerceAtLeast(0)
    var draft by remember(current) { mutableFloatStateOf(current.toFloat()) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_context_policy),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = options[draft.toInt().coerceIn(0, options.lastIndex)].label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                onChange(options[draft.toInt().coerceIn(0, options.lastIndex)])
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
        )

        Text(
            text = options[draft.toInt().coerceIn(0, options.lastIndex)].note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val STEPS_MIN = 10
private const val STEPS_UNLIMITED = 100

@Composable
private fun StepsSliderRow(
    maxSteps: Int,
    onChange: (Int) -> Unit,
) {
    val stored = if (maxSteps <= 0) STEPS_UNLIMITED
    else maxSteps.coerceIn(STEPS_MIN, STEPS_UNLIMITED - 1)

    var draft by remember(stored) { mutableFloatStateOf(stored.toFloat()) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_max_steps),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (draft >= STEPS_UNLIMITED) {
                    stringResource(R.string.settings_steps_unlimited)
                } else {
                    stringResource(R.string.settings_steps_value, draft.toInt())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                val v = draft.toInt()
                onChange(if (v >= STEPS_UNLIMITED) 0 else v)
            },
            valueRange = STEPS_MIN.toFloat()..STEPS_UNLIMITED.toFloat(),
            steps = STEPS_UNLIMITED - STEPS_MIN - 1,
        )

        Text(
            text = stringResource(R.string.settings_max_steps_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogSection(
    state: MainUiState,
    onExportLogs: () -> Unit,
    onDeleteLogs: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.log_delete_confirm_title)) },
            text = { Text(stringResource(R.string.log_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteLogs()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.log_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            },
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_export_logs),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_export_logs_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = state.logStats,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.memoryStats.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.memoryStats,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.autoCapStats.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.autoCapStats,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onExportLogs,
                enabled = !state.exporting,
                modifier = Modifier.weight(1f),
            ) {
                if (state.exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_working))
                } else {
                    Text(stringResource(R.string.settings_export_logs_button))
                }
            }
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.log_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
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
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun DeveloperWarning() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_developer_warning),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun <T> DropdownRow(
    title: String,
    subtitle: String?,
    current: T,
    options: List<T>,
    optionLabel: (T) -> String,
    optionEnabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        Box {
            TextButton(onClick = { open = true }) {
                Text(optionLabel(current))
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }

            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        enabled = optionEnabled(option),
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
