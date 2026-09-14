// voice/PcmAudioRecorder.kt — 按住说话的音频采集层。
//
// 设计取舍（和 WorkBuddy 的 browser-voice-capture-adapter.ts 同构，换到 Android）：
//   - 固定 16k / mono / PCM16 —— 腾讯云实时 ASR 只吃这个组合，采样率不对会 4007。
//   - 切片按 200ms（16k → 6400 字节）。官方要求「每 200ms 发 200ms，1:1 实时率」，
//     发快了报 4000，间隔太久报 4008。
//   - 采集线程**只做 read()**，切片回调扔给调用方自己的线程/协程。
//     如果把 ws.send 直接写在 read 循环里，网络一抖就会漏采，表现为「中间吞字」。
//
// 三个容易踩的坑：
//   1. AudioSource 用 VOICE_RECOGNITION 而不是 MIC。这条源默认不做 AGC/降噪，
//      喂给 ASR 的字准率明显更好；用 MIC 在嘈杂环境下容易把字当噪声吃掉。
//   2. AudioRecord.getMinBufferSize() 必须校验 > 0，不支持的组合会返回
//      ERROR_BAD_VALUE(-2)，此时构造 AudioRecord 不会抛异常但 state 是 UNINITIALIZED，
//      startRecording() 静默失败 —— 表现是「按住没反应」。
//   3. stop() 之后要 join 采集线程再释放，否则 read() 可能还在往已释放的
//      AudioRecord 上写，抛 IllegalStateException 或直接崩在 native 层。
package io.github.hotmanxp.lanagent.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * 16k / mono / PCM16 采集器。一次 start/stop 对应一次「按住」。
 *
 * 线程模型：start() 起一条独立线程跑 read 循环；onChunk 在**那条线程**上回调，
 * 调用方负责切回主线程（或用线程安全的队列转发）。
 */
class PcmAudioRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
    private val chunkMs: Int = CHUNK_MS,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_MS = 200

        /** 200ms × 16k × 2byte = 6400 */
        private const val BYTES_PER_CHUNK = SAMPLE_RATE / 1000 * CHUNK_MS * 2

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 整段音频镜像，stop() 时用来编 WAV（走一次性 HTTP 兜底时才需要）。 */
    private val mirror = ByteArrayOutputStream()

    val isRecording: Boolean get() = running

    /**
     * 开始采集。[onChunk] 收到定长 200ms 的 PCM16 小端裸数据。
     *
     * @throws IllegalStateException 设备不支持 16k/mono/PCM16 或 AudioRecord 初始化失败。
     */
    @SuppressLint("MissingPermission") // 权限由调用方（Composable）在申请后保证
    fun start(onChunk: (ByteArray) -> Unit) {
        if (running) return

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        check(minBuffer > 0) {
            "设备不支持 ${sampleRate}Hz/mono/PCM16（getMinBufferSize=$minBuffer）"
        }
        // 至少 4 个切片，避免读取线程被调度抖动饿死导致 overrun。
        val bufferSize = maxOf(minBuffer, BYTES_PER_CHUNK * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            CHANNEL,
            ENCODING,
            bufferSize,
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }

        record = rec
        mirror.reset()
        running = true
        rec.startRecording()

        worker = Thread({
            val buffer = ByteArray(BYTES_PER_CHUNK)
            while (running) {
                var filled = 0
                // read() 不保证一次填满，攒够一个切片再发。
                while (filled < buffer.size && running) {
                    val n = rec.read(buffer, filled, buffer.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled <= 0) continue
                val chunk = if (filled == buffer.size) buffer.copyOf() else buffer.copyOf(filled)
                mirror.write(chunk)
                onChunk(chunk)
            }
        }, "pcm-capture").also { it.start() }
    }

    /**
     * 停止并返回整段 PCM16（不含 WAV 头）。
     * 返回空数组说明这段没采到东西 —— 通常是权限被系统拒了，或麦克风被别的 App 占用。
     */
    fun stop(): ByteArray {
        if (!running) return ByteArray(0)
        teardown()
        return mirror.toByteArray()
    }

    /** 停止并丢弃。上滑取消、页面销毁走这个。 */
    fun cancel() {
        if (!running) return
        teardown()
        mirror.reset()
    }

    /** 幂等的资源释放。 */
    private fun teardown() {
        running = false
        worker?.let { runCatching { it.join(500) } }
        worker = null
        record?.let { rec ->
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        record = null
    }
}
