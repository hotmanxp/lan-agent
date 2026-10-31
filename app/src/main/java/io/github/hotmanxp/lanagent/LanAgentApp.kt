// LanAgentApp.kt — Application subclass; the only reason this exists is to
// register the foreground-service notification channel exactly once per
// process at startup. Without it, Android 8+ silently drops the first
// notification the service tries to post and the user sees no "running in
// background" hint — but the service still runs, so this is purely a UX
// nicety, not a correctness requirement.
package io.github.hotmanxp.lanagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.hotmanxp.lanagent.engine.AppContextHolder
import io.github.hotmanxp.lanagent.engine.CapabilityService
import io.github.hotmanxp.lanagent.engine.EngineController
import io.github.hotmanxp.lanagent.engine.EngineInstaller

class LanAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Channel registration is idempotent — repeated createNotificationChannel
        // calls with the same id are a documented no-op, but we still only
        // do it from Application.onCreate to avoid paying the IPC cost on
        // every Activity recreate.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_WEBVIEW_KEEPALIVE,
                getString(R.string.notif_keepalive_channel_name),
                // LOW: no sound, no heads-up — we don't want the user's phone
                // buzzing just because they switched apps with the WebView
                // open. They can still see the silent notification in the
                // shade. MIN would technically save more attention but on
                // some OEM ROMs (notably MIUI) MIN-tier channels are hidden
                // entirely, defeating the purpose.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_keepalive_channel_desc)
                setShowBadge(false)
            }
        )
        // 本地引擎(:engine 进程)的前台通知通道。跟 keepalive 一个待遇:
        // 常驻但安静 —— 引擎跑着的时候不该用通知声打扰用户。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ENGINE,
                getString(R.string.notif_engine_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_engine_channel_desc)
                setShowBadge(false)
            }
        )

        // 截屏前台服务的通知通道。Android 14 上 MediaProjection 类型的前台
        // 服务必须有对应通道,否则 startForeground 抛 IllegalArgumentException。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_SCREEN_CAPTURE,
                getString(R.string.notif_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_capture_channel_desc)
                setShowBadge(false)
            }
        )

        // 能力桥(主进程的 127.0.0.1:9421):引擎通过它调 Android 的能力
        // (截图 / 通知 / 剪贴板 / 启动 App / 系统设置)。能力实现是静态
        // 方法,拿不到构造参数,所以 Context 单独存一份。
        //
        // 必须在主进程起 —— :engine 进程里也起一份的话,两个进程抢同一个
        // 9421 端口,后起的那个直接 bind 失败。
        AppContextHolder.context = this
        if (EngineController.isMainProcess(this)) {
            CapabilityService.ensureStarted(this)
        }

        // 本地引擎(:engine 进程):App 一开就把它托管起来。
        // 为什么在 Application 里起而不是等用户点:引擎是「打开就能用」的
        // 兜底 —— 冷启动要解包 + 起 Node(几秒),等用户进会话页才起的话,
        // 第一次点会等半天。起不来也不影响别的功能(引擎是可选能力),
        // EngineController 自己按退避重试。
        if (EngineInstaller.isInstalled(this)) {
            EngineController.start(this)
        }
    }

    companion object {
        const val CHANNEL_ID_WEBVIEW_KEEPALIVE = "webview_keepalive"

        /** 本地引擎前台服务的通知通道。 */
        const val CHANNEL_ID_ENGINE = "local_engine"

        /** 截屏前台服务的通知通道。 */
        const val CHANNEL_ID_SCREEN_CAPTURE = "screen_capture"
    }
}