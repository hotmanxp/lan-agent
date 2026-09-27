// data/QrCodeBitmap.kt — 把字符串编码成 QR Bitmap 的小工具(0.21.0)。
//
// 用途:Agents-Anywhere mobile-login QR 对话框 —— 把
// `{baseUrl}/auth/mobile-login/confirm?loginToken=…&userId=…` 渲染成 QR,已
// 登录设备的浏览器扫码后服务器把 `status` 推到 `pending_web_confirm` →
// `approved`,客户端 2s 轮询拿到 approved 就 exchange 拿 token。
//
// **只要 ZXing core**:不引入 journeyapps 的 zxing-android-embedded,那套是
// 扫码(相机 + ActivityResult),我们**只生成**。`com.google.zxing:core`
// 是纯 JVM 库,Android 上跑无额外兼容性包袱。
//
// **像素路径**:`setPixels(int[])` 一次塞整行,比 `setPixel(x,y)` 循环快
// 200× 左右(1080×1080 的 QR ~3ms 而不是 ~600ms)。背景白前景黑 —— 默认
// `Color.WHITE` / `Color.BLACK`,跟 Material 的 card 配色一致,不挑主题。
package io.github.hotmanxp.lanagent.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 [content] 编码成 [sizePx] × [sizePx] 的 ARGB Bitmap。
 *
 * @param content 要编码的字符串(通常就是 web confirm URL)
 * @param sizePx 输出 Bitmap 的边长(像素)。常见 720 / 1080 —— 大屏手机显示
 *               不会糊,小屏会自动被 ImageView 缩放
 * @throws IllegalStateException ZXing 编码失败(内容超长 / 非 ASCII 极端情况)
 */
suspend fun generateQrBitmap(content: String, sizePx: Int): Bitmap = withContext(Dispatchers.Default) {
    require(sizePx > 0) { "sizePx must be > 0, got $sizePx" }
    require(content.isNotBlank()) { "content must not be blank" }

    // L = 7% 冗余,够扫不糊;MARGIN=1 让 QR 占满画布(默认 4 周边留太多白边)。
    // 屏幕小屏显示时这种「贴边」形态更稳。
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )

    val matrix = try {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    } catch (e: WriterException) {
        throw IllegalStateException("QR encode failed for content of length ${content.length}", e)
    } catch (e: IllegalArgumentException) {
        // ZXing 在内容超长时抛 IllegalArgumentException("Data too big") —— 包装一下。
        throw IllegalStateException("QR content too long (${content.length} chars)", e)
    }

    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val rowStart = y * width
        for (x in 0 until width) {
            pixels[rowStart + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}