// engine/ImageScaleMath.kt — 图片缩放的**纯计算**部分。
//
// ## 为什么单独抽出来
//
// 「EXIF 朝向要不要交换宽高」「目标框按哪组宽高算」「采样率取多少」
// 这三件事耦合在一起,算错的后果都很隐蔽:
//
// - 宽高按**像素**算、缩放按**显示**做 → 竖拍照片(像素 4032×3024,
//   显示 3024×4032)被 createScaledBitmap 精确拉伸成横的,明显变形。
// - 采样率只按目标框算、不设绝对上限 → 调用方没给框时一张 12MP 图
//   按原尺寸解出来就是 48MB,再叠旋转副本和铺白底副本,主进程 OOM。
//
// 而这些计算夹在 BitmapFactory 那条路上时,纯 JVM 单测碰不到
// (org.json / BitmapFactory 在单测里是 stub,一碰就
// ExceptionInInitializerError —— 见 FileUploadTest 的注释)。
//
// 把纯算术抽出来之后,这两类错误都能用普通 JVM 单测钉住。

package io.github.hotmanxp.lanagent.engine

/**
 * 图片缩放的纯计算。
 *
 * 只做算术,不碰 Bitmap / 文件 / 上下文 —— 所以能在纯 JVM 单测里跑。
 */
object ImageScaleMath {

    /**
     * 单次解码允许的最大像素数(约 6MP)。
     *
     * ARGB_8888 一份就是 4 字节/像素:6MP ≈ 24MB。解码之后还要叠一份
     * 旋转副本和一份铺白底副本,峰值能到 3 倍 —— 不设上限的话,一张 12MP
     * 的手机照片(像素 4032×3024)按原尺寸解出来就是 48MB,三份叠一起
     * 140MB,主进程直接 OOM(没申请 largeHeap)。
     *
     * core 给的缩放框最大 1568(IMAGE_MAX_WIDTH),那条路径采样后只剩
     * 3MP,不受这个上限影响 —— 上限只兜「调用方没给框」的情况。
     */
    const val MAX_DECODE_PIXELS = 6_000_000L

    /** 没给缩放框时的兜底长边上限。 */
    private const val FALLBACK_LONG_EDGE = 4096

    /**
     * 「显示方向」的宽高。
     *
     * @param swapsAxes EXIF 朝向是否交换了宽高(旋转 90/270,或等价的
     *   TRANSPOSE / TRANSVERSE)。
     */
    fun displaySize(
        origWidth: Int,
        origHeight: Int,
        swapsAxes: Boolean,
    ): Pair<Int, Int> = if (swapsAxes) origHeight to origWidth else origWidth to origHeight

    /**
     * 目标框:fit=inside(等比缩到框内)。
     *
     * @param maxWidth 宽上限,0 = 不限。
     * @param maxHeight 高上限,0 = 不限。
     * @param withoutEnlargement 小图不放大。
     * @return (目标宽, 目标高)。两个上限都是 0 时返回原尺寸。
     */
    fun targetSize(
        dispWidth: Int,
        dispHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        withoutEnlargement: Boolean,
    ): Pair<Int, Int> {
        if (maxWidth <= 0 && maxHeight <= 0) return dispWidth to dispHeight
        val boxW = if (maxWidth > 0) maxWidth else Int.MAX_VALUE
        val boxH = if (maxHeight > 0) maxHeight else Int.MAX_VALUE
        var scale = minOf(boxW.toDouble() / dispWidth, boxH.toDouble() / dispHeight)
        if (withoutEnlargement) scale = minOf(scale, 1.0)
        val w = maxOf(1, Math.round(dispWidth * scale).toInt())
        val h = maxOf(1, Math.round(dispHeight * scale).toInt())
        return w to h
    }

    /**
     * 解码采样率(必须是 2 的幂次)。
     *
     * 三层约束,从松到紧:
     *  1. 采样后解出来的像素 ≥ 目标尺寸 —— 缩放只能往下走,不能靠放大凑数;
     *  2. 调用方没给框时,长边兜底到 ≤ 4096;
     *  3. 绝对像素上限 —— 前两条都管不住时的最后一道闸。
     *
     * @param hasBox 调用方是否给了缩放框。
     */
    fun sampleSize(
        dispWidth: Int,
        dispHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        hasBox: Boolean,
    ): Int {
        var sample = 1
        while (dispWidth / (sample * 2) >= targetWidth &&
            dispHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        if (!hasBox) {
            val longEdge = maxOf(dispWidth, dispHeight)
            var guard = 1
            while (longEdge / (guard * 2) >= FALLBACK_LONG_EDGE) guard *= 2
            if (guard > sample) sample = guard
        }
        while ((dispWidth.toLong() / sample) * (dispHeight.toLong() / sample) > MAX_DECODE_PIXELS) {
            sample *= 2
        }
        return sample
    }
}
