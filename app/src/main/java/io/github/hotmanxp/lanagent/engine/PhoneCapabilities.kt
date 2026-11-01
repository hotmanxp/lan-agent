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
            // 这个权限是 appop 特殊权限,代码代劳不了 —— 只能跳系统页让用户开。
            // App 自己的设置页里有这一项(跳 ACTION_MANAGE_WRITE_SETTINGS)。
            return err(
                "还没有「修改系统设置」权限,写不了。去「设置 → 应用 → 特殊应用权限 → " +
                    "修改系统设置 → 局域网 Agent」打开(或者在 App 设置页的「手机能力权限」里点「去开启」)。",
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
