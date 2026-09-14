// data/ImageAttachment.kt — 会话详情页的图片附件：选图 → 重编码 → base64。
//
// **为什么一定要重编码成 JPEG，不能原样 base64 上传**：
//   1. 服务端 `ImageBlock.source.media_type` 是**枚举**：只认
//      `image/jpeg|png|gif|webp`（agent.ts:216-221）。相册里的 HEIC / BMP /
//      AVIF 会被 zod 拒成 400。
//   2. 紧跟一道 magic bytes 预检（agent.ts:1906-1914 `assertImageMagicMatches`）——
//      声明的 media_type 和实际字节头不一致就 400 `image_format_mismatch`，
//      而 `content://` 的 MIME 在部分 ROM 上会撒谎，声明和字节对不上很常见。
//   3. `express.json({ limit: '20mb' })`（server/index.ts:171）是整包上限，
//      现在手机随手一张就是 4–12MB，base64 再 ×1.33 直接顶格。
//
// 一条路全解决：**统一解码 → 白底铺平 → 长边压到 1600 → JPEG 85 → base64**。
// media_type 恒为 `image/jpeg`，字节头必然匹配，体积落在 200–500KB。
//
// 白底铺平那一步不能省：PNG 截图带 alpha，`compress(JPEG)` 会把透明区压成
// **黑色**，深色主题下看着像图坏了。
package io.github.hotmanxp.lanagent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 已编码好的图片附件。持有 base64 意味着选中就占了内存 —— 所以
 * [ImageAttachments.MAX_COUNT] 卡在 4 张（重编码后单张 ~300KB，4 张 base64
 * 也就 ~1.6MB，可忽略）。
 */
data class AttachedImage(
    /** 稳定 key（uri 字符串），用于去重 + Compose 列表 key。 */
    val id: String,
    val uri: Uri,
    val mediaType: String,
    val base64: String,
    val width: Int,
    val height: Int,
    val byteSize: Int,
) {
    /** 给用户看的大小，如 "312 KB"。 */
    val sizeLabel: String
        get() = if (byteSize >= 1024 * 1024) {
            "%.1f MB".format(byteSize / 1024f / 1024f)
        } else {
            "${byteSize / 1024} KB"
        }
}

/** 转成上线的 wire 形态（剥掉 Uri —— 请求体不需要它）。 */
fun AttachedImage.toPromptImage(): PromptImage = PromptImage(mediaType = mediaType, base64 = base64)

object ImageAttachments {

    /** 单张上限张数（服务端 contentBlocks 上限是 10，含文本块，这里留足余量）。 */
    const val MAX_COUNT = 4

    /** 重编码后的长边上限。1600 够模型看清截图里的代码/白板字。 */
    private const val MAX_EDGE = 1600

    private const val QUALITY = 85

    /** 缩略图（输入条上方的附件 chip）长边。 */
    private const val THUMB_EDGE = 220

    /**
     * 读 + 重编码。失败抛 [IllegalStateException]（调用方 toast）。
     * 必须在 IO 线程跑 —— 大图解码是几十到几百毫秒的活。
     */
    suspend fun load(context: Context, uri: Uri): AttachedImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // 第一遍只读尺寸，拿到就关流（inJustDecodeBounds 不解像素，很便宜）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("读不到图片尺寸，可能不是图片文件")
        }

        // 采样到 [MAX_EDGE, 2*MAX_EDGE)，避免把 8000×6000 原图整张读进内存
        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("图片解码失败")

        val scale = MAX_EDGE.toFloat() / maxOf(src.width, src.height)
        val outW = if (scale < 1f) (src.width * scale).toInt().coerceAtLeast(1) else src.width
        val outH = if (scale < 1f) (src.height * scale).toInt().coerceAtLeast(1) else src.height

        val flat = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)  // alpha → 白，别让 JPEG 压出黑底
            Paint(Paint.FILTER_BITMAP_FLAG).also { paint ->
                drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, outW, outH), paint)
            }
        }
        if (flat !== src) src.recycle()

        val bytes = ByteArrayOutputStream().use { out ->
            flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        val w = flat.width
        val h = flat.height
        flat.recycle()

        AttachedImage(
            id = uri.toString(),
            uri = uri,
            mediaType = "image/jpeg",
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = w,
            height = h,
            byteSize = bytes.size,
        )
    }

    /** 附件 chip 的缩略图。失败返回 null，UI 退化成「占位图标 + 文件名」。 */
    suspend fun thumbnail(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / (sample * 2) >= THUMB_EDGE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.getOrNull()
    }

    /**
     * 全屏查看用的原图(0.10.2 起)。
     *
     * 跟 [thumbnail] 区别在长边上限不同 —— [thumbnail] 卡 220px 够输入条上方
     * 的 64dp chip 用,全屏查看至少要 1600px 才不糊。失败返回 null(URI 失效 /
     * 文件被删),UI 退化成空 Dialog。
     */
    suspend fun fullBitmap(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.getOrNull()
    }
}
