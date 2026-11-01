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
import org.json.JSONArray
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
        instance = this
        Log.i(TAG, "通知使用权拿到了")
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
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
                .put("actions", JSONArray().also { arr -> actionLabels(n).forEach { arr.put(it) } })
        }.getOrNull() ?: return
        synchronized(entries) {
            entries.removeAll { it.optString("key") == record.optString("key") }
            entries.add(record)
            while (entries.size > MAX_CACHED) entries.removeAt(0)
        }
        live.put(n.key, n)
        while (live.size > MAX_CACHED) {
            val oldest = live.keys.firstOrNull() ?: break
            live.remove(oldest)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        synchronized(entries) {
            entries.removeAll { it.optString("key") == key }
        }
        live.remove(key)
    }

    companion object {
        private const val TAG = "PhoneNotifListener"
        private const val MAX_CACHED = 200

        private val entries = CopyOnWriteArrayList<JSONObject>()

        /** 活的 StatusBarNotification —— 动作(打开 / 清除 / 回复)要用原对象。 */
        private val live = java.util.concurrent.ConcurrentHashMap<String, StatusBarNotification>()

        /** 服务实例(系统 bind 上来才有)。 */
        @Volatile
        var instance: PhoneNotificationListener? = null
            private set

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
         * 打开一条通知(像点它那样)。
         *
         * @param actionTitle 点某个动作按钮的标题(比如「标记已读」);null = 点通知本身
         * @return null = 成功;否则是错误原因
         */
        fun open(key: String, actionTitle: String?): String? {
            val sbn = live[key] ?: return "通知已经不在了(可能已经被清掉)"
            val notification = sbn.notification ?: return "这条通知没有可打开的内容"
            val pending = if (actionTitle.isNullOrBlank()) {
                notification.contentIntent
            } else {
                val action = notification.actions?.firstOrNull {
                    it.title?.toString()?.equals(actionTitle, ignoreCase = true) == true
                } ?: return "这条通知上没有「$actionTitle」这个按钮"
                action.actionIntent
            } ?: return "这条通知点不开"
            return runCatching { pending.send() }.exceptionOrNull()?.let {
                "点不开这条通知:${it.message}"
            }
        }

        /**
         * 清除通知。
         *
         * @return 清掉了几条
         */
        fun dismiss(keys: List<String>): Int {
            val svc = instance ?: return 0
            var n = 0
            for (key in keys) {
                runCatching { svc.cancelNotification(key); n++ }
            }
            return n
        }

        /**
         * 回一条通知(走它的 inline reply —— 微信 / QQ / 短信这类)。
         *
         * 只有带 RemoteInput 的动作才能回 —— 那是通知栏里那个「回复」输入框
         * 的后端。没有这种动作就回不了。
         */
        fun reply(key: String, text: String): String? {
            val sbn = live[key] ?: return "通知已经不在了(可能已经被清掉)"
            val notification = sbn.notification ?: return "这条通知不能回"
            val action = notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
                ?: return "这条通知没有可以回复的输入框(只有能回的通知才行)"
            val remoteInput = action.remoteInputs?.firstOrNull() ?: return "这条通知的回复框取不到"
            val svc = instance ?: return "通知使用权没了"
            // ⚠️ 用公开 API 的静态版 `android.app.RemoteInput.addResultsToIntent` ——
            // 实例版 `(Context, Bundle, Intent)` 只在隐藏的
            // `android.service.notification.RemoteInput` 上有,公开 SDK 里没有
            // (SDK 把 getRemoteInputs() 的返回类型标成了 android.app.RemoteInput)。
            val results = android.os.Bundle()
            results.putCharSequence(remoteInput.resultKey, text)
            val fillIn = Intent()
            android.app.RemoteInput.addResultsToIntent(arrayOf(remoteInput), fillIn, results)
            return runCatching { action.actionIntent.send(svc, 0, fillIn) }
                .exceptionOrNull()?.let { "回复失败:${it.message}" }
        }

        /** 通知上有哪些动作按钮(标题 + 是否能回复)。 */
        private fun actionLabels(sbn: StatusBarNotification): List<String> {
            val actions = sbn.notification?.actions ?: return emptyList()
            return actions.mapNotNull { a ->
                val title = a.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (a.remoteInputs?.isNotEmpty() == true) "$title (可回复)" else title
            }
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
