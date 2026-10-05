// ui/CameraCapture.kt — 任务页「拍照」的全屏取景框(0.26.5)
//
// 对齐 Trae 的「添加到对话」:相册之外再给一个直接的取景入口,拍完即成附件。
// 依赖是 AA(0.24.2)移植时带进来的 androidx.camera.{core,camera2,lifecycle,view},
// 清单里 CAMERA 权限也已声明(为 ScanQrScreen),这里不再动 manifest。
//
// **不复用 `aa/.../SessionCameraCapture`**:那个 Composable 的返回类型是 AA 的
// `PendingAttachment`、配色走 AA 的 `LocalAAColors`、定位还是硬编码的
// 174/458dp。原生任务页引用它等于把两套设计系统绑在一起。
//
// 输出是 cacheDir 里的 `file://` Uri —— `ImageAttachments.load` 走
// `ContentResolver.openInputStream`,该方法对 SCHEME_FILE 同样有效(走
// FileInputStream 分支),所以不需要 FileProvider。
package io.github.hotmanxp.lanagent.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.icons.lucide.FlipHorizontal2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import io.github.hotmanxp.lanagent.R
import java.io.File

/**
 * 全屏拍照弹层。调用方负责先拿到 CAMERA 权限再挂上来(见 AgentSessionScreen)。
 *
 * [onCaptured] 给的是 cacheDir 里的 `file://` Uri,调用方自己决定怎么消费;
 * [onError] 只报人话,UI 层负责 toast —— 这里不发第二个 Dialog。
 */
@Composable
internal fun CameraCaptureDialog(
    onDismiss: () -> Unit,
    onCaptured: (Uri) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var capturing by remember { mutableStateOf(false) }
    // bind 失败(设备没摄像头 / 被别的应用占着)只报一次,别在每次重组里重复弹 toast。
    var reported by remember { mutableStateOf(false) }

    DisposableEffect(cameraController) {
        onDispose { cameraController.unbind() }
    }
    // 翻转镜头走「重绑」而不是在 update 里调 bindToLifecycle —— update 每次
    // 重组都跑,那里调等于不停重绑。
    LaunchedEffect(lensFacing, lifecycleOwner) {
        runCatching {
            cameraController.cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            cameraController.bindToLifecycle(lifecycleOwner)
        }.onFailure {
            if (!reported) {
                reported = true
                onError(context.getString(R.string.agent_camera_unavailable))
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        controller = cameraController
                    }
                },
            )

            // 顶栏:左关闭 / 右翻转镜头。都垫半透明黑底圆钮,压在取景画面上要能看清。
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CameraCircleButton(
                    icon = Lucide.X,
                    contentDescription = stringResource(R.string.agent_camera_close_cd),
                    onClick = onDismiss,
                )
                CameraCircleButton(
                    icon = Lucide.FlipHorizontal2,
                    contentDescription = stringResource(R.string.agent_camera_flip_cd),
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                )
            }

            // 底部:快门。环形留白是 iOS/安卓相机通行的视觉语言,白色实心圆
            // 按下去就是「正在拍」——capturing 期间禁用,防连按拍出一堆。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 48.dp)
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(3.dp, Color.White, CircleShape)
                    .noRippleClickable(enabled = !capturing) {
                        capturing = true
                        val photo = cameraPhotoFile(context.cacheDir)
                        val output = ImageCapture.OutputFileOptions.Builder(photo).build()
                        cameraController.takePicture(
                            output,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                    capturing = false
                                    onCaptured(Uri.fromFile(photo))
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    onError(
                                        exception.message
                                            ?: context.getString(R.string.agent_camera_failed),
                                    )
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
}

/** 压在取景画面上的圆形透明按钮(自绘,不引 IconButton —— 48dp 最小尺寸会撑破圆形)。 */
@Composable
private fun CameraCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 落盘文件名走时间戳而不是 ContentResolver 的 display name —— 取景框本来就没有
 * 「文件名」这个概念,时间戳顺带天然去重。
 */
private fun cameraPhotoFile(cacheDir: File): File {
    val dir = File(cacheDir, "camera-attachments").apply { mkdirs() }
    return File(dir, "IMG_${System.currentTimeMillis()}.jpg")
}
