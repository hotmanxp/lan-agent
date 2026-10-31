// ScreenCaptureService.kt — MediaProjection 截屏。
//
// ## 为什么必须是个前台服务
//
// Android 14(API 34)起有一条硬规定:**必须先起一个 type=mediaProjection
// 的前台服务,才能调 MediaProjectionManager.getMediaProjection()**。
// 顺序反了直接 SecurityException。所以这个服务不是可选的 —— 它是截屏的
// 必经之路。
//
// ## 授权流程
//
//   用户在 App 里点「授权截屏」
//     → MainActivity 发 createScreenCaptureIntent()
//     → 系统弹「开始录制或投射」
//     → 回调 resultCode + data
//     → startService(带 resultCode/data)
//     → 服务里 startForeground() 之后才 getMediaProjection()
//
// ## 为什么能一直用
//
// 授权一次之后 MediaProjection 对象一直在,VirtualDisplay 一直在,之后
// 每次截屏只是读 ImageReader 的最新一帧 —— 不再弹任何系统对话框。
// 用户可以在系统「正在投射/录制」那条通知里随时停掉。
package io.github.hotmanxp.lanagent.engine

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import io.github.hotmanxp.lanagent.LanAgentApp
import io.github.hotmanxp.lanagent.R
import java.io.ByteArrayOutputStream

/**
 * 持有 MediaProjection + VirtualDisplay + ImageReader,提供「拍一帧」。
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // API 34 起必须先 startForeground 再 getMediaProjection。
        startForegroundCompat()

        if (projection == null) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            if (resultCode == Int.MIN_VALUE || data == null) {
                Log.w(TAG, "没带授权结果,停掉")
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(resultCode, data)
                if (p == null) {
                    Log.w(TAG, "getMediaProjection 返回 null")
                    stopSelf()
                    return START_NOT_STICKY
                }
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "系统停了投射")
                        release()
                        stopSelf()
                    }
                }, null)
                projection = p
                startVirtualDisplay()
                Log.i(TAG, "截屏授权拿到了")
            } catch (e: Throwable) {
                Log.e(TAG, "起 MediaProjection 失败", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
    }

    /** 建 VirtualDisplay + ImageReader。 */
    private fun startVirtualDisplay() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // DEFAULT_DISPLAY 在 android.view.Display 上(值 0),不在 DisplayManager 上。
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        if (display == null) {
            Log.w(TAG, "拿不到默认屏幕,按 1080x1920 兜底")
        } else {
            display.getRealMetrics(metrics)
        }
        val width = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val height = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
        val size = Point(width, height)
        val density = metrics.densityDpi.takeIf { it > 0 } ?: 320

        val reader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection?.createVirtualDisplay(
            "lan-agent-screen",
            size.x,
            size.y,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, LanAgentApp.CHANNEL_ID_SCREEN_CAPTURE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_desc))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground 被拒", it) }
    }

    private fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIF_ID = 0x5343 // 'SC'

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile
        private var projection: MediaProjection? = null

        @Volatile
        private var virtualDisplay: VirtualDisplay? = null

        @Volatile
        private var imageReader: ImageReader? = null

        /** 已经授权了吗。 */
        fun isReady(): Boolean = projection != null

        /**
         * 拍一帧,编码成 PNG。
         *
         * ImageReader 里可能一帧都没有(VirtualDisplay 刚建好还没产出),
         * 所以轮询一小会儿再放弃。
         */
        fun capturePng(): ByteArray? {
            val reader = imageReader ?: return null
            repeat(20) {
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image != null) {
                    try {
                        return encodePng(image)
                    } finally {
                        runCatching { image.close() }
                    }
                }
                Thread.sleep(50)
            }
            Log.w(TAG, "等了 1 秒还没拿到帧")
            return null
        }

        /** Image → Bitmap → PNG 字节。 */
        private fun encodePng(image: Image): ByteArray? {
            val plane = image.planes.getOrNull(0) ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            // rowStride 常常比 width*4 大(硬件对齐),不能直接当成紧凑数组。
            val rowBytes = rowStride
            val out = ByteArray(width * height * 4)
            buffer.rewind()
            val row = ByteArray(rowBytes)
            for (y in 0 until height) {
                if (buffer.remaining() < rowBytes) break
                buffer.get(row, 0, rowBytes)
                var src = 0
                var dst = y * width * 4
                for (x in 0 until width) {
                    out[dst] = row[src]
                    out[dst + 1] = row[src + 1]
                    out[dst + 2] = row[src + 2]
                    out[dst + 3] = row[src + 3]
                    src += pixelStride
                    dst += 4
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(out))
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bitmap.recycle()
            return bos.toByteArray()
        }

        /**
         * 带着用户的授权结果起服务。
         *
         * 必须在 App 前台(有 Activity)调用 —— 系统弹窗需要 Activity 上下文。
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 请求截屏授权的 Intent(由 Activity 发起系统弹窗)。 */
        fun consentIntent(context: Context): Intent? {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            return mpm?.createScreenCaptureIntent()
        }
    }
}

/** 一个小工具:像素尺寸。 */
private data class Point(val x: Int, val y: Int)
