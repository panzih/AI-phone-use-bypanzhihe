package com.aiphone.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * 麦克风录音，输出 [WavEncoder] 包好的 PCM16 WAV。
 *
 * ## 参数为什么写死 16k 单声道
 *
 * DeepSeek ASR 支持的采样率里 16000 是语音识别最常用的，也是体积最小的
 * 一档：16000 × 2 字节 = 32 KB/s，120 秒上限约 3.8 MB，离接口 8 MiB
 * 的限制还有余量。双声道只会让左声道被识别，没有收益。
 *
 * ## 生命周期
 *
 * [start] 起一个后台线程持续 `AudioRecord.read`，[stop] 停掉并返回 WAV。
 * 读线程只负责 append，写和读之间靠 `Thread.join` 建立 happens-before ——
 * 不在锁里 join，避免读线程等锁、stop 等线程的死锁。
 *
 * 单例的 [`VoiceInputController`] 保证同一时刻只有一个实例在录；这个类
 * 自己不做跨实例互斥（系统麦克风本来也只允许一个前台应用占用）。
 */
class VoiceRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
    private val maxSeconds: Int = MAX_SECONDS,
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 120

        /** 太短的录音大概率是误触，直接当"没录到" */
        const val MIN_PCM_BYTES = SAMPLE_RATE / 2 // 0.25 秒
    }

    private val pcm = ByteArrayOutputStream()
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var bytesWritten = 0

    /** 16 位单声道每秒的字节数 */
    private val bytesPerSecond: Int get() = sampleRate * 2

    private val maxPcmBytes: Int get() = maxSeconds * bytesPerSecond

    /** 已经录了多少秒（给界面读，不需要锁） */
    fun elapsedSeconds(): Int =
        (bytesWritten / bytesPerSecond).coerceIn(0, maxSeconds)

    /**
     * 开始录音。
     *
     * @return false = 麦克风打不开（没权限、被占用、设备不支持）。
     *         调用方负责把原因翻译成用户能看懂的话。
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (running) return true

        // 显式检查，而不是只靠调用方：录音权限可以被用户随时撤销，
        // 这里返回 false 让上层给一句能照着做的提示，而不是抛异常崩掉
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        pcm.reset()
        bytesWritten = 0

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false

        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, 4096),
            )
        }.getOrNull() ?: return false

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        val started = runCatching { rec.startRecording() }.isSuccess
        if (!started) {
            rec.release()
            return false
        }

        record = rec
        running = true
        worker = Thread({ readLoop(rec) }, "voice-recorder").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** 结束录音并返回 WAV；没录到有效内容时返回 null */
    fun stop(): ByteArray? {
        val rec: AudioRecord?
        val thread: Thread?
        synchronized(this) {
            running = false
            rec = record
            thread = worker
            record = null
            worker = null
        }
        runCatching { rec?.stop() }
        runCatching { thread?.join(1500) }
        runCatching { rec?.release() }

        val data = pcm.toByteArray()
        if (data.size < MIN_PCM_BYTES) return null
        return WavEncoder.encode(data, sampleRate)
    }

    /** 丢弃这段录音，不发任何东西 */
    fun cancel() {
        val rec: AudioRecord?
        val thread: Thread?
        synchronized(this) {
            running = false
            rec = record
            thread = worker
            record = null
            worker = null
        }
        runCatching { rec?.stop() }
        runCatching { thread?.join(1500) }
        runCatching { rec?.release() }
        pcm.reset()
        bytesWritten = 0
    }

    private fun readLoop(rec: AudioRecord) {
        val buffer = ByteArray(4096)
        try {
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n <= 0) break
                val room = maxPcmBytes - bytesWritten
                if (room <= 0) break
                val take = minOf(n, room)
                pcm.write(buffer, 0, take)
                bytesWritten += take
                if (take < n) break
            }
        } catch (_: Throwable) {
            // 麦克风被抢走 / 拔耳机都可能让 read 抛异常。已经录到的部分仍然有效，
            // 由 stop 决定够不够长 —— 这里只负责安静地退出
        } finally {
            running = false
        }
    }
}
