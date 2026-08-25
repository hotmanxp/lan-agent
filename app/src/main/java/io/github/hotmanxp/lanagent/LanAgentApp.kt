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
    }

    companion object {
        const val CHANNEL_ID_WEBVIEW_KEEPALIVE = "webview_keepalive"
    }
}