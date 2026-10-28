package com.aiphone.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiphone.assistant.R
import com.aiphone.assistant.data.AppInfo
import com.aiphone.assistant.ui.design.AppButton
import com.aiphone.assistant.ui.design.AppScaffold
import com.aiphone.assistant.ui.design.ButtonVariant
import com.aiphone.assistant.ui.design.GroupCard
import com.aiphone.assistant.ui.design.GroupDivider
import com.aiphone.assistant.ui.design.HintText
import com.aiphone.assistant.ui.design.SectionTitle
import com.aiphone.assistant.ui.design.SettingRow
import com.aiphone.assistant.ui.design.Space
import com.aiphone.assistant.ui.design.StatusPill
import com.aiphone.assistant.ui.design.SwitchRow
import com.aiphone.assistant.ui.design.Tone

/**
 * 关于页。
 *
 * 三段：品牌（我是谁、什么版本）→ 更新（唯一的"做事"区）→ 开源许可（法律事实）。
 *
 * 品牌区做成**居中**的，这是全应用唯一居中排版的页面 —— 它没有任务，
 * 只有陈述，居中读起来像"名片"而不是"表单"。
 */
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
    AppScaffold(
        title = stringResource(R.string.about_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_back),
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(bottom = Space.huge),
        ) {
            item { AppHeader(appVersion) }

            item { SectionTitle("更新") }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    SettingRow(
                        title = "检查新版本",
                        subtitle = if (checkHint.isNotBlank()) checkHint else "会去仓库读最新版本的说明",
                        trailing = {
                            AppButton(
                                text = if (checking) "检查中" else "检查",
                                onClick = onCheckUpdate,
                                variant = ButtonVariant.Ghost,
                                loading = checking,
                                enabled = !checking,
                            )
                        },
                    )
                    GroupDivider()
                    SwitchRow(
                        title = "启动时自动检查",
                        subtitle = "默认关闭。开启后每次启动会向更新站发送 IP、User-Agent、设备标识（Android ID）、包名和版本号，用于版本分发与统计；关掉也能在这里手动查。",
                        checked = autoCheck,
                        onCheckedChange = onAutoCheckChange,
                    )
                }
            }

            item { SectionTitle("隐私说明") }
            item {
                HintText(
                    text = "纸盒没有广告或分析 SDK。下面是你需要知道的联网与本地数据行为：",
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs),
                )
            }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    SettingRow(
                        title = "更新检查",
                        subtitle = "手动检查或开启自动检查时，会向更新站发送 IP、User-Agent、Android ID、包名和版本号。服务端保存设备哈希以及检查/下载统计，用于版本分发。",
                    )
                    GroupDivider()
                    SettingRow(
                        title = "模型请求",
                        subtitle = "屏幕控件树、截图和你输入的文字会发送给你选择的模型接口。免费通道是社区非官方网关，首次开启前会单独告知风险。",
                    )
                    GroupDivider()
                    SettingRow(
                        title = "本地数据",
                        subtitle = "日志、截图、对话和记忆默认只留在本机；只有你主动导出时才会离开手机。导出前会提示可能包含验证码、聊天、支付页面等敏感内容。",
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.about_licenses_title)) }
            item {
                HintText(
                    text = stringResource(R.string.about_licenses_intro),
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs),
                )
            }
            item {
                GroupCard(modifier = Modifier.padding(horizontal = Space.lg)) {
                    licenses.forEachIndexed { index, item ->
                        if (index > 0) GroupDivider()
                        SettingRow(
                            title = item.name,
                            subtitle = "${item.license} · ${item.note}",
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(Space.xxl))
                Text(
                    text = "开源软件，欢迎 star 和提 issue。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 品牌头。
 *
 * 版本号做成胶囊挂在名字右边，而不是单独一行"版本：0.10.8" ——
 * 它是**属性**不是**信息**，跟着名字走才不会占掉一整行。
 */
@Composable
private fun AppHeader(appVersion: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_box_logo),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(Space.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(Space.sm))
            StatusPill(
                text = "v${appVersion.ifBlank { "?" }}",
                tone = Tone.Neutral,
                dot = false,
            )
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            text = stringResource(R.string.settings_about_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    Column(modifier = Modifier.padding(horizontal = Space.lg)) {
        GroupCard {
            InfoRow(stringResource(R.string.settings_about_author), AppInfo.AUTHOR)
            GroupDivider()
            InfoRow(stringResource(R.string.settings_about_license), AppInfo.LICENSE)
            if (AppInfo.REPO_URL.isNotBlank()) {
                GroupDivider()
                InfoRow(stringResource(R.string.settings_about_repo), AppInfo.REPO_URL)
            }
        }
    }
}

/** 一行"标签 → 值"。标签定宽 76dp，值可以长（仓库地址那种） */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private data class LicenseItem(val name: String, val license: String, val note: String)

private val licenses = listOf(
    LicenseItem("AndroidX Compose", "Apache-2.0", "UI 框架"),
    LicenseItem("org.json", "JSON License", "JSON 解析"),
    LicenseItem("Our Free Model（参考）", "MIT", "免费模型网关协议参考"),
)
