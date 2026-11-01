// ScreenCaptureConsentActivity.kt — 截屏授权的「过桥」Activity。
//
// ## 为什么要有这个 Activity
//
// MediaProjection 的授权弹窗必须由 **Activity** 发起
// (`MediaProjectionManager.createScreenCaptureIntent()`),而且授权结果只能
// 通过 Activity 的 onActivityResult 拿回来 —— 没有别的路子。
//
// 而我们的设置页是一个 Composable(在 MainActivity 里),Compose 没有
// 「startActivityForResult」的等价物(要自己接 ActivityResultLauncher)。
//
// 所以这里放一个**透明**的 Activity:它起来 → 弹系统授权框 → 拿到结果 →
// 把 MediaProjection 起起来 → 自己关掉。用户看到的只有系统的那个弹窗。
//
// ## 为什么现在还留着这条路
//
// 无障碍自带的截图(AccessibilityService.takeScreenshot,Android 11+)不用授权,
// 更好。但:
//   - Android 10 及以下没有那个 API;
//   - 用户的系统可能关了无障碍。
// 所以 MediaProjection 这条留着当兜底。
package io.github.hotmanxp.lanagent.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.github.hotmanxp.lanagent.engine.ScreenCaptureService

/**
 * 透明的截屏授权 Activity。
 *
 * 流程:弹系统授权框 → 用户同意 → [ScreenCaptureService.start] 拿授权 →
 * 关闭自己。不同意就直接关掉(不弹任何错误 —— 用户是主动点的)。
 */
class ScreenCaptureConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val consent = ScreenCaptureService.consentIntent(this)
        if (consent == null) {
            Log.w(TAG, "拿不到截屏授权 Intent(系统不支持?)")
            finish()
            return
        }
        try {
            startActivityForResult(consent, REQ_CAPTURE)
        } catch (e: Throwable) {
            Log.w(TAG, "弹截屏授权失败", e)
            finish()
        }
    }

    @Deprecated("startActivityForResult 在新版本被弃用,但这是拿 MediaProjection 授权结果最直接的路")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Log.i(TAG, "拿到截屏授权,起捕获服务")
            ScreenCaptureService.start(this, resultCode, data)
        } else {
            Log.i(TAG, "用户没给截屏授权")
        }
        finish()
    }

    companion object {
        private const val TAG = "ScreenCaptureConsent"
        private const val REQ_CAPTURE = 0x5343 // 'SC'
    }
}
