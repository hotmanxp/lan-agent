// ui/VoiceInput.kt — 输入条的语音转文字（平台 SpeechRecognizer 封装）。
//
// 为什么用平台 SpeechRecognizer 而不是引第三方 SDK：
//   - 识别由系统「语音服务」提供（Google / 厂商 ROM 自带），App 侧零依赖、
//     零 key、零体积；第三方 SDK（讯飞/百度）要联网鉴权 + 打包体积 + 隐私合规。
//   - 代价：**必须联网**、且设备上得真的装了识别服务。所以 UI 侧先
//     `isRecognitionAvailable()` 探测，不可用就把按钮置灰，而不是点了没反应。
//
// 三个容易踩的点：
//   1. **Manifest 必须声明 `<queries><intent action="android.speech.RecognitionService">`**
//      (targetSdk 30+ 的包可见性)。漏了的话 `isRecognitionAvailable()` 在真机上
//      稳定返回 false，且不报任何错 —— 表现为「语音按钮永远点不动」。
//   2. **SpeechRecognizer 必须在主线程创建和调用**（内部绑 Service，跨线程会
//      `RuntimeException: SpeechRecognizer should be used only from the main thread`）。
//      Compose 的 `remember` / 事件回调都在主线程，所以这里不做切线程。
//   3. **`onError(ERROR_CLIENT)` 是噪音**：主动 `stopListening()` / `cancel()` /
//      `destroy()` 都会回调它。用户主动取消时报「识别失败」很蠢，所以静默吞掉。
//
// 权限流程用「needsPermission 标志位 + Composable 侧 LaunchedEffect 发申请」，
// 而不是在这里 `ActivityCompat.requestPermissions` —— 后者拿不到结果回调，
// 也跟不上 Activity 重建。
package io.github.hotmanxp.lanagent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 语音输入控制器。由 [rememberVoiceInput] 创建，随宿主 Composable 销毁。
 *
 * 生命周期归 Compose：不要在外部持有它的引用（跨屏会绑住 SpeechRecognizer
 * 不放，那玩意持有 Context）。
 */
@Stable
class VoiceInputController internal constructor(
    private val context: Context,
    private val onMessage: (String) -> Unit,
    private val onText: (String) -> Unit,
) {
    /** 设备上是否有可用的识别服务。false 时 UI 应该置灰按钮。 */
    val available: Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    /** 正在收音。UI 靠它切换图标高亮 / 脉冲动画。 */
    var listening by mutableStateOf(false)
        internal set

    /**
     * 需要申请录音权限。Composable 侧 `LaunchedEffect` 观察它并发起
     * 运行时申请，回调里再调 [onPermissionResult]。
     */
    var needsPermission by mutableStateOf(false)
        internal set

    /** 开始听之前的输入框内容。partial 结果是「整句猜测」，要拼在这个前缀后面。 */
    private var baseText = ""

    /**
     * 丢弃本次识别的结果。发送 / 离开页面时置上 —— `stopListening()` 之后
     * **识别服务仍会异步回调 `onResults`**，不拦的话刚发出去的消息会被
     * 重新填回已清空的输入框，看着像「发出去的话又回来了」。
     */
    private var discardResults = false

    private var recognizer: SpeechRecognizer? = null
    private var released = false

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (discardResults) return
            // partial 是**累积**的整句当前猜测，所以是「覆盖回填」而不是追加，
            // 否则会得到「你好你好你」这种鬼东西。
            firstResult(partialResults)?.let { onText(baseText + it) }
        }

        override fun onResults(results: Bundle?) {
            val dropped = discardResults
            listening = false
            discardResults = false
            if (dropped) return
            firstResult(results)?.let { onText(baseText + it) }
        }

        override fun onError(error: Int) {
            listening = false
            discardResults = false
            // 主动取消引发的噪音，不打扰用户。
            if (error == SpeechRecognizer.ERROR_CLIENT) return
            onMessage(errorText(error))
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，检查麦克风是否被别的应用占用"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限，语音输入用不了"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要联网，检查网络后重试"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "系统识别服务出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "语音识别失败(code=$code)"
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 点语音按钮。[base] 是当前输入框内容 —— 识别结果会拼在它后面。
     * 缺权限时不会直接听，而是置 [needsPermission]，等 Composable 申请完再继续。
     */
    fun toggle(base: String) {
        if (listening) {
            stop()
            return
        }
        if (!available) {
            onMessage("这台设备没有可用的语音识别服务")
            return
        }
        if (!hasPermission()) {
            baseText = base
            needsPermission = true
            return
        }
        begin(base)
    }

    /** 权限申请结果由 Composable 回灌。 */
    internal fun onPermissionResult(granted: Boolean) {
        needsPermission = false
        if (granted) {
            begin(baseText)
        } else {
            onMessage("没有录音权限，语音输入用不了（可在系统设置里开启）")
        }
    }

    private fun begin(base: String) {
        if (released) return
        baseText = base
        discardResults = false
        val r = recognizer ?: runCatching {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        }.getOrNull() ?: run {
            onMessage("创建语音识别器失败")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // 中文优先；识别服务不支持时系统会回落到默认语言，不报错。
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        runCatching { r.startListening(intent) }
            .onFailure {
                listening = false
                onMessage("启动语音识别失败：${it.message ?: it}")
            }
    }

    /** 主动停：会走 `onResults`（把已识别的部分收下来），所以不用手动回填。 */
    fun stop() {
        if (!listening) return
        listening = false
        runCatching { recognizer?.stopListening() }
    }

    /**
     * 主动中止并**丢弃**结果（发送消息 / 页面销毁时用）。
     * 用 `cancel()` 而非 `stopListening()` —— 后者仍会把已识别的片断回调回来。
     */
    fun discard() {
        discardResults = true
        listening = false
        runCatching { recognizer?.cancel() }
    }

    internal fun release() {
        released = true
        listening = false
        discardResults = true
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}

@Composable
fun rememberVoiceInput(
    onMessage: (String) -> Unit,
    onText: (String) -> Unit,
): VoiceInputController {
    val context = LocalContext.current
    // remember 的闭包会跨重组存活，必须用 rememberUpdatedState 拿最新回调，
    // 否则会一直调到第一次组合时的那个（典型症状：snackbar 只弹老文案、
    // 回填调用了已销毁屏的 setter）。
    val message by rememberUpdatedState(onMessage)
    val text by rememberUpdatedState(onText)
    val controller = remember(context) {
        VoiceInputController(context.applicationContext, { message(it) }, { text(it) })
    }
    // 录音权限的运行时申请放在这里（而不是控制器内部）：控制器拿不到
    // Activity 的 result 回调，而 rememberLauncherForActivityResult 必须在
    // Composable 作用域注册，才能跟上 Activity 重建。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> controller.onPermissionResult(granted) }
    LaunchedEffect(controller.needsPermission) {
        if (controller.needsPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}
