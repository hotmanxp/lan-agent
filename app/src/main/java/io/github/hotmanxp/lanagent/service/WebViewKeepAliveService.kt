// service/WebViewKeepAliveService.kt — Foreground service that holds a
// detached WebView alive after the user backgrounds the app. Without it,
// Activity.onPause freezes the WebView's network stack and any open SSE /
// long-poll / WebSocket session on the LAN zai page silently drops. With
// it, the session keeps running while the user is in another app or the
// screen is off — when they come back, the page is still in the same
// state, no reconnect handshake, no lost messages.
//
// The WebView here is never attached to a View hierarchy. Chromium
// notices this and skips rasterization (there is no surface to draw
// into) but the JS engine and the network stack keep running — which is
// exactly what we want. Memory cost is non-trivial (a few hundred MB for
// the Chromium process); the 30-minute WakeLock timeout below is the
// safety net if the caller forgets to stop the service.
package io.github.hotmanxp.lanagent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import io.github.hotmanxp.lanagent.LanAgentApp
import io.github.hotmanxp.lanagent.R

class WebViewKeepAliveService : Service() {

    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // PARTIAL_WAKE_LOCK keeps the CPU running without lighting the
        // screen — the network stack and JS engine need CPU cycles even
        // when the user has the device in their pocket. acquire(timeout)
        // is a safety net: if the caller crashes or forgets to call stop(),
        // the wake lock releases itself after 30 minutes instead of
        // draining the battery indefinitely. The foreground path
        // (WebViewScreen DisposableEffect.onDispose) is the primary stop
        // signal; this timeout is just belt-and-suspenders.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()

        // startForeground MUST be called within ~5 seconds of
        // startForegroundService() or the system throws
        // ForegroundServiceDidNotTimeOutException and kills the process.
        // We do it as the first statement so there's no chance of an
        // exception in WebView setup blocking it.
        val notification = buildNotification(url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34 (targetSdk 34): the 2-arg startForeground is deprecated
            // and refuses to start a service whose manifest type doesn't
            // match — we pass the type explicitly so Android knows we mean
            // it.
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }

        // Reuse the existing WebView across onStartCommand calls. The same
        // service instance can be re-entered when the user navigates to a
        // different URL within WebViewScreen — creating a fresh WebView
        // each time would leak Chromium processes and balloon memory.
        // We re-load the new URL into the existing instance so any state
        // (cookies, session storage) the user already has sticks around.
        val current = webView
        if (current == null) {
            webView = WebViewFactory.create(this, url)
            Log.i(TAG, "created WebView for url=${url.take(80)}")
        } else {
            current.loadUrl(if (url.isBlank()) "about:blank" else url)
            Log.i(TAG, "reloaded existing WebView with url=${url.take(80)}")
        }

        // START_STICKY: if the OS kills the process under memory pressure,
        // it will re-deliver a null intent when memory recovers. We treat
        // that as "no url" and fall back to about:blank so the service
        // boots cleanly. Note that aggressive OEM ROMs (MIUI / EMUI /
        // ColorOS) will ignore STICKY and kill the service silently —
        // that's a known limitation, mitigated in the foreground by
        // asking users to whitelist the app in system settings.
        return START_STICKY
    }

    private fun buildNotification(url: String): Notification {
        // Tapping the notification re-launches MainActivity. SINGLE_TOP +
        // CLEAR_TOP means a fresh tap while the app is already in the
        // foreground just brings it forward instead of stacking a new
        // instance. FLAG_IMMUTABLE is mandatory on API 31+ for any
        // PendingIntent we don't explicitly create with mutable bits.
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = if (tapIntent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(
                this,
                0,
                tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            null
        }

        val displayUrl = if (url.isBlank()) "保持连接" else url.take(40)
        return NotificationCompat.Builder(this, LanAgentApp.CHANNEL_ID_WEBVIEW_KEEPALIVE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_keepalive_title))
            .setContentText(displayUrl)
            // ongoing = user can't swipe-dismiss; this is a foreground
            // service's main affordance. Silent = no first-show sound.
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    override fun onDestroy() {
        // Reverse order of acquisition: destroy WebView first (frees
        // Chromium's native memory), then release the wake lock.
        // Wrapped in try/catch because some WebView impls throw on
        // destroy() if the View was never attached — losing the service's
        // wake lock would be worse than logging a warning.
        try {
            webView?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView.destroy() threw", e)
        }
        webView = null
        wakeLock?.takeIf { it.isHeld }?.let {
            try {
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock.release() threw", e)
            }
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WebViewKeepAlive"
        private const val NOTIF_ID = 0xA1B2
        private const val WAKE_LOCK_TAG = "lanagent:webview_keepalive"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        const val EXTRA_URL = "extra_url"

        /**
         * Start the foreground service holding [url]'s WebView alive in the
         * background. Must be called while the calling component is in the
         * foreground — on Android 12+ a background Context starting a
         * foreground service throws IllegalStateException.
         */
        fun start(context: Context, url: String) {
            val intent = Intent(context, WebViewKeepAliveService::class.java)
                .putExtra(EXTRA_URL, url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the service (and tear down its WebView + wake lock).
         * Safe to call when the service isn't running — stopService on an
         * unknown service just returns false and does nothing.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, WebViewKeepAliveService::class.java))
        }
    }
}