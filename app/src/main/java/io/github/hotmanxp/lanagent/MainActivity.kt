// MainActivity.kt — 单activity入口;状态栏保持可见,只隐藏底部导航栏
package io.github.hotmanxp.lanagent

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.hotmanxp.lanagent.data.ThemeMode
import io.github.hotmanxp.lanagent.data.themeModeFlow
import io.github.hotmanxp.lanagent.ui.LanAgentTheme
import io.github.hotmanxp.lanagent.ui.MainScaffold

class MainActivity : ComponentActivity() {

    // 媒体读权限: WebView 进程读系统选择器返回的 content:// 图片 URI 时,
    // 只靠 Intent.FLAG_GRANT_READ_URI_PERMISSION 在部分国产 ROM 上不够,
    // 还要 READ_MEDIA_IMAGES (API 33+) / READ_EXTERNAL_STORAGE (更早版本) 才能
    // 让 FileReader.readAsDataURL 拿到字节,否则选图后 zai 输入框看不到缩略图。
    // 用 system 的标准权限弹窗,用户拒绝也不阻塞 App(只是图可能传不上去)。
    private val mediaPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 用户的接受/拒绝不影响后续流程,WebView 的 onShowFileChooser 在两种
        情况下都能弹系统选择器;只是拒绝时读 content URI 仍可能失败。 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw under status bar so we control the background ourselves; the
        // navigation bar stays in its normal slot.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // Hide only the navigation bar; keep the status bar visible so
            // battery / clock / signal remain readable.
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Make the status-bar background transparent so the app's content
        // paints behind it (used for the home screen, which already pads).
        window.statusBarColor = AndroidColor.TRANSPARENT
        requestMediaPermissionsIfNeeded()
        setContent {
            // 主题模式从 DataStore 读,所以「设置 → 深色」是即时全局生效的,
            // 不需要 recreate()。首帧会用系统默认值,深色用户最多闪一下。
            val context = LocalContext.current
            val mode by context.themeModeFlow().collectAsState(initial = ThemeMode.System)
            val systemDark = isSystemInDarkTheme()
            LanAgentTheme(
                darkTheme = when (mode) {
                    ThemeMode.System -> systemDark
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                },
            ) {
                MainScaffold()
            }
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (needed.isNotEmpty()) {
            mediaPermLauncher.launch(needed.toTypedArray())
        }
    }
}