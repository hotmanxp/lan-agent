// PhoneCapabilities.kt — 手机能力的实际实现(跑在 App 主进程)。
//
// ## 为什么是「主进程」而不是 :engine
//
// 引擎在 :engine 进程里,那儿拿不到 Context(拿不到 PackageManager /
// NotificationManager / MediaProjection)。这些都要主进程来做,所以能力桥
// 是主进程上的一个 HTTP 服务,引擎通过 loopback 调过来。
//
// ## 每一项的授权门槛
//
//  Android 的权限模型决定了「能不能做」不由我们决定,系统决定:
//
//  - 剪贴板 / 打开 App / 列 App / 读设置:普通权限,装了就能用。
//  - 通知列表:必须注册 NotificationListenerService,而且用户得在系统设置里
//    给「通知使用权」 —— 系统不让我们代劳。
//  - 截屏:必须用户点一次系统的截屏授权弹窗(MediaProjection)。
//  - 写系统设置:需要「修改系统设置」这个特殊权限,同样得用户去设置里开。
//
//  所以每项在缺授权时返回的是**可执行的提示**(去哪开),不是一句「失败」。
package io.github.hotmanxp.lanagent.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * 手机能力实现表。
 *
 * 单例 + 一个 Context:能力服务在主进程起,系统 API 全都要 Context。
 */
object PhoneCapabilities {

    private const val TAG = "PhoneCapabilities"

    @Volatile
    private var app: Context? = null

    /** 由 [CapabilityService.ensureStarted] 调用一次。 */
    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun ctx(): Context = app ?: error("能力桥还没初始化")

    // ── 剪贴板 ──────────────────────────────────────────────────────────────

    /**
     * 读剪贴板。
     *
     * Android 10+ 规定「后台 App 不能读剪贴板」—— 这里是 App 在前台
     * (用户在聊天)时才被调,正常情况读得到。读不到就返回空字符串,
     * 不报错(有些 ROM 对空剪贴板也报「敏感」)。
     */
    fun clipboard(args: JSONObject): JSONObject {
        val cm = ctx().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return err("拿不到剪贴板服务")
        val clip = runCatching { cm.primaryClip }.getOrNull()
        if (clip == null || clip.itemCount == 0) {
            return ok(JSONObject().put("text", "").put("empty", true))
        }
        val text = (0 until clip.itemCount).joinToString("\n") { i ->
            clip.getItemAt(i)?.text?.toString().orEmpty()
        }
        return ok(JSONObject().put("text", text).put("empty", text.isEmpty()))
    }

    // ── 应用列表 / 启动 ─────────────────────────────────────────────────────

    /**
     * 列手机上装了的 App。
     *
     * ## 为什么默认只返回「用户装的」
     *
     * 一台国产 ROM 上装满之后 `getInstalledPackages(0)` 会返回 300+ 个包,
     * 其中 90% 是系统组件(ColorOS / 高通 / AOSP 那一坨)。全量丢给模型,
     * 模型只能**靠包名前缀猜**哪些是用户装的 —— 猜错的直接表现为
     * 「你手机上一共装了 278 个包,真正你手动装的第三方 App 只有 8 个左右」
     * 这种看着有板有眼、实际是编的结论(0.29.6 修的就是这个)。
     *
     * 所以分类由系统给,不由模型猜:
     *  - `ApplicationInfo.FLAG_SYSTEM` → 系统预装
     *  - `FLAG_UPDATED_SYSTEM_APP` → 预装但被用户更新过(浏览器之类)
     *  - 其余 → 用户装的
     *
     * ## scope
     *
     *  - `user`(默认)→ 只列用户装的
     *  - `system` → 只列系统预装的
     *  - `all` → 全列
     *
     * 包可见性:清单里声明了 QUERY_ALL_PACKAGES(Android 11+ 不声明的话
     * 系统会把查询结果过滤成「可见包」,列表静默不全)。这 App 是侧载自用,
     * 不上 Play,那个政策顾虑不成立。
     */
    fun listApps(args: JSONObject): JSONObject {
        val pm = ctx().packageManager
        val query = args.optString("query", "").trim()
        val scope = args.optString("scope", "user").trim().lowercase().let {
            if (it == "all" || it == "system") it else "user"
        }
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrElse { e ->
            return err("列应用失败:${e.message}")
        }
        val launchable = runCatching { launcherPackages(pm) }.getOrDefault(emptySet())

        var userCount = 0
        var systemCount = 0
        val rows = ArrayList<JSONObject>()
        for (pi in packages) {
            val ai = pi.applicationInfo ?: continue
            val pkg = ai.packageName ?: continue
            val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem =
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val userInstalled = !isSystem
            if (userInstalled) userCount++ else systemCount++

            val inScope = when (scope) {
                "all" -> true
                "system" -> isSystem
                else -> userInstalled
            }
            if (!inScope) continue

            val label = runCatching { pm.getApplicationLabel(ai).toString() }
                .getOrDefault(pkg)
            if (query.isNotEmpty() &&
                !label.contains(query, ignoreCase = true) &&
                !pkg.contains(query, ignoreCase = true)
            ) {
                continue
            }
            rows.add(
                JSONObject()
                    .put("package", pkg)
                    .put("name", label)
                    .put("userInstalled", userInstalled)
                    .put("updatedSystemApp", updatedSystem)
                    .put("launchable", pkg in launchable)
                    .put("versionName", pi.versionName ?: "")
                    .put("firstInstallTime", pi.firstInstallTime)
            )
        }
        // 用户装的、能从桌面点开的排前面,按应用名排 —— 模型读到的顺序
        // 就是人读 App 列表的顺序。
        rows.sortWith(
            compareByDescending<JSONObject> { it.optBoolean("userInstalled") }
                .thenByDescending { it.optBoolean("launchable") }
                .thenBy { it.optString("name") }
        )
        val out = JSONArray()
        rows.forEach { out.put(it) }
        return ok(
            JSONObject()
                .put("scope", scope)
                .put("apps", out)
                .put("count", out.length())
                .put("totalInstalled", packages.size)
                .put("userInstalledCount", userCount)
                .put("systemAppCount", systemCount)
        )
    }

    /** 有桌面图标的包(用户能点开的那些)。 */
    private fun launcherPackages(pm: android.content.pm.PackageManager): Set<String> =
        runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            ).map { it.activityInfo?.packageName }.filterNotNull().toSet()
        }.getOrDefault(emptySet())

    /**
     * 按包名启动 App。
     *
     * FLAG_ACTIVITY_NEW_TASK 是必须的:能力服务不是 Activity,没有 task。
     */
    fun openApp(args: JSONObject): JSONObject {
        val pkg = args.optString("package", "").trim()
        if (pkg.isEmpty()) return err("要传 package")
        val pm = ctx().packageManager
        val intent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent == null) return err("手机上没有装 $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { ctx().startActivity(intent) }.onFailure { e ->
            return err("启动 $pkg 失败:${e.message}")
        }
        return ok(JSONObject().put("package", pkg).put("launched", true))
    }

    /**
     * 打开某个系统权限的授权页。
     *
     * 「修改系统设置」这类是**特殊权限**(appop)—— 代码代劳不了,只能把用户
     * 带到系统页,让他自己拨开关。以前 agent 只能口头说「去设置里开」,
     * 用户得自己在设置里翻三层菜单找;现在一步跳到对应的那个页面。
     *
     * 支持的 permission:
     *   - write_settings —— 「修改系统设置」(写亮度 / 息屏 / 飞行模式等)
     *   - notification_access —— 「通知使用权」(NotificationListenerService)
     *   - accessibility —— 「无障碍」(读屏 / 点按 / 输入)
     *   - post_notifications —— 「通知」权限(Android 13+ 发通知要)
     *
     * ⚠️ **后台起 Activity 会被系统拦**(Android 10+ 的后台启动限制)。
     * 用户在 App 聊天界面时 App 是前台的 → 能起;App 在后台时会被拒 ——
     * 所以这里把失败原因如实报回去,而不是假装跳过去了。
     */
    fun permissionPage(args: JSONObject): JSONObject {
        val which = args.optString("permission", "").trim().lowercase()
        if (which.isEmpty()) {
            return err("要传 permission。可用的有 write_settings / notification_access / accessibility / post_notifications")
        }
        val pkg = ctx().packageName
        val intent = when (which) {
            "write_settings", "settings", "modify_system_settings" -> android.content.Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:$pkg"),
            )
            "notification_access", "notification_listeners", "notification_use" -> android.content.Intent(
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            )
            "accessibility", "a11y" -> android.content.Intent(
                Settings.ACTION_ACCESSIBILITY_SETTINGS,
            )
            "post_notifications", "notifications" -> android.content.Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                android.net.Uri.parse("package:$pkg"),
            )
            else -> return err(
                "不知道的权限:$which。可用的有 write_settings / notification_access / " +
                    "accessibility / post_notifications",
            )
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx().startActivity(intent)
            ok(
                JSONObject()
                    .put("permission", which)
                    .put("opened", true)
                    .put(
                        "note",
                        "已经打开系统权限页。用户拨完开关回来后你可以重试刚才的操作。" +
                            "用 hand_to_user 提醒他一下他就在旁边。",
                    ),
            )
        }.getOrElse { e ->
            err(
                "打开权限页失败:${e.message}。" +
                    "多半是 App 现在在后台 —— Android 不让后台起 Activity。" +
                    "让用户切回 App 再试,或者直接口头告诉他去哪开。",
            )
        }
    }

    /**
     * 写剪贴板。
     *
     * DSH 的 Clipboard 组是 get + set 两个,我们原来只有 get。
     * Android 10+ 读剪贴板在后台被限,但**写**任何时候都行。
     */
    fun clipboardSet(args: JSONObject): JSONObject {
        val text = args.optString("text", "")
        if (text.isEmpty()) return err("要传 text(要写进剪贴板的内容)")
        val cm = ctx().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return err("拿不到剪贴板服务")
        return runCatching {
            cm.setPrimaryClip(ClipData.newPlainText("lan-agent", text))
            ok(JSONObject().put("text", text).put("length", text.length))
        }.getOrElse { e -> err("写剪贴板失败:${e.message}") }
    }

    /**
     * 一个 App 的详细信息。
     *
     * DSH 的 app_info:版本 / 安装时间 / 体积 / 导出的 activity。
     */
    fun appInfo(args: JSONObject): JSONObject {
        val pkg = args.optString("package", "").trim()
        if (pkg.isEmpty()) return err("要传 package")
        val pm = ctx().packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            ?: return err("手机上没有装 $pkg")
        val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
        val versionName = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty()
        val versionCode = runCatching {
            val pi = pm.getPackageInfo(pkg, 0)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        }.getOrDefault(0L)
        val firstInstall = runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L)
        val lastUpdate = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        // 体积:源码目录的大小。拿不到就 0 —— 读不了不是错。
        val sizeBytes = runCatching {
            val src = File(info.sourceDir ?: return@runCatching 0L)
            src.length()
        }.getOrDefault(0L)
        // 导出的 activity:能被别人用 Intent 拉起来的入口。
        val exported = runCatching {
            val arr = JSONArray()
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities
                ?.filter { it.exported }
                ?.take(20)
                ?.forEach { a ->
                    arr.put(
                        JSONObject()
                            .put("name", a.name)
                            .put("label", runCatching { a.loadLabel(pm).toString() }.getOrDefault(a.name)),
                    )
                }
            arr
        }.getOrDefault(JSONArray())
        return ok(
            JSONObject()
                .put("package", pkg)
                .put("name", label)
                .put(
                    "userInstalled",
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                )
                .put("versionName", versionName)
                .put("versionCode", versionCode)
                .put("firstInstallTime", firstInstall)
                .put("lastUpdateTime", lastUpdate)
                .put("sizeBytes", sizeBytes)
                .put("exportedActivities", exported),
        )
    }

    /**
     * 用一个 URL / scheme 打开页面。
     *
     * DSH 的 app_open_link:https / tel / geo / mailto / 各种自定义 scheme。
     * 靠 `Intent.parseUri` 处理 scheme —— 比自己拆 query 稳。
     */
    fun appOpenLink(args: JSONObject): JSONObject {
        val url = args.optString("url", "").trim()
        if (url.isEmpty()) return err("要传 url(比如 https://example.com 或 tel:13800138000)")
        val intent = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull() ?: return err("这个 url 解析不了:$url")
        val target = args.optString("package", "").trim()
        if (target.isNotEmpty()) intent.setPackage(target)
        return runCatching {
            ctx().startActivity(intent)
            ok(JSONObject().put("url", url).put("opened", true).put("package", target))
        }.getOrElse { e ->
            err("打开 $url 失败:${e.message}。这个链接手机上没有 App 能接。")
        }
    }

    /**
     * 发一个显式 Intent。
     *
     * DSH 的 app_send_intent:带 component / action / extras。
     * extras 用 JSON 对象传,值是字符串就当字符串,是数字/布尔就按那个类型放。
     */
    fun appSendIntent(args: JSONObject): JSONObject {
        val pkg = args.optString("package", "").trim()
        val cls = args.optString("class", "").trim()
        if (pkg.isEmpty()) return err("要传 package")
        if (cls.isEmpty()) return err("要传 class(目标 activity 的全名,app_info 的 exportedActivities 里有)")
        val intent = runCatching {
            Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrElse { e -> return err("构造 Intent 失败:${e.message}") }
        val action = args.optString("action", "").trim()
        if (action.isNotEmpty()) intent.action = action
        args.optJSONObject("extras")?.let { obj ->
            obj.keys().forEach { k ->
                when (val v = obj.get(k)) {
                    is Boolean -> intent.putExtra(k, v)
                    is Int -> intent.putExtra(k, v)
                    is Long -> intent.putExtra(k, v)
                    is Double -> intent.putExtra(k, v)
                    is JSONObject -> intent.putExtra(k, v.toString())
                    else -> intent.putExtra(k, v.toString())
                }
            }
        }
        return runCatching {
            ctx().startActivity(intent)
            ok(JSONObject().put("package", pkg).put("class", cls).put("sent", true))
        }.getOrElse { e -> err("发 Intent 失败:${e.message}") }
    }

    /**
     * 把文本或文件分享给别的 App。
     *
     * DSH 的 share_to_app。走系统的 chooser —— 让用户选分享给谁。
     */
    fun shareToApp(args: JSONObject): JSONObject {
        val text = args.optString("text", "")
        val path = args.optString("path", "").trim()
        val title = args.optString("title", "").trim()
        if (text.isEmpty() && path.isEmpty()) return err("要传 text(要分享的文字)或 path(要分享的文件)")
        val send = Intent(Intent.ACTION_SEND).apply {
            if (path.isNotEmpty()) {
                val file = File(path)
                if (!file.exists()) return err("文件不存在:$path")
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx(),
                    "${ctx().packageName}.engine.fileprovider",
                    file,
                )
                type = guessMime(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        val chooser = Intent.createChooser(send, title.ifEmpty { "分享" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx().startActivity(chooser)
            ok(JSONObject().put("shared", true).put("text", text).put("path", path))
        }.getOrElse { e -> err("分享失败:${e.message}") }
    }

    /** 从文件名猜 MIME 类型(粗判,够用)。 */
    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "*/*"
    }

    /**
     * 设备当前状态。
     *
     * DSH 的 device_status:电量 / 充电 / 网络 / Wi-Fi SSID / 存储 / 勿扰 / 各音量流。
     * 这是 agent 回答「我手机怎么了」的基础 —— 没有它,电量/网络/勿扰全瞎。
     */
    fun deviceStatus(args: JSONObject): JSONObject {
        val out = JSONObject()

        // ── 电量 ──
        runCatching {
            val bm = ctx().getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            if (bm != null) {
                val intent = ctx().registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                out.put(
                    "battery",
                    JSONObject()
                        .put("percent", if (level >= 0 && scale > 0) level * 100 / scale else -1)
                        .put("charging", status == android.os.BatteryManager.BATTERY_STATUS_CHARGING)
                        .put("full", status == android.os.BatteryManager.BATTERY_STATUS_FULL),
                )
            }
        }

        // ── 网络 ──
        runCatching {
            val cm = ctx().getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val ncm = cm ?: return@runCatching
            val net = ncm.activeNetwork
            val caps = ncm.getNetworkCapabilities(net)
            out.put(
                "network",
                JSONObject()
                    .put("connected", caps != null)
                    .put("wifi", caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true)
                    .put("cellular", caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true)
                    .put("validated", caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                    .put("type", when {
                        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                        caps != null -> "other"
                        else -> "none"
                    }),
            )
        }

        // ── Wi-Fi SSID ──
        runCatching {
            val wm = ctx().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ssid = wm?.connectionInfo?.ssid?.trim('"').orEmpty()
            if (ssid.isNotEmpty()) out.put("wifiSsid", ssid)
        }

        // ── 存储 ──
        runCatching {
            val stat = android.os.StatFs(ctx().filesDir.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            out.put(
                "storage",
                JSONObject()
                    .put("totalBytes", total)
                    .put("freeBytes", free)
                    .put("usedBytes", total - free)
                    .put("usedPercent", if (total > 0) (total - free) * 100 / total else -1),
            )
        }

        // ── 勿扰 ──
        runCatching {
            val nm = ctx().getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            val filter = nm?.currentInterruptionFilter ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            out.put(
                "dnd",
                JSONObject()
                    .put("on", filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    .put("filter", filter),
            )
        }

        // ── 各音量流 ──
        runCatching {
            val am = ctx().getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null) {
                out.put(
                    "volume",
                    JSONObject()
                        .put("ring", am.getStreamVolume(android.media.AudioManager.STREAM_RING))
                        .put("ringMax", am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING))
                        .put("music", am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                        .put("musicMax", am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC))
                        .put("notification", am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION))
                        .put("alarm", am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)),
                )
            }
        }

        // ── 响铃模式 + 息屏时长 ──
        runCatching {
            val am = ctx().getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            am?.ringerMode?.let { out.put("ringerMode", it) }
            out.put(
                "screenTimeoutMs",
                Settings.System.getInt(ctx().contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1),
            )
        }

        return ok(out)
    }

    // ── 系统设置 ────────────────────────────────────────────────────────────

    /**
     * 读 / 写系统设置。
     *
     * 写的门槛是「修改系统设置」(Settings.System.canWrite)。没有的话
     * 返回怎么去开 —— 这个权限系统只允许用户手动授,不给代码路径。
     */
    fun settings(args: JSONObject): JSONObject {
        val key = args.optString("key", "").trim().lowercase()
        if (key.isEmpty()) return err("要传 key")
        val write = args.optBoolean("write", false)
        val value = args.optString("value", "")

        if (write && !Settings.System.canWrite(ctx())) {
            // 这个权限是 appop 特殊权限,代码代劳不了 —— 只能跳系统页让用户开。
            //
            // 提示里给出**具体动作**:先调 phone_permission_page 把用户带到
            // 系统那个页面(一步),别让他自己在设置里翻三层菜单找。
            return err(
                "还没有「修改系统设置」权限,写不了。" +
                    "这是特殊权限,只能用户手动开 —— " +
                    "先调 phone_permission_page(permission=write_settings)把用户带到那个页面," +
                    "再用 hand_to_user 告诉他要拨哪个开关,然后**停下等用户回话**。" +
                    "别在没授权的情况下反复重试这次写入 —— 结果都一样,只会白烧轮次。",
            )
        }

        return when (key) {
            "brightness" -> {
                if (write) {
                    val v = value.toIntOrNull()?.coerceIn(0, 255)
                        ?: return err("brightness 要 0-255 的整数")
                    // ⚠️ **写亮度必须先关掉自动亮度** —— 自动亮度开着时,系统
                    // 会按环境光重新算,把刚写的值盖掉(表现为「写了没反应」)。
                    //
                    // 这不是假设:agent「把亮度打到最高」时用户看到的就是亮度
                    // 没变 —— 系统把 255 又调回 120。所以写亮度时连带把
                    // SCREEN_BRIGHTNESS_MODE 置 0(手动)。
                    //
                    // 原值一并带回去,方便调用方之后还原。
                    val autoMode = Settings.System.getInt(
                        ctx().contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        0,
                    )
                    Settings.System.putInt(
                        ctx().contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        0,
                    )
                    Settings.System.putInt(ctx().contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                    ok(
                        JSONObject()
                            .put("key", key)
                            .put("value", v)
                            .put("auto_brightness_was", autoMode == 1)
                            .put(
                                "note",
                                if (autoMode == 1) {
                                    "原来开着自动亮度,已经关掉(否则系统会立刻把亮度调回去)。" +
                                        "要还原的话把 auto_brightness 设回 true。"
                                } else {
                                    ""
                                },
                            ),
                    )
                } else {
                    ok(
                        JSONObject()
                            .put("key", key)
                            .put("value", Settings.System.getInt(ctx().contentResolver, Settings.System.SCREEN_BRIGHTNESS)),
                    )
                }
            }

            // 自动亮度开关。写 brightness 时会连带关掉它,所以这个 key 是
            // 「还原」时用的 —— agent 记下 auto_brightness_was,还原时写回来。
            "auto_brightness", "brightness_mode" -> {
                val on = when {
                    write -> value.toBooleanish()
                        ?: return err("$key 的值要 true / false")
                    else -> Settings.System.getInt(
                        ctx().contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        0,
                    ) == 1
                }
                if (write) {
                    Settings.System.putInt(
                        ctx().contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (on) 1 else 0,
                    )
                    ok(JSONObject().put("key", key).put("value", on))
                } else {
                    ok(JSONObject().put("key", key).put("value", on))
                }
            }

            "screen_timeout" -> {
                if (write) {
                    val v = value.toIntOrNull()?.coerceAtLeast(1000)
                        ?: return err("screen_timeout 要毫秒数(至少 1000)")
                    Settings.System.putInt(ctx().contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, v)
                    ok(JSONObject().put("key", key).put("value", v))
                } else {
                    ok(
                        JSONObject()
                            .put("key", key)
                            .put("value", Settings.System.getInt(ctx().contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)),
                    )
                }
            }

            "airplane_mode" -> {
                // ⚠️ 飞行模式的状态在 **Global** 表里,不在 System 表。
                // 原来那段写的是 Settings.System.putInt(AIRPLANE_MODE_ON) ——
                // 写的是另一个表里的另一个键,系统不读,于是「写成功了」但飞行
                // 模式纹丝不动;读也一样:从 System 读永远读到默认值 0。
                //
                // 写 Global 要 WRITE_SETTINGS(已声明 + 用户在系统设置里授权)。
                // 某些 ROM 上 Global 写入仍被拒,那就退回 System 写一次(无害)。
                if (write) {
                    val on = value.toBooleanish() ?: return err("airplane_mode 的值要 true / false")
                    val wrote = runCatching {
                        Settings.Global.putInt(
                            ctx().contentResolver,
                            Settings.Global.AIRPLANE_MODE_ON,
                            if (on) 1 else 0,
                        )
                        true
                    }.getOrElse { e ->
                        Log.w(TAG, "写 Global 飞行模式失败,退回 System", e)
                        runCatching {
                            Settings.System.putInt(
                                ctx().contentResolver,
                                Settings.System.AIRPLANE_MODE_ON,
                                if (on) 1 else 0,
                            )
                        }.getOrDefault(false)
                    }
                    // 光改 Settings 不生效,得发广播让系统重新读。
                    // ACTION_AIRPLANE_MODE 是「请求切换」,ACTION_AIRPLANE_MODE_CHANGED
                    // 是「状态已变」的通知 —— 两个都发,覆盖不同 ROM 的响应方式。
                    runCatching {
                        ctx().sendBroadcast(
                            Intent("android.intent.action.AIRPLANE_MODE")
                                .putExtra("state", on),
                        )
                    }
                    runCatching {
                        ctx().sendBroadcast(
                            Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on),
                        )
                    }
                    ok(
                        JSONObject()
                            .put("key", key)
                            .put("value", on)
                            .put("settings_written", wrote)
                            .put(
                                "note",
                                "飞行模式是系统级设置,部分 ROM 上应用改不动;" +
                                    "如果没反应,系统设置里手动切一下。",
                            ),
                    )
                } else {
                    val on = runCatching {
                        Settings.Global.getInt(
                            ctx().contentResolver,
                            Settings.Global.AIRPLANE_MODE_ON,
                            0,
                        )
                    }.getOrDefault(0) == 1
                    ok(JSONObject().put("key", key).put("value", on))
                }
            }

            "volume" -> {
                val am = ctx().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return err("拿不到音频服务")
                val stream = AudioManager.STREAM_MUSIC
                if (write) {
                    val v = value.toIntOrNull()?.coerceIn(0, am.getStreamMaxVolume(stream))
                        ?: return err("volume 要 0-${am.getStreamMaxVolume(stream)} 的整数")
                    am.setStreamVolume(stream, v, 0)
                    ok(JSONObject().put("key", key).put("value", am.getStreamVolume(stream)))
                } else {
                    ok(JSONObject().put("key", key).put("value", am.getStreamVolume(stream)))
                }
            }

            "ringer_mode" -> {
                val am = ctx().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return err("拿不到音频服务")
                if (write) {
                    val mode = when (value.lowercase()) {
                        "normal" -> AudioManager.RINGER_MODE_NORMAL
                        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                        "silent" -> AudioManager.RINGER_MODE_SILENT
                        else -> return err("ringer_mode 只能是 normal / vibrate / silent")
                    }
                    am.ringerMode = mode
                    ok(JSONObject().put("key", key).put("value", value))
                } else {
                    ok(JSONObject().put("key", key).put("value", am.ringerMode))
                }
            }

            // Wi-Fi 开关在 API 29+ 是 Global 设置,普通应用写不了
            // (WRITE_SETTINGS 也只能写 System 级的)。所以写 = 打开系统的
            // Wi-Fi 设置页让用户自己点 —— 这比假装写成功诚实。
            "wifi" -> {
                if (write) {
                    runCatching {
                        ctx().startActivity(
                            Intent(Settings.ACTION_WIFI_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { e -> return err("打开 Wi-Fi 设置失败:${e.message}") }
                    ok(
                        JSONObject()
                            .put("key", key)
                            .put("value", readWifiState())
                            .put("opened_settings", true)
                            .put(
                                "note",
                                "Wi-Fi 开关是系统级设置,应用改不了。已经帮你打开系统 Wi-Fi 设置页," +
                                    "点一下开关就行。",
                            ),
                    )
                } else {
                    ok(JSONObject().put("key", key).put("value", readWifiState()))
                }
            }

            else -> err(
                "不知道的设置项:$key。可用的有 brightness / auto_brightness / screen_timeout / " +
                    "airplane_mode / volume / ringer_mode / wifi",
            )
        }
    }

    /** Wi-Fi 开着没有。读 Global.WIFI_ON,读不到就当开着(保守)。 */
    private fun readWifiState(): Boolean = runCatching {
        Settings.Global.getInt(ctx().contentResolver, Settings.Global.WIFI_ON, 1) == 1
    }.getOrDefault(true)

    // ── 通知 ────────────────────────────────────────────────────────────────

    /**
     * 读通知栏。
     *
     * 只有 App 自己能通过 NotificationManager 拿到自己的通知;要读**别的
     * App 的**通知,必须注册 NotificationListenerService 并且用户在系统
     * 设置里给了「通知使用权」。没授权就返回一句可执行的提示。
     */
    fun notifications(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        val listener = PhoneNotificationListener
        if (!listener.isConnected()) {
            return err(
                "还没拿到通知使用权。点这句去开:" +
                    "设置 → 应用 → 特殊应用权限 → 通知使用权 → 打开 lan-agent。" +
                    "（这是 Android 的硬性限制,应用不能代替用户点同意）",
            )
        }
        val list = listener.recent(limit)
        val arr = JSONArray()
        list.forEach { rec -> arr.put(rec) }
        return ok(JSONObject().put("notifications", arr).put("count", arr.length()))
    }

    /**
     * 打开一条通知(像点它那样),或者点它上面的某个动作按钮。
     *
     * @param key      notification_list 给的 key
     * @param action   动作按钮标题(可选);不给就点通知本身
     */
    fun notificationOpen(args: JSONObject): JSONObject {
        if (!PhoneNotificationListener.isConnected()) return err("还没拿到通知使用权")
        val key = args.optString("key", "").trim()
        if (key.isEmpty()) return err("要传 key(先用 phone_notifications 拿)")
        val action = args.optString("action", "").trim().ifEmpty { null }
        val err = PhoneNotificationListener.open(key, action)
        return if (err == null) {
            ok(JSONObject().put("key", key).put("action", action ?: "").put("opened", true))
        } else {
            err(err)
        }
    }

    /** 清除通知(清掉通知栏里的那些)。 */
    fun notificationDismiss(args: JSONObject): JSONObject {
        if (!PhoneNotificationListener.isConnected()) return err("还没拿到通知使用权")
        val keys = mutableListOf<String>()
        args.optString("keys", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { keys += it }
        args.optJSONArray("keys")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { keys += it }
        }
        if (keys.isEmpty()) return err("要传 keys(通知的 key;传空数组表示全清)")
        val n = PhoneNotificationListener.dismiss(keys.distinct())
        return ok(JSONObject().put("dismissed", n).put("requested", keys.distinct().size))
    }

    /**
     * 回一条通知(走它的 inline reply —— 微信 / QQ / 短信这类)。
     *
     * 只有带回复输入框的通知才能回;没有就明确说没有。
     */
    fun notificationReply(args: JSONObject): JSONObject {
        if (!PhoneNotificationListener.isConnected()) return err("还没拿到通知使用权")
        val key = args.optString("key", "").trim()
        if (key.isEmpty()) return err("要传 key")
        val text = args.optString("text", "")
        if (text.isEmpty()) return err("要传 text(要回复的内容)")
        val err = PhoneNotificationListener.reply(key, text)
        return if (err == null) {
            ok(JSONObject().put("key", key).put("replied", true))
        } else {
            err(err)
        }
    }

    // ── 截屏 ────────────────────────────────────────────────────────────────

    /**
     * 截屏。
     *
     * **返回的是文件路径,不是图片本身。** 原因:opencc core 对 mod 工具的
     * 返回值强制 JSON.stringify(见 core 的 G2s),所以 mod 没法直接返回
     * image 内容块给模型看。改成「落盘成 PNG + 把路径给模型」,模型用
     * 标准的 Read 工具读那个 PNG —— Read 工具是支持图片的,这是唯一
     * 不改 core 就能让模型「看见屏幕」的路子。
     */
    fun screenshot(args: JSONObject): JSONObject {
        val png = ScreenCaptureService.capturePng()
        if (png == null) {
            return err(
                "还没拿到截屏授权。点这句去开:回到 lan-agent 首页点「授权截屏」," +
                    "在系统弹窗里选「开始录制或投射」。授权一次之后就能一直用。",
            )
        }
        val dir = File(EnginePaths.workspaceDir(ctx()), "screenshots").apply { mkdirs() }
        val name = "screen-${System.currentTimeMillis()}.png"
        val file = File(dir, name)
        file.writeBytes(png)
        Log.i(TAG, "截屏 → ${file.absolutePath}(${png.size} 字节)")
        return ok(
            JSONObject()
                .put("path", file.absolutePath)
                .put("bytes", png.size)
                .put(
                    "note",
                    "图片已经存成 PNG。用 Read 工具读这个路径就能看见屏幕内容。",
                ),
        )
    }

    // ── 屏幕操作(无障碍)────────────────────────────────────────────────────

    /** 跨读稳定的 id 分配器(所有屏幕能力共用一个)。 */
    private val screenIds = StableIds()

    private fun needAccessibility(): String? {
        if (PhoneAccessibilityService.isConnected) return null
        return "还没开无障碍服务。点这句去开:设置 → 无障碍 → 已下载的服务(已安装的应用) " +
            "→ lan-agent → 打开开关。开完 agent 才能读屏、点按、输入。"
    }

    /**
     * 重拍一帧,返回**当前**的帧(元素带新鲜的节点引用)。
     *
     * ⚠️ **动作前必须重拍** —— [android.view.accessibility.AccessibilityNodeInfo]
     * 是**快照**,不是活视图。读屏时存下的 node 引用,等模型反应过来再
     * performAction,页面可能早就变了:
     *
     * - 对**过期快照**做 `ACTION_SET_TEXT`,`performAction` 会**返回 true 但屏幕上
     *   什么都没变**(静默失效,最坏的一种:agent 以为打进去了);
     * - 实测(模拟器 android-14):对着读屏时存的旧节点 setText,输入框里的字没变;
     *   换成当次 `rootInActiveWindow` 上 `findFocus` 拿到的节点就立刻生效。
     *
     * 还有一层延迟:系统派发「窗口内容变了」事件是有延迟的,`rootInActiveWindow`
     * 拿到的是**上一次事件之后**的树 —— 改完立刻读会读到旧值(输入框里刚打的字
     * 读不出来,下一次读才看得到)。所以这里读**两轮**、中间等一下,取第二轮:
     * 第一轮的作用是把「事件还没派发到」这个延迟吃掉。
     */
    private fun freshFrame(): ScreenFrame? {
        val elements = readFreshElements() ?: return null
        return screenIds.assign(PhoneAccessibilityService.foregroundPackage(), null, elements)
    }

    /** 读两轮、中间等一下,取第二轮 —— 拿到的是「事件已派发」之后的那棵树。 */
    private fun readFreshElements(): List<UiElement>? {
        PhoneAccessibilityService.readElements()
        try {
            Thread.sleep(FRESH_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return PhoneAccessibilityService.readElements()
    }

    /**
     * 把一个稳定 id 解析成**当前**的元素(带新鲜节点)。
     *
     * 匹配靠 identity(元素身份),不是下标 —— 页面变了但元素还在时 identity 不变,
     * 能对上;元素真没了就返回 null,让调用方报「不在屏幕上了」。
     */
    private fun freshElement(id: String): UiElement? {
        val norm = StableIds.normalize(id)
        val old = when (val lookup = screenIds.resolve(norm)) {
            is StableIds.Lookup.Found -> screenIds.latest()?.elements?.getOrNull(lookup.index)
            is StableIds.Lookup.Relocated -> screenIds.latest()?.elements?.getOrNull(lookup.index)
            else -> null
        }
        val frame = freshFrame() ?: return null
        if (old != null) {
            val hit = frame.elements
                .filter { it.identity == old.identity }
                .minByOrNull { hypot((it.x - old.x).toDouble(), (it.y - old.y).toDouble()) }
            if (hit != null) return hit
        }
        // identity 对不上(元素内容变了)→ 退化到按 id 找
        return frame.indexOf(norm)?.let { frame.elements.getOrNull(it) }
    }

    /** 当前聚焦的输入框(新鲜节点)。 */
    private fun freshFocusedInput(): UiElement? {
        val frame = freshFrame()
        val focused = frame?.elements?.firstOrNull { it.editable && it.focused }
        if (focused != null) return focused
        val node = PhoneAccessibilityService.instance?.rootInActiveWindow?.findFocus(
            android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT,
        ) ?: return null
        return frame?.elements?.firstOrNull { it.editable && it.node === node }
            ?: frame?.elements?.firstOrNull { it.editable }
    }

    /**
     * 读屏:把当前屏的控件树拍平成给模型看的列表。
     *
     * 元素 id 跨读稳定 —— 模型可以「读一次 → 拿 e12 去点」。
     */
    fun screenRead(args: JSONObject): JSONObject {
        needAccessibility()?.let { return err(it) }
        val maxLines = args.optInt("max", 200).coerceIn(10, 500)
        val elements = readFreshElements() ?: return err("读不到当前屏幕")
        val pkg = PhoneAccessibilityService.foregroundPackage()
        val frame = screenIds.assign(pkg, null, elements)
        return ok(
            JSONObject()
                .put("package", pkg ?: "")
                .put("count", elements.size)
                .put("text", frame.render(maxLines))
                .put("ids", JSONArray(frame.ids))
                .put(
                    "note",
                    "元素 id 跨读稳定(同一元素下次读还是同一个 id)。" +
                        "用 screen_tap 传 id 就能点。",
                ),
        )
    }

    /**
     * 点一下。
     *
     * 三种寻址:元素 id(读屏拿到的 e12)、屏幕上的文字、坐标。
     *
     * 优先用控件的 ACTION_CLICK(比按坐标点准得多 —— 坐标会因动画/滚动偏);
     * 点不动(不可点的容器)才退回坐标手势。
     */
    fun screenTap(args: JSONObject): JSONObject {
        needAccessibility()?.let { return err(it) }
        val id = args.optString("id", "").trim()
        val text = args.optString("text", "").trim()
        val hasCoords = args.has("x") && args.has("y")

        if (id.isNotEmpty()) {
            val el = freshElement(id)
                ?: return err("id $id 指的元素已经不在屏幕上了,重新读一次屏")
            return tapElement(el, id)
        }

        if (text.isNotEmpty()) {
            val elements = freshFrame()?.elements.orEmpty()
            val hit = elements.firstOrNull { it.text == text }
                ?: elements.firstOrNull { it.text.contains(text) || it.desc.contains(text) }
                ?: return err("屏幕上没有找到文字「$text」")
            return tapElement(hit, hit.text)
        }

        if (hasCoords) {
            val x = args.optInt("x")
            val y = args.optInt("y")
            val done = PhoneAccessibilityService.tapAt(x, y)
            return if (done) {
                ok(JSONObject().put("x", x).put("y", y).put("tapped", true))
            } else {
                err("点不了($x,$y):手势派发失败")
            }
        }

        return err("要传 id(读屏拿到的 e12)、text(屏幕上的文字)或 x/y(坐标)")
    }

    /** 点一个元素:先试控件点击,不行再按坐标。 */
    private fun tapElement(el: UiElement, id: String): JSONObject {
        val node = el.node
        // 不可点的容器 → 往上找能点的祖先(最多 6 层)
        var target = node
        var depth = 0
        while (target != null && !target.isClickable && depth < 6) {
            target = target.parent
            depth++
        }
        val clicked = target?.let { n ->
            runCatching { n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrDefault(false)
        } ?: false
        if (clicked) {
            return ok(JSONObject().put("id", id).put("text", el.text).put("clicked", true))
        }
        // 控件点不动(自绘页面 / canvas)→ 按坐标点
        val done = PhoneAccessibilityService.tapAt(el.x, el.y)
        return if (done) {
            ok(
                JSONObject().put("id", id).put("text", el.text)
                    .put("tapped", true).put("by", "coordinates"),
            )
        } else {
            err("点不了「${el.text.ifEmpty { el.type }}」:控件不响应点击,坐标手势也失败了")
        }
    }

    /**
     * 输入文字。
     *
     * 用 ACTION_SET_TEXT(直接设值)而不是模拟键盘事件 —— 前者可靠得多,
     * 而且不用切输入法。
     */
    fun screenType(args: JSONObject): JSONObject {
        needAccessibility()?.let { return err(it) }
        val text = args.optString("text", "")
        if (text.isEmpty()) return err("要传 text")
        val id = args.optString("id", "").trim()

        val el = if (id.isNotEmpty()) {
            freshElement(id)
        } else {
            freshFocusedInput()
        } ?: return err(
            if (id.isEmpty()) "当前没有聚焦的输入框。给个元素 id,或者先 screen_read 找到输入框点一下。"
            else "id $id 对应的元素没了,重新读一次屏",
        )

        val node = el.node ?: return err("id $id 对应的元素拿不到节点了,重新读一次屏")
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (!editable) return err("这个元素不能输入(不是输入框)")

        val arg = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val done = runCatching {
            node.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                arg,
            )
        }.getOrDefault(false)
        if (!done) return err("输入失败:这个输入框不接收 ACTION_SET_TEXT")

        // ⚠️ performAction 返回 true **不等于**字真的进去了 ——
        // 对过期快照它也返回 true。所以这里回读一次确认。
        if (!textAppears(el, text)) {
            return err(
                "输入框没接受这段文字(ACTION_SET_TEXT 静默失效了)。" +
                    "重试一次,或者改用 screen_tap 点一下输入框再输。",
            )
        }
        return ok(JSONObject().put("text", text).put("typed", true))
    }

    /**
     * 回读校验:输入框现在的文字里能看到刚输入的内容吗。
     *
     * ⚠️ 回读比写入**慢一拍**:系统派发「文本变了」事件有延迟,刚 setText 完
     * 立刻回读拿到的还是旧值(实测:字已经进去了,但当场读是空的)。
     * 所以要重试几轮再下结论 —— 否则会把「成功了」误判成「静默失效」。
     */
    private fun textAppears(el: UiElement, text: String): Boolean {
        repeat(4) { attempt ->
            val cur = runCatching { el.node?.text?.toString().orEmpty() }.getOrDefault("")
            if (cur.contains(text)) return true
            val focused = PhoneAccessibilityService.instance?.rootInActiveWindow?.findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT,
            )
            val cur2 = runCatching { focused?.text?.toString().orEmpty() }.getOrDefault("")
            if (cur2.contains(text)) return true
            if (attempt < 3) {
                try {
                    Thread.sleep(80)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    /** 滑动(坐标 → 坐标)。 */
    fun screenSwipe(args: JSONObject): JSONObject {
        needAccessibility()?.let { return err(it) }
        val x1 = args.optInt("x1", Int.MIN_VALUE)
        val y1 = args.optInt("y1", Int.MIN_VALUE)
        val x2 = args.optInt("x2", Int.MIN_VALUE)
        val y2 = args.optInt("y2", Int.MIN_VALUE)
        if (x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE || x2 == Int.MIN_VALUE || y2 == Int.MIN_VALUE) {
            return err("要传 x1/y1/x2/y2")
        }
        val duration = args.optInt("duration", 300).coerceIn(50, 2000).toLong()
        val done = PhoneAccessibilityService.swipe(x1, y1, x2, y2, duration)
        return if (done) {
            ok(JSONObject().put("swiped", true).put("from", "$x1,$y1").put("to", "$x2,$y2"))
        } else {
            err("滑动失败:手势派发失败")
        }
    }

    /** 滚动:优先控件的滚动动作,不行就甩一个滑动手势。 */
    fun screenScroll(args: JSONObject): JSONObject {
        needAccessibility()?.let { return err(it) }
        val forward = args.optString("direction", "forward").lowercase() != "backward"
        val id = args.optString("id", "").trim()

        val node = if (id.isNotEmpty()) {
            freshElement(id)?.node
        } else {
            freshFocusedInput()?.node
        }

        val action = if (forward) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val scrolled = node?.let { n ->
            runCatching { n.performAction(action) }.getOrDefault(false)
        } ?: false
        if (scrolled) return ok(JSONObject().put("scrolled", true).put("direction", if (forward) "forward" else "backward"))

        // 兜底:在手势区域甩一下
        val dm = ctx().resources.displayMetrics
        val cx = dm.widthPixels / 2
        val cy = dm.heightPixels / 2
        val (fromY, toY) = if (forward) (cy + dm.heightPixels / 4) to (cy - dm.heightPixels / 4)
        else (cy - dm.heightPixels / 4) to (cy + dm.heightPixels / 4)
        val done = PhoneAccessibilityService.swipe(cx, fromY, cx, toY, 300)
        return if (done) {
            ok(JSONObject().put("scrolled", true).put("by", "gesture"))
        } else {
            err("滚不动:没有可滚的控件,手势也失败了")
        }
    }

    /**
     * 转滚轮 / 选择器。
     *
     * DSH 的 screen_set_wheel。滚轮(DatePicker / 时间选择器 / 音量条这类)
     * **不是**可滚容器 —— `screen_scroll` 对它无效(ACTION_SCROLL_FORWARD
     * 不会被响应),这就是滚轮必须单独一条能力的原因。
     *
     * 两条路:
     *   1. 元素 id —— 精确。对那个节点做 ACTION_SCROLL_FORWARD/BACKWARD。
     *   2. 不给 id —— 找当前屏幕上的滚轮控件(SeekBar / DatePicker / NumberPicker),
     *      有选中项的就转它。
     *
     * 方向:forward(默认,往后一格)/ backward(往前一格)。
     * 也可以给 value 直接设(ACTION_SET_PROGRESS,SeekBar 才有)。
     */
    fun screenSetWheel(args: JSONObject): JSONObject {
        needAccessibility()?.let { return err(it) }
        val id = args.optString("id", "").trim()
        val forward = args.optString("direction", "forward").lowercase() != "backward"

        // 不给 id:自己找一个滚轮控件。
        val target = if (id.isNotEmpty()) {
            freshElement(id)?.node ?: return err("id $id 指的元素已经不在屏幕上了,重新读一次屏")
        } else {
            val elements = readFreshElements() ?: return err("读不到当前屏幕")
            val wheel = elements.firstOrNull { el ->
                el.type.contains("SeekBar", ignoreCase = true) ||
                    el.type.contains("DatePicker", ignoreCase = true) ||
                    el.type.contains("TimePicker", ignoreCase = true) ||
                    el.type.contains("NumberPicker", ignoreCase = true) ||
                    el.type.contains("Wheel", ignoreCase = true)
            }
            if (wheel == null) {
                return err(
                    "屏幕上没有滚轮控件(SeekBar / DatePicker / TimePicker / NumberPicker)。" +
                        "用 screen_read 看看有什么,或者给个 id。",
                )
            }
            wheel.node ?: return err("那个滚轮控件拿不到节点了,重新读一次屏")
        }

        // 直接设值:SeekBar 才有 ACTION_SET_PROGRESS。
        //
        // ⚠️ ACTION_SET_PROGRESS 挂在 `AccessibilityNodeInfo.AccessibilityAction` 上,
        // **不是** AccessibilityNodeInfo 的静态常量(那个类上没有这个字段)。
        // 用错类名编译就报 unresolved reference。
        val value = args.optInt("value", Int.MIN_VALUE)
        if (value != Int.MIN_VALUE) {
            val arg = android.os.Bundle().apply {
                putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
            }
            val set = runCatching {
                target.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    arg,
                )
            }.getOrDefault(false)
            if (set) return ok(JSONObject().put("set", true).put("value", value).put("by", "set_progress"))
            return err("这个滚轮不支持直接设值(ACTION_SET_PROGRESS 失败了)。用 direction 转一格试试。")
        }

        val action = if (forward) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val turned = runCatching { target.performAction(action) }.getOrDefault(false)
        return if (turned) {
            ok(
                JSONObject()
                    .put("turned", true)
                    .put("direction", if (forward) "forward" else "backward")
                    .put("id", id),
            )
        } else {
            err(
                "这个滚轮转不动(ACTION_SCROLL_FORWARD/BACKWARD 没被响应)。" +
                    "有些选择器只认点击 —— 用 screen_read 找到它里面的选项文字,再 screen_tap 点。",
            )
        }
    }

    /**
     * 按键。
     *
     * 全局动作(返回/主页/最近任务/下拉通知/快捷设置/息屏)是系统给的,直接能发。
     * 但「回车」「退格」这种**按键事件**无障碍发不了(要 INJECT_EVENTS 权限,
     * 那是系统签名权限),所以:
     *   - enter:找屏幕上「搜索 / 确定 / 发送」这类按钮点它;找不到就让模型自己点
     *   - delete:用 ACTION_SET_TEXT 把最后一个字符去掉(等效退格)
     */
    fun screenKey(args: JSONObject): JSONObject {
        needAccessibility()?.let { return err(it) }
        val key = args.optString("key", "").trim().lowercase()
        if (key.isEmpty()) return err("要传 key")

        when (key) {
            "back", "home", "recents", "notifications", "quick_settings", "lock_screen" -> {
                val done = PhoneAccessibilityService.globalAction(key)
                return if (done) ok(JSONObject().put("key", key).put("pressed", true))
                else err("系统拒绝了「$key」")
            }

            "delete" -> {
                val focused = freshFocusedInput()?.node
                    ?: return err("当前没有聚焦的输入框,没什么可删的")
                val cur = runCatching { focused.text?.toString().orEmpty() }.getOrDefault("")
                if (cur.isEmpty()) return err("输入框是空的")
                val arg = android.os.Bundle().apply {
                    putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        cur.dropLast(1),
                    )
                }
                val done = runCatching {
                    focused.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arg,
                    )
                }.getOrDefault(false)
                return if (done) ok(JSONObject().put("key", key).put("text", cur.dropLast(1)))
                else err("删除失败")
            }

            "enter", "search", "submit" -> {
                // 无障碍发不了回车键 —— 找「搜索 / 确定 / 发送 / 提交」这类按钮点它。
                val elements = freshFrame()?.elements.orEmpty()
                val labels = listOf("搜索", "确定", "发送", "提交", "查询", "search", "send", "ok", "go")
                val hit = elements.firstOrNull { el ->
                    labels.any { l -> el.text.equals(l, ignoreCase = true) || el.desc.equals(l, ignoreCase = true) }
                }
                if (hit != null) {
                    val r = tapElement(hit, hit.text)
                    return r.put("key", key).put("by", "on_screen_button")
                }
                return err(
                    "无障碍发不了回车键。屏幕上没找到「搜索 / 确定 / 发送」这类按钮 —— " +
                        "用 screen_read 找到它,然后 screen_tap 点它。",
                )
            }

            else -> return err(
                "不认识的按键:$key。可用的有 back / home / recents / notifications / " +
                    "quick_settings / lock_screen / enter / delete",
            )
        }
    }

    /**
     * 等某个文字出现 / 消失。
     *
     * 比「sleep 然后再看」强:条件一满足就返回。
     */
    fun screenWait(args: JSONObject): JSONObject {
        needAccessibility()?.let { return err(it) }
        val text = args.optString("text", "").trim()
        val pkg = args.optString("package", "").trim()
        if (text.isEmpty() && pkg.isEmpty()) return err("要传 text(等某段文字)或 package(等某个 App 到前台)")
        val gone = args.optBoolean("gone", false)
        val timeout = args.optInt("timeout", 10_000).coerceIn(500, 60_000).toLong()
        val deadline = System.currentTimeMillis() + timeout

        while (System.currentTimeMillis() < deadline) {
            val elements = PhoneAccessibilityService.readElements().orEmpty()
            val hasText = text.isNotEmpty() && elements.any {
                it.text.contains(text, ignoreCase = true) || it.desc.contains(text, ignoreCase = true)
            }
            val hasPkg = pkg.isNotEmpty() && PhoneAccessibilityService.foregroundPackage() == pkg
            val matched = if (text.isNotEmpty()) hasText else hasPkg
            if (matched != gone) {
                return ok(
                    JSONObject()
                        .put("matched", matched)
                        .put("gone", gone)
                        .put("text", screenIds.assign(
                            PhoneAccessibilityService.foregroundPackage(),
                            null,
                            elements,
                        ).render(80)),
                )
            }
            Thread.sleep(400)
        }
        return err("等了 ${timeout}ms 还没等到${if (gone) "消失" else "出现"}:${if (text.isNotEmpty()) "「$text」" else pkg}")
    }

    /**
     * 无障碍截图(不需要用户授权)。
     *
     * 跟 [screenshot] 的区别:那条走 MediaProjection(要用户点系统弹窗),
     * 这条走无障碍自带的 takeScreenshot(Android 11+,零授权)。
     * 拿不到时退回 MediaProjection 那条。
     */
    fun screenCapture(args: JSONObject): JSONObject {
        val bmp = PhoneAccessibilityService.screenshot()
        if (bmp != null) {
            val dir = File(EnginePaths.workspaceDir(ctx()), "screenshots").apply { mkdirs() }
            val file = File(dir, "screen-${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            return ok(
                JSONObject()
                    .put("path", file.absolutePath)
                    .put("by", "accessibility")
                    .put("note", "图片已经存成 PNG。用 Read 工具读这个路径就能看见屏幕内容。"),
            )
        }
        // 退回 MediaProjection
        val fallback = screenshot(args)
        return fallback.put("by", "media_projection")
    }

    /**
     * 把这一步交回用户。
     *
     * DSH 的 hand_to_user:登录 / 验证码 / 支付确认这些**只能人做**的步骤,
     * agent 不该硬试(反复输验证码会被判风控、误点支付不可逆)。
     *
     * 做法:发一条 **heads-up** 通知把用户叫过来,告诉他要做什么。
     * 通知比 Toast 靠谱 —— Toast 在后台会被系统吞掉,而且只有 2 秒。
     *
     * 返回给模型的是「已交接」,不是「完成了」—— 模型这时应该停下,
     * 等用户做完再说(可以用 screen_wait 等某个文字出现)。
     */
    fun handToUser(args: JSONObject): JSONObject {
        val reason = args.optString("reason", "").trim()
        val instruction = args.optString("instruction", "").trim()
        if (reason.isEmpty() && instruction.isEmpty()) {
            return err("要传 reason(为什么要交给用户)或 instruction(要用户做什么)")
        }
        val title = reason.ifEmpty { "需要你操作一下" }
        val body = instruction.ifEmpty { reason }

        val nm = ctx().getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return err("拿不到通知服务")

        // 通知权限(Android 13+):没给的话通知发不出去 —— 那就如实说,
        // 别假装「已经叫用户了」。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = runCatching {
                ctx().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (!granted) {
                return err(
                    "发不了通知:App 没有「通知」权限,没法把你叫过来。" +
                        "去系统设置里给 lan-agent 开通知权限,或者直接在聊天里告诉用户要做什么。",
                )
            }
        }

        val builder = androidx.core.app.NotificationCompat.Builder(ctx(), io.github.hotmanxp.lanagent.LanAgentApp.CHANNEL_ID_HAND_TO_USER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    ctx(),
                    0,
                    android.content.Intent(ctx(), io.github.hotmanxp.lanagent.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        val id = System.currentTimeMillis().toInt()
        return runCatching {
            nm.notify(id, builder.build())
            ok(
                JSONObject()
                    .put("handed_off", true)
                    .put("title", title)
                    .put("instruction", body)
                    .put(
                        "note",
                        "已经发通知把用户叫过来了。**现在停下**:不要再重试刚才那个失败的调用," +
                            "不要再操作屏幕,也别再 sleep 轮询 —— 权限没开的时候重试多少次都一样。" +
                            "告诉用户要做什么、等他回话就行(他回话后你再重试)。",
                    ),
            )
        }.getOrElse { e -> err("发通知失败:${e.message}") }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private fun ok(json: JSONObject): JSONObject = json.put("ok", true)

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    /** "true" / "1" / "yes" / "on" 都算 true。 */
    private fun String.toBooleanish(): Boolean? = when (lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    /**
     * 重拍一帧时中间等的毫秒数。
     *
     * 用来吃掉「系统还没派发窗口内容变更事件」这个延迟 —— 不等的话,
     * 紧接着的第二次读拿到的还是同一份旧树。
     */
    private const val FRESH_SETTLE_MS = 120L
}
