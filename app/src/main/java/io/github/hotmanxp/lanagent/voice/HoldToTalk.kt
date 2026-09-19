// voice/HoldToTalk.kt — 「按住说话」的 Compose 组件 + 状态机。
//
// 交互契约（和微信一致）：
//   按下 → 申请权限（首次）→ 开始录音 → 实时回填非稳态文本 → 松手 → 等 final → 交给调用方
//   上滑超过阈值 → 标记取消 → 抬手丢弃整段，不进输入框
//
// 状态机只有三态，不允许出现第四态：
//   Idle ──press──▶ Recording ──release──▶ Recognizing ──final/timeout──▶ Idle
//                     └──cancel / slide-up──▶ Idle
//
// 四个容易写错的地方：
//   1. **手势的 pointerInput key 不能用 phase**。用 `pointerInput(state.isIdle)` 的话，
//      press() 一改 phase 就会重启 gesture 协程，`awaitEachGesture` 被取消，
//      后面再也收不到抬手事件 —— 表现是「按住之后永远不停，麦克风一直开」。
//      key 必须是与 phase 无关的量（这里用 cancelPx）。
//   2. **不能只靠 phase 判断能不能再按**。松手到 Recognizing 之间麦克风还没释放，
//      这期间再按会让两段音频混在一起。`press()` 里对 phase 做了硬校验，非法调用直接忽略。
//   3. **手势不能用 detectTapGestures**。它只给「按下/抬起」，拿不到拖动过程，
//      上滑取消就实现不了。这里手写 awaitEachGesture 循环读 pointer 事件。
//   4. **首次按下的权限流程要单独处理**。弹权限框时用户必然抬手（去点「允许」），
//      所以授权回来后不能直接 begin() —— 那会录出一段「没人按着」的音频。
//      用 `held` 记住手指是否还按着：还按着才续上，否则提示再按一次。
//   5. **awaitEachGesture 的块绝不许不 await 就返回**。外层是 while(true)，
//      块若提前 return（比如 `if (!state.isIdle) return`），它就以最快速度
//      空转重启，主线程 100% CPU 直接 ANR —— 真机表现是「松手后 App 卡死
//      被系统杀」，且必现。非 Idle 时必须先把这轮手势的事件「吸干」再退出。
//
// 注：`awaitEachGesture` 需要 androidx.compose.foundation 1.6.0+（2024-01 起稳定）。
package io.github.hotmanxp.lanagent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.sqrt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

enum class HoldPhase { Idle, Recording, Recognizing }

/** 低于 300ms 不给识别：省一次没意义的上行，也避免「没听清」的误报。 */
private const val MIN_AUDIO_BYTES = 300 * PcmAudioRecorder.SAMPLE_RATE * 2 / 1000

@Stable
class HoldToTalkState internal constructor(
    private val context: Context,
    private val asrUrlProvider: AsrUrlProvider,
    private val engine: String,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onHint: (String) -> Unit,
) {
    /** 三态。Recognizing 期间麦克风已释放、只等 final。 */
    var phase by mutableStateOf(HoldPhase.Idle)
        internal set

    /** 实时文本 = 输入框原有内容 + 已定稿 + 当前句猜测，直接绑输入框。 */
    var liveText by mutableStateOf("")
        internal set

    /** 0f–1f 音量，给呼吸/波形动画用。 */
    var level by mutableStateOf(0f)
        internal set

    /** 已滑入取消区，UI 据此变红。 */
    var willCancel by mutableStateOf(false)
        internal set

    var needsPermission by mutableStateOf(false)
        internal set

    val isIdle: Boolean get() = phase == HoldPhase.Idle

    private val recorder = PcmAudioRecorder()
    private var asr: TencentRealtimeAsr? = null
    private var receivedBytes = 0

    /** 按下时输入框里已有的内容，识别结果拼在它后面而不是覆盖它。 */
    private var baseText = ""

    /** 手指是否还按着。见文件头坑位 4。 */
    private var held = false

    // ── 对外动作 ─────────────────────────────────────────────────────────

    /** 手指按下。[base] 是当前输入框内容。 */
    internal fun press(base: String) {
        if (phase != HoldPhase.Idle) return // 见文件头坑位 2
        held = true
        baseText = base.trimEnd()
        if (!hasAudioPermission()) {
            needsPermission = true
            return
        }
        begin()
    }

    /**
     * 手指抬起。[cancelled] = 上滑取消。
     * 无论哪条路径都要先把 held 落下，否则权限回调会误判「还按着」。
     */
    internal fun endGesture(cancelled: Boolean) {
        held = false
        if (cancelled) cancel() else release()
    }

    /** 抬起且未取消 → 结束录音，等 ASR 收尾。 */
    private fun release() {
        if (phase != HoldPhase.Recording) return
        willCancel = false

        val pcm = recorder.stop()
        val client = asr
        if (client == null || receivedBytes < MIN_AUDIO_BYTES || pcm.isEmpty()) {
            client?.cancel()
            asr = null
            reset()
            onError("说话时间太短")
            return
        }

        phase = HoldPhase.Recognizing
        level = 0f
        // finish() 保证回调（正常 final、超时兜底或出错兜底），统一在这里复位。
        client.finish { text ->
            asr = null
            reset()
            if (text.isNotBlank()) onResult(text) else onError("没听清，再说一次")
        }
    }

    /** 上滑取消 / 页面销毁。整段丢弃，不进输入框。 */
    fun cancel() {
        recorder.cancel()
        asr?.cancel()
        asr = null
        reset()
    }

    internal fun onPermissionResult(granted: Boolean) {
        needsPermission = false
        if (!granted) {
            onError("没有录音权限，语音输入用不了（可在系统设置里开启）")
            return
        }
        // 见文件头坑位 4。
        if (held) begin() else onHint("已获得录音权限，再按住说话")
    }

    internal fun dispose() = cancel()

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun begin() {
        receivedBytes = 0
        willCancel = false
        liveText = baseText

        val client = TencentRealtimeAsr(
            urlProvider = asrUrlProvider,
            engine = engine,
            listener = object : TencentRealtimeAsr.Listener {
                override fun onConnected(voiceId: String) = Unit

                override fun onText(committed: String, partial: String) {
                    liveText = join(committed + partial)
                }

                override fun onFinal(text: String) {
                    // 服务端会主动收尾：max_speak_time（默认 60s）强制断句、或 VAD 判静音。
                    // 这时用户可能还按着 —— 必须主动收口，否则结果到了但没人接。
                    // 正常松手路径下 phase 已经变成 Recognizing，由 finish 的 waiter 处理，这里跳过。
                    if (phase != HoldPhase.Recording) return
                    recorder.stop()
                    reset()
                    if (text.isNotBlank()) onResult(join(text)) else onError("没听清，再说一次")
                }

                override fun onError(code: Int, message: String) {
                    // 服务端错误码原样透出，便于对号入座：
                    // 4002 鉴权失败 / 4003 服务未开通 / 4004 资源包耗尽 /
                    // 4007 解码失败（采样率或 voice_format 不对）/ 6001 走了境外代理
                    onError(if (code == 0) message else "识别失败($code)：$message")
                }

                override fun onWarning(message: String) {
                    // 非致命（如「网络不稳丢了 N 片」）走 hint 通道，让 UI toast 提示。
                    // 复用 onHint 而不是 onError，因为识别其实成功了，不该改用户已看到的文本。
                    onHint(message)
                }
            },
        )
        asr = client
        client.start()

        try {
            recorder.start { chunk ->
                receivedBytes += chunk.size
                asr?.feed(chunk)
                updateLevel(chunk)
            }
        } catch (e: IllegalStateException) {
            // 设备不支持 16k/mono/PCM16，或麦克风被别的 App 占用
            client.cancel()
            asr = null
            reset()
            onError(e.message ?: "录音启动失败")
            return
        }

        phase = HoldPhase.Recording
    }

    /** 把识别结果拼到按下前输入框的内容后面。 */
    private fun join(recognized: String): String = when {
        baseText.isBlank() -> recognized
        recognized.isBlank() -> baseText
        else -> "$baseText $recognized"
    }

    /** 16 位小样本的 RMS 足够驱动动画，不值得为它开 FFT。 */
    private fun updateLevel(chunk: ByteArray) {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
            val v = sample.toDouble() / Short.MAX_VALUE
            sum += v * v
            count++
            i += 2
        }
        if (count == 0) return
        val rms = sqrt(sum / count).toFloat().coerceIn(0f, 1f)
        // 平滑一下，否则 200ms 一跳看起来像闪烁
        level = (level * 0.6f + rms * 0.4f).coerceIn(0f, 1f)
    }

    private fun reset() {
        phase = HoldPhase.Idle
        willCancel = false
        liveText = ""
        level = 0f
        receivedBytes = 0
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
fun rememberHoldToTalk(
    asrUrlProvider: AsrUrlProvider,
    engine: String = "16k_zh",
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onHint: (String) -> Unit = {},
): HoldToTalkState {
    val context = LocalContext.current
    // 闭包跨重组存活，必须取回调最新值 —— 否则会一直调到首次组合那个
    // （症状：toast 只弹老文案、回填调用到已销毁屏的 setter）。
    val latestResult by rememberUpdatedState(onResult)
    val latestError by rememberUpdatedState(onError)
    val latestHint by rememberUpdatedState(onHint)

    // asrUrlProvider 必须由调用方 remember 住，否则这里每次重组都会重建状态机。
    val state = remember(context, asrUrlProvider) {
        HoldToTalkState(
            context = context.applicationContext,
            asrUrlProvider = asrUrlProvider,
            engine = engine,
            onResult = { latestResult(it) },
            onError = { latestError(it) },
            onHint = { latestHint(it) },
        )
    }

    // 权限申请必须在 Composable 作用域注册，才能跟上 Activity 重建；
    // 控制器内部拿不到 Activity 的 result 回调。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> state.onPermissionResult(granted) }

    LaunchedEffect(state.needsPermission) {
        if (state.needsPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(state) {
        onDispose { state.dispose() }
    }
    return state
}

/**
 * 按住说话按钮。视觉三态：
 * 空闲（无底色 + 无描边）→ 收音（主题色描边 + 呼吸 + 音量驱动缩放）→ 取消区（红色）。
 *
 * @param baseText 当前输入框内容 —— 识别结果拼在它后面，由按钮在按下那一刻快照下来。
 */
@Composable
fun HoldToTalkButton(
    state: HoldToTalkState,
    baseText: String,
    modifier: Modifier = Modifier,
    cancelThresholdDp: Int = 64,
) {
    val density = LocalDensity.current
    val cancelPx = with(density) { cancelThresholdDp.dp.toPx() }
    // 按钮上的文字/内容在组合期取一次即可，手势协程里用这个固定值。
    val latestBase by rememberUpdatedState(baseText)

    val pulse = rememberInfiniteTransition(label = "hold-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "hold-pulse-alpha",
    )

    val recording = state.phase == HoldPhase.Recording
    val accent = when {
        state.willCancel -> MaterialTheme.colorScheme.error
        recording -> MaterialTheme.colorScheme.primary
        else -> LocalContentColor.current
    }
    val alpha = if (recording) 0.55f + pulseAlpha * 0.45f else 1f
    val scale = if (recording) 1f + state.level * 0.22f else 1f

    Box(
        modifier = modifier
            .size(44.dp)
            .scale(scale)
            // key 用 cancelPx（与 phase 无关），见文件头坑位 1。
            .pointerInput(cancelPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!state.isIdle) {
                        // 见文件头坑位 5：不能裸 return，必须把这轮手势吸干，
                        // 否则 awaitEachGesture 空转重启把主线程打满（ANR）。
                        while (awaitPointerEvent().changes.any { it.pressed }) {
                            // Recognizing 期间的事件全部丢弃，等所有指针抬起
                        }
                        return@awaitEachGesture
                    }
                    state.press(latestBase)

                    var slidingOff = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            state.endGesture(slidingOff)
                            break
                        }
                        // 上滑距离（y 轴向上为负）
                        val now = (change.position.y - down.position.y) < -cancelPx
                        if (now != slidingOff) {
                            slidingOff = now
                            state.willCancel = now
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    color = if (recording) accent.copy(alpha = 0.14f * alpha) else Color.Transparent,
                    shape = CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = if (recording || state.willCancel) accent.copy(alpha = alpha)
                    else Color.Transparent,
                    shape = CircleShape,
                ),
        )
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = "按住说话",
            tint = accent.copy(alpha = alpha),
            modifier = Modifier.size(21.dp),
        )
    }
}

/**
 * 「按住 说话」大胶囊 —— WorkBuddy 输入条的同款形态。
 *
 * 交互：点击工具条的语音图标后，输入框区域切换成这个全宽胶囊；按住录音、
 * 松手识别、上滑取消，全部复用 [HoldToTalkState]，与 [HoldToTalkButton] 唯一的
 * 差别是皮。左侧小键盘按钮退出语音模式（[onCollapse]），识别完成（final 回填后）
 * 由调用方观察 phase 自动收回 —— 见 AgentInputBar 的 LaunchedEffect。
 *
 * 手势块与 [HoldToTalkButton] 相同，两个坑位同样适用（pointerInput key 与
 * phase 无关；非 Idle 分支必须吸干事件，见文件头坑位 1/5）。
 */
@Composable
fun HoldToTalkCapsule(
    state: HoldToTalkState,
    baseText: String,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    cancelThresholdDp: Int = 64,
) {
    val density = LocalDensity.current
    val cancelPx = with(density) { cancelThresholdDp.dp.toPx() }
    val latestBase by rememberUpdatedState(baseText)

    val pulse = rememberInfiniteTransition(label = "capsule-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "capsule-pulse-alpha",
    )

    val recording = state.phase == HoldPhase.Recording
    val recognizing = state.phase == HoldPhase.Recognizing
    val accent = when {
        state.willCancel -> MaterialTheme.colorScheme.error
        recording || recognizing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val alpha = if (recording) 0.55f + pulseAlpha * 0.45f else 1f
    val scale = if (recording) 1f + state.level * 0.06f else 1f

    val label = when {
        state.willCancel -> "松开 取消"
        recording -> "松手 结束"
        recognizing -> "识别中…"
        else -> "按住 说话"
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 退出语音模式的小键盘按钮（WorkBuddy 同位置）。
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                )
                .clickable(onClick = onCollapse),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = "键盘输入",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(21.dp),
            )
        }

        // 大胶囊本体。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .scale(scale)
                .pointerInput(cancelPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!state.isIdle) {
                            // 坑位 5：吸干再退出，绝不裸 return（awaitEachGesture 空转 → ANR）。
                            while (awaitPointerEvent().changes.any { it.pressed }) {
                                // Recognizing 期间的事件全部丢弃
                            }
                            return@awaitEachGesture
                        }
                        state.press(latestBase)

                        var slidingOff = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                state.endGesture(slidingOff)
                                break
                            }
                            val now = (change.position.y - down.position.y) < -cancelPx
                            if (now != slidingOff) {
                                slidingOff = now
                                state.willCancel = now
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent.copy(alpha = alpha),
                    maxLines = 1,
                )
            }
        }
    }
}
