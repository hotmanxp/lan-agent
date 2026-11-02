// engine/ImageScaleMathTest.kt — 图片缩放纯计算的回归测试。
//
// 这里钉的是两类**静默**错误 —— 不会崩、不会报错,只是图变形了或者手机
// OOM 了,不看图根本发现不了:
//
//  1. EXIF 竖拍照片(像素 4032×3024,显示 3024×4032)的宽高交换:
//     目标框必须按**显示方向**算,否则旋转完再精确拉伸会把竖图拉扁。
//  2. 采样率的绝对像素上限:没有它,调用方不给框时一张 12MP 图会按原
//     尺寸解出来(48MB),叠上旋转副本和铺白底副本直接把主进程撑爆。
//
// 跑法:./gradlew :app:testDebugUnitTest --tests "*ImageScaleMathTest*"
package io.github.hotmanxp.lanagent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageScaleMathTest {

    @Test
    fun `EXIF 旋转 90 时显示宽高互换`() {
        val (w, h) = ImageScaleMath.displaySize(4032, 3024, swapsAxes = true)
        assertEquals(3024, w)
        assertEquals(4032, h)
    }

    @Test
    fun `不换轴时显示宽高就是像素宽高`() {
        val (w, h) = ImageScaleMath.displaySize(4032, 3024, swapsAxes = false)
        assertEquals(4032, w)
        assertEquals(3024, h)
    }

    @Test
    fun `竖拍照片的目标框按显示方向算 —— 比例不能反`() {
        // 竖拍:像素 4032×3024,显示 3024×4032。框是 1568(长边)。
        val (dispW, dispH) = ImageScaleMath.displaySize(4032, 3024, swapsAxes = true)
        val (tw, th) = ImageScaleMath.targetSize(dispW, dispH, 1568, 1568, true)

        assertEquals(1176, tw) // 短边 = 3024 * (1568/4032)
        assertEquals(1568, th) // 长边贴住框
        // 关键:目标框必须是**竖**的(高 > 宽)。按像素宽高算的话会得到
        // 1568×1176(横的),旋转完就被拉扁了。
        assertTrue(th > tw, "竖拍照片的目标框不能是横的")
    }

    @Test
    fun `没有框时目标尺寸就是显示尺寸`() {
        val (w, h) = ImageScaleMath.targetSize(3024, 4032, 0, 0, true)
        assertEquals(3024, w)
        assertEquals(4032, h)
    }

    @Test
    fun `withoutEnlargement 时小图不放大`() {
        val (w, h) = ImageScaleMath.targetSize(100, 200, 1568, 1568, true)
        assertEquals(100, w)
        assertEquals(200, h)
    }

    @Test
    fun `采样后解出来的像素不少于目标尺寸`() {
        val sample = ImageScaleMath.sampleSize(4032, 3024, 1568, 1176, hasBox = true)
        assertTrue(sample >= 1)
        // 采样是 2 的幂次
        assertEquals(0, sample and (sample - 1))
        // 解出来的像素要 ≥ 目标(否则只能靠放大凑数)
        assertTrue(4032 / sample >= 1568)
        assertTrue(3024 / sample >= 1176)
    }

    @Test
    fun `没有框时 12MP 图按像素上限采样而不是全解`() {
        // 4032×3024 = 12.2MP。不设上限的话 sample=1 → 48MB 一份,
        // 叠旋转和铺白底副本峰值 140MB → OOM。
        val sample = ImageScaleMath.sampleSize(4032, 3024, 4032, 3024, hasBox = false)
        val pixels = (4032L / sample) * (3024L / sample)
        assertTrue(
            pixels <= ImageScaleMath.MAX_DECODE_PIXELS,
            "采样后像素数 $pixels 超过上限 ${ImageScaleMath.MAX_DECODE_PIXELS}",
        )
    }

    @Test
    fun `像素上限对超大图也生效`() {
        // 8000×6000 = 48MP,连长边兜底(4096)都拦不住 —— 得靠像素上限。
        val sample = ImageScaleMath.sampleSize(8000, 6000, 8000, 6000, hasBox = false)
        val pixels = (8000L / sample) * (6000L / sample)
        assertTrue(pixels <= ImageScaleMath.MAX_DECODE_PIXELS)
    }

    @Test
    fun `core 的 1568 框路径不受像素上限影响`() {
        // core 给的框最大 1568 → 采样后 3MP,远低于上限,不该被上限再砍一刀。
        val sample = ImageScaleMath.sampleSize(4032, 3024, 1568, 1176, hasBox = true)
        assertEquals(2, sample)
    }
}
