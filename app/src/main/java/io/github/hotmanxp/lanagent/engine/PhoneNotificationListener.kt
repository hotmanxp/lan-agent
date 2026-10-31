// PhoneNotificationListener.kt — 通知栏读取。
//
// ## 为什么必须是 NotificationListenerService
//
// NotificationManager.getActiveNotifications() 只能拿到**本 App 自己**发的
// 通知。要读别的 App 的通知(微信、短信、邮件……),系统只给一条路:
// 注册 NotificationListenerService,而且要用户在系统设置里手动给
// 「通知使用权」。
//
// 这个权限系统不让我们代劳 —— 代码里没法弹一个「同意」框。所以这里的
// 体验是:能力调用失败时返回一句「去哪开」,而不是假装能读。
package io.github.hotmanxp.lanagent.engine

import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 通知监听服务。
 *
 * 系统在用户授权后才会 bind 上来 —— `onListenerConnected` 之前,
 * [isConnected] 是 false,能力调用会返回「去开权限」的提示。
 */
class PhoneNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "通知使用权拿到了")
    }

    override fun onListenerDisconnected() {
        connected = false
        entries.clear()
        Log.i(TAG, "通知使用权没了")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val record = runCatching {
            val extras = n.notification?.extras
            val title = extras?.getCharSequence("android.title")?.toString().orEmpty()
            val text = extras?.getCharSequence("android.text")?.toString().orEmpty()
            val appName = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0))
                    .toString()
            }.getOrDefault(n.packageName)
            JSONObject()
                .put("key", n.key)
                .put("package", n.packageName)
                .put("app", appName)
                .put("title", title)
                .put("text", text)
                .put("postTime", n.postTime)
        }.getOrNull() ?: return
        synchronized(entries) {
            entries.removeAll { it.optString("key") == record.optString("key") }
            entries.add(record)
            while (entries.size > MAX_CACHED) entries.removeAt(0)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        synchronized(entries) {
            entries.removeAll { it.optString("key") == key }
        }
    }

    companion object {
        private const val TAG = "PhoneNotifListener"
        private const val MAX_CACHED = 200

        private val entries = CopyOnWriteArrayList<JSONObject>()

        @Volatile
        var connected: Boolean = false
            private set

        /** 系统给的通知使用权拿到了吗。 */
        fun isConnected(): Boolean = connected

        /** 最近的通知(新的在前)。 */
        fun recent(limit: Int): List<JSONObject> = synchronized(entries) {
            entries.takeLast(limit).reversed()
        }

        /**
         * 带用户去系统设置里开「通知使用权」。
         *
         * 只能在有 Activity 的时候调(要能起前台 Activity)。
         */
        fun requestAccess(context: Context) {
            runCatching {
                context.startActivity(
                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "打开通知使用权设置失败", it) }
        }
    }
}
