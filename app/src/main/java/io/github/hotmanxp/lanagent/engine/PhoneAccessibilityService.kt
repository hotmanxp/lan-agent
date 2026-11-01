// PhoneAccessibilityService.kt — 无障碍服务:让 agent 能「看着屏幕操作」。
//
// ## 它提供什么
//
// 无障碍服务是 Android 上唯一能让一个 App 合法地「读当前屏幕上有什么 +
// 代替用户点/输/滑」的官方通道。它给的是**控件树**(不是像素),所以:
//
//   - 读屏:拿到每个控件的类型 / 文字 / resource-id / 位置 / 能不能点
//   - 点击:直接对控件 performAction(ACTION_CLICK),比按坐标点准得多
//   - 输入:performAction(ACTION_SET_TEXT),比模拟键盘事件可靠
//   - 滑动/点按:dispatchGesture(Android 7.0+)
//
// ## 门槛
//
// 用户得在系统设置里手动打开「无障碍 → 已安装的服务 → lan-agent」。
// 系统只允许用户授,不给代码路径 —— 所以设置页要放一个入口(见
// LocalEngineSettingsSection)。
//
// ## 为什么用单例 + 静态 instance
//
// 能力桥在主进程,服务也是主进程的(没声明 android:process)→ 同一个进程,
// 直接静态引用即可(跟 DSH-Android 的 DshAccessibilityService 同一路子)。
package io.github.hotmanxp.lanagent.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务本体。
 *
 * 生命周期由系统管:用户在设置里开了才会连上来(onServiceConnected)。
 * 没开时 [instance] 是 null,能力层要返回「去开」的提示而不是崩。
 */
class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneAccessibilityService"

        /** 已连接的服务实例;没开无障碍就是 null。 */
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set

        /** 服务是否已连上。 */
        val isConnected: Boolean get() = instance != null

        /**
         * 读当前屏的元素列表。
         *
         * @return null = 服务没连上
         */
        fun readElements(maxNodes: Int = 400): List<UiElement>? {
            val svc = instance ?: return null
            return svc.collectElements(maxNodes)
        }

        /** 当前前台包名。 */
        fun foregroundPackage(): String? =
            instance?.rootInActiveWindow?.packageName?.toString()

        /**
         * 点一下(坐标)。
         *
         * 无障碍不能直接「点某坐标」—— 只能对控件点。要按坐标点,得用
         * gesture(一小段位移的手势,位移为 0 就是一个点)。
         */
        fun tapAt(x: Int, y: Int): Boolean {
            val svc = instance ?: return false
            return svc.gesture(
                Path().apply { moveTo(x.toFloat(), y.toFloat()) },
                0L, 60L,
            )
        }

        /** 滑动。 */
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
            val svc = instance ?: return false
            val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            return svc.gesture(path, 0L, durationMs)
        }

        /** 全局动作(返回 / 主页 / 最近任务 / 下拉通知 / 快捷设置 / 息屏)。 */
        fun globalAction(action: String): Boolean {
            val svc = instance ?: return false
            val id = when (action) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
                else -> return false
            }
            return runCatching { svc.performGlobalAction(id) }.getOrDefault(false)
        }

        /**
         * 无障碍截图(API 30+)。
         *
         * 比 MediaProjection 强的地方:**不需要用户点授权弹窗**。
         * MediaProjection 那条路要系统弹窗,而且一次授权只对一次前台会话有效。
         */
        fun screenshot(): Bitmap? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val svc = instance ?: return null
            val latch = CountDownLatch(1)
            var result: Bitmap? = null
            var error = 0
            runCatching {
                svc.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    { command -> command.run() },
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            // 系统给的是 HardwareBuffer,不是 Bitmap —— 包一层。
                            // wrapHardwareBuffer 需要 API 26(我们是 minSdk 26 ✅)。
                            result = runCatching {
                                val buffer = screenshot.hardwareBuffer
                                val bmp = Bitmap.wrapHardwareBuffer(
                                    buffer,
                                    android.graphics.ColorSpace.get(
                                        android.graphics.ColorSpace.Named.SRGB,
                                    ),
                                )
                                buffer.close()
                                bmp
                            }.getOrNull()
                            latch.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            error = errorCode
                            latch.countDown()
                        }
                    },
                )
            }.onFailure { latch.countDown() }
            latch.await(3, TimeUnit.SECONDS)
            if (result == null && error != 0) {
                Log.w(TAG, "无障碍截图失败:errorCode=$error")
            }
            return result
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * 把控件树拍平成元素列表。
     *
     * 只收「有信息量」的节点:有文字 / 有描述 / 能点 / 能输 / 能滚 / 有 id。
     * 全收的话一个页面几百个节点,给模型的文本没法看。
     */
    private fun collectElements(maxNodes: Int): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<UiElement>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes * 4) {
            val node = queue.removeFirst() ?: continue
            visited++
            val info = describe(node)
            if (info != null) {
                out += info
                if (out.size >= maxNodes) break
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    /** 节点 → 元素;不值得展示的返回 null。 */
    private fun describe(node: AccessibilityNodeInfo): UiElement? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val resourceId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        val focused = node.isFocused
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        // 空节点(纯容器)不值得展示 —— 除非它能点 / 能输 / 能滚。
        val meaningful = text.isNotEmpty() || desc.isNotEmpty() ||
            clickable || editable || scrollable || resourceId.isNotEmpty()
        if (!meaningful) return null
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val type = (node.className?.toString()?.substringAfterLast('.') ?: "Node")
        // 身份 = 「同一个元素」的判据。
        //
        // ⚠️ **输入框的身份里不能带文字** —— 输入框的 text 一改(agent 自己输入了
        // 一句),身份就变了 → id 变了 → 模型手里那个 id 立刻失效。
        // 所以 editable 的元素去掉 text(对齐 DSH-Android 的 UiElement.identity)。
        val identity = if (editable) {
            listOf(type, resourceId, desc).joinToString("|")
        } else {
            listOf(type, resourceId, text, desc).joinToString("|")
        }
        return UiElement(
            identity = identity,
            type = type,
            text = text,
            desc = desc,
            resourceId = resourceId,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            focused = focused,
            checked = node.isChecked,
            x = rect.centerX(),
            y = rect.centerY(),
            width = rect.width(),
            height = rect.height(),
            node = node,
        )
    }

    /**
     * 派发一个手势。
     *
     * Android 7.0+ 的 dispatchGesture —— 滑动、点按都靠它。
     */
    private fun gesture(path: Path, startTimeMs: Long, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startTimeMs, durationMs))
            .build()
        return runCatching { dispatchGesture(desc, null, null) }.getOrDefault(false)
    }
}
