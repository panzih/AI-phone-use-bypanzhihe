package com.aiphone.assistant

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import com.aiphone.assistant.a11y.AutoService
import com.aiphone.assistant.ui.ChannelStatus
import com.aiphone.assistant.ui.LogEntry
import com.aiphone.assistant.ui.LogKind
import com.aiphone.assistant.ui.MainScreen
import com.aiphone.assistant.ui.MainUiState
import com.aiphone.assistant.ui.ModelConfig
import com.aiphone.assistant.ui.PreviewState
import com.aiphone.assistant.ui.Screen
import com.aiphone.assistant.ui.SettingsScreen
import com.aiphone.assistant.ui.theme.AiPhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var controller: ChannelController

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边显示：内容铺到状态栏和导航栏下面，
        // 再靠 WindowInsets 给内容留出安全区。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        controller = ChannelController(applicationContext)

        setContent {
            AiPhoneTheme {
                AppRoot(
                    controller = controller,
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚去系统设置里开了无障碍，回来时状态就变了。
        // 这里不主动刷新，交给界面的重连按钮 —— 避免每次切后台回来都抓一帧。
    }

    /**
     * 跳到系统的无障碍设置页。
     *
     * 无障碍服务**不能通过代码申请**，必须引导用户手动去系统设置里开。
     * 这是系统设计，没有绕过的方法。
     */
    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * 界面根节点。
 *
 * 用本地状态驱动。之后要接执行循环时，换成 ViewModel 即可，各界面不用改。
 */
@Composable
private fun AppRoot(
    controller: ChannelController,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.CONTROL) }
    var input by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(ModelConfig()) }
    var preview by remember { mutableStateOf(PreviewState(status = ChannelStatus.UNKNOWN)) }
    val logs = remember { mutableListOf<LogEntry>().toMutableStateList() }

    /** 检查无障碍是否开启；开着就抓一帧 */
    fun connectAndPreview() {
        scope.launch {
            preview = preview.copy(status = ChannelStatus.CONNECTING, message = null)
            val result = controller.connect()
            preview = result
            if (result.status == ChannelStatus.READY) {
                controller.captureFrame { preview = it }
            }
        }
    }

    // 首次进入自动检查一次
    remember {
        connectAndPreview()
        true
    }

    when (screen) {
        Screen.CONTROL -> MainScreen(
            state = MainUiState(
                screen = Screen.CONTROL,
                input = input,
                isRunning = false,
                logs = logs,
                preview = preview,
                model = model,
            ),
            onSettingsClick = { screen = Screen.SETTINGS },
            onInputChange = { input = it },
            onSubmit = {
                if (input.isNotBlank()) {
                    logs.add(
                        LogEntry(
                            id = "u${logs.size}",
                            kind = LogKind.ACTION,
                            text = input,
                            label = "指令",
                        )
                    )
                    input = ""
                }
            },
            onStop = { },
            onControlPhone = { screen = Screen.CONTROL },
            onRefreshPreview = { connectAndPreview() },
            onPreviewAction = {
                if (!AutoService.isConnected) {
                    // 没开无障碍 → 带用户去系统设置
                    onOpenAccessibilitySettings()
                } else {
                    connectAndPreview()
                }
            },
        )

        Screen.SETTINGS -> SettingsScreen(
            model = model,
            onModelChange = { model = it },
            onBack = { screen = Screen.CONTROL },
        )
    }
}
