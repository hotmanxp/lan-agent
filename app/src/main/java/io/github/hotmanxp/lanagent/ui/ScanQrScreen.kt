// ui/ScanQrScreen.kt — CameraX + ML Kit Barcode 扫码;扫到 URL 回调 onScanned
package io.github.hotmanxp.lanagent.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.hotmanxp.lanagent.R
import java.util.concurrent.Executors

/**
 * Full-screen QR scanner. Asks for CAMERA on entry; if denied, renders a
 * permission-denied state. On a successful QR read we call [onScanned]
 * exactly once and stop further analysis so the caller doesn't get
 * duplicate navigations while it's processing.
 *
 * zai's share QR encodes a full URL (e.g. http://192.168.101.69:9988/m?sid=abc)
 * — we hand the raw value back and let [AppNavHost] navigate to webview/{url}.
 */
@Composable
fun ScanQrScreen(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            CameraPreviewWithScanner(onScanned = onScanned)
            ScannerOverlay()
        } else {
            PermissionDeniedState(
                onRequestAgain = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
            )
        }

        // Top-left back button sits above the preview / denied-state so the
        // user always has an exit (system back also works via BackHandler).
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 8.dp, top = 4.dp)
                .size(36.dp)
                .background(Color(0x80000000), shape = CircleShape)
                .semantics { contentDescription = context.getString(R.string.scan_back_cd) }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color.White,
            )
        }
    }
}

/**
 * CameraX Preview + ImageAnalysis bound to BarcodeScanner. Camera lifecycle
 * is bound to [LocalLifecycleOwner] so it stops cleanly when the user backs
 * out — no leaked camera sessions.
 *
 * The analyzer short-circuits after the first hit: we set [done] true and
 * unbind the use cases so subsequent frames don't fire the callback again.
 * Success listener fires on the main executor, so [onScanned] can directly
 * trigger navigation without re-dispatching.
 */
@Composable
private fun CameraPreviewWithScanner(onScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                // zai's share QR is always FORMAT_QR_CODE; restricting the
                // format list skips ML Kit's other decoders and trims first-
                // frame latency on lower-end devices.
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    // Done latch: prevents double-fire from a queued frame mid-navigation.
    var done by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // COMPATIBLE uses a SurfaceView (older, lower-latency path).
                // PERFORMANCE uses a TextureView which can show flicker on
                // some OEM ROMs during the first ~100ms before the surface
                // attaches; COMPATIBLE is safer for a debug LAN tool.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val cameraProvider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    // KEEP_ONLY_LATEST drops backlog if analyzer is slow;
                    // we want the freshest frame, not every queued one.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    if (done) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val media = imageProxy.image
                    if (media == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(
                        media, imageProxy.imageInfo.rotationDegrees
                    )
                    scanner.process(input)
                        .addOnSuccessListener { barcodes ->
                            val url = barcodes.firstNotNullOfOrNull { it.rawValue }
                            if (!url.isNullOrBlank()) {
                                done = true
                                cameraProvider.unbindAll()
                                onScanned(url)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                    // bind failures (no camera, lifecycle issue, etc.) — leave
                    // the preview area blank; the user can still back out.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

/**
 * Translucent overlay drawn on top of the camera preview. A square border
 * tells the user where to aim. We can't punch a real hole in the Compose
 * layer (would need RenderNode clip), so the visual cue is the border +
 * dark backdrop around the camera (the Black background already dims the
 * edges relative to the framed region).
 */
@Composable
private fun ScannerOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .border(
                        width = 2.dp,
                        color = Color.White.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp),
                    )
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.scan_hint),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PermissionDeniedState(
    onRequestAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_permission_denied),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequestAgain) {
                Text(stringResource(R.string.scan_permission_retry))
            }
            Button(onClick = onBack) {
                Text(stringResource(R.string.scan_back))
            }
        }
    }
}
