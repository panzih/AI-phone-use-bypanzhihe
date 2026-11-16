package com.aiphone.assistant.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiphone.assistant.llm.AsrClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 语音输入的三态。界面只按这个画麦克风/进度条。 */
enum class VoicePhase {
    IDLE,
    RECORDING,
    TRANSCRIBING,
}

/** 开始录音时定格的配置：中途改设置不影响这一趟 */
data class VoiceConfig(
    val baseUrl: String,
    val apiKey: String,
    val allowPublicCleartext: Boolean,
    val useFreeModel: Boolean = false,
)

/**
 * 语音输入的界面状态与流程。
 *
 * 状态放在 Compose 的 [mutableStateOf] 里，因为它是**界面状态**：
 * 麦克风的颜色、"正在听 0:12"、"正在识别…"都直接读这里。
 *
 * ## 流程
 *
 *   IDLE --点麦克风--> RECORDING --再点/到 120 秒--> TRANSCRIBING --成功--> IDLE
 *                                                          └--失败--> IDLE + 提示
 *
 * 失败不弹对话框：一句提示条 + 一条运行日志就够了 —— 语音识别失败最常见的是
 * Key 没填、网络不通，用户改完再点一次即可，不值得打断整个界面。
 */
class VoiceInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val onEvent: (String) -> Unit,
) {

    var phase by mutableStateOf(VoicePhase.IDLE)
        private set

    /** 已录秒数，给界面显示 */
    var seconds by mutableIntStateOf(0)
        private set

    /** 一次性提示（错误 / 结果），若干秒后自动消失 */
    var notice by mutableStateOf<String?>(null)
        private set

    private var recorder: VoiceRecorder? = null
    private var ticker: Job? = null
    private var noticeJob: Job? = null
    private var config: VoiceConfig? = null

    fun start(cfg: VoiceConfig) {
        if (phase != VoicePhase.IDLE) return

        if (cfg.useFreeModel) {
            notify(
                "语音输入只走 DeepSeek 官方接口：先到「设置 → 模型」切回自定义模型并填 API Key" +
                    "（免费通道不含语音识别）",
                log = true,
            )
            return
        }

        if (cfg.apiKey.isBlank()) {
            notify("语音输入需要 DeepSeek API Key：到「设置 → 模型」里填一个再试", log = true)
            return
        }

        val rec = VoiceRecorder()
        if (!rec.start(context)) {
            notify(
                "麦克风打不开：确认「权限」页的录音授权已开启，并检查是否被别的应用占用",
                log = true,
            )
            return
        }

        config = cfg
        recorder = rec
        phase = VoicePhase.RECORDING
        seconds = 0
        clearNotice()
        ticker = scope.launch {
            while (isActive && phase == VoicePhase.RECORDING) {
                delay(200)
                seconds = rec.elapsedSeconds()
                // 接口上限 120 秒，到了自动收尾，不让用户对着手机说到超时被拒
                if (seconds >= VoiceRecorder.MAX_SECONDS) {
                    stop()
                    break
                }
            }
        }
    }

    /** 结束录音并开始转写 */
    fun stop() {
        if (phase != VoicePhase.RECORDING) return
        phase = VoicePhase.TRANSCRIBING
        ticker?.cancel()
        ticker = null

        val rec = recorder
        val cfg = config
        recorder = null
        config = null
        if (rec == null || cfg == null) {
            phase = VoicePhase.IDLE
            return
        }

        // 停录音要 join 读线程，转写要走网络 —— 两件都放 IO，
        // 不让主线程为了收尾卡住一帧
        scope.launch {
            val wav = withContext(Dispatchers.IO) { rec.stop() }
            if (wav == null) {
                phase = VoicePhase.IDLE
                seconds = 0
                notify("没有录到声音，请靠近麦克风再说一次", log = true)
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                AsrClient.transcribe(
                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,
                    wav = wav,
                    allowPublicCleartext = cfg.allowPublicCleartext,
                )
            }
            when (result) {
                is AsrClient.Result.Ok -> {
                    onText(result.text)
                    notify("已转成文字，确认或修改后点发送")
                }

                is AsrClient.Result.Fail -> notify(result.message, log = true)
            }
            phase = VoicePhase.IDLE
            seconds = 0
        }
    }

    /** 离开界面 / Activity 销毁：丢弃当前录音，不留后台线程 */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        recorder?.cancel()
        recorder = null
        config = null
        phase = VoicePhase.IDLE
        seconds = 0
        clearNotice()
    }

    private fun clearNotice() {
        noticeJob?.cancel()
        noticeJob = null
        notice = null
    }

    private fun notify(message: String, log: Boolean = false) {
        notice = message
        if (log) onEvent(message)
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(8_000)
            if (notice == message) notice = null
        }
    }
}
