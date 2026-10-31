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
     * Android 11(API 30)起有「包可见性」过滤:不声明 QUERY_ALL_PACKAGES
     * 就只能看到自己和自己声明过的包。manifest 里声明了 QUERY_ALL_PACKAGES,
     * 所以这里能列全。
     */
    fun listApps(args: JSONObject): JSONObject {
        val pm = ctx().packageManager
        val query = args.optString("query", "").trim()
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrElse { e ->
            return err("列应用失败:${e.message}")
        }
        val out = JSONArray()
        for (info in apps) {
            val pkg = info.packageName ?: continue
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
            if (query.isNotEmpty() &&
                !label.contains(query, ignoreCase = true) &&
                !pkg.contains(query, ignoreCase = true)
            ) {
                continue
            }
            out.put(JSONObject().put("package", pkg).put("name", label))
        }
        return ok(JSONObject().put("apps", out).put("count", out.length()))
    }

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
            return err(
                "还没有「修改系统设置」权限。点这句去开:" +
                    "设置 → 应用 → 特殊应用权限 → 修改系统设置 → 打开 lan-agent",
            )
        }

        return when (key) {
            "brightness" -> {
                if (write) {
                    val v = value.toIntOrNull()?.coerceIn(0, 255)
                        ?: return err("brightness 要 0-255 的整数")
                    Settings.System.putInt(ctx().contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                    ok(JSONObject().put("key", key).put("value", v))
                } else {
                    ok(
                        JSONObject()
                            .put("key", key)
                            .put("value", Settings.System.getInt(ctx().contentResolver, Settings.System.SCREEN_BRIGHTNESS)),
                    )
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
                if (write) {
                    val on = value.toBooleanish() ?: return err("airplane_mode 的值要 true / false")
                    Settings.System.putInt(
                        ctx().contentResolver,
                        Settings.System.AIRPLANE_MODE_ON,
                        if (on) 1 else 0,
                    )
                    // 光改 Settings 不生效,得发广播让系统重新读。
                    runCatching {
                        ctx().sendBroadcast(
                            Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on),
                        )
                    }
                    ok(JSONObject().put("key", key).put("value", on))
                } else {
                    ok(
                        JSONObject()
                            .put("key", key)
                            .put("value", Settings.System.getInt(ctx().contentResolver, Settings.System.AIRPLANE_MODE_ON) == 1),
                    )
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

            else -> err("不知道的设置项:$key。可用的有 brightness / screen_timeout / airplane_mode / volume / ringer_mode / wifi")
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

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private fun ok(json: JSONObject): JSONObject = json.put("ok", true)

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    /** "true" / "1" / "yes" / "on" 都算 true。 */
    private fun String.toBooleanish(): Boolean? = when (lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
}
