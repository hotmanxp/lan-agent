// MainActivity.kt — 单 Activity 入口;状态栏保持可见,只隐藏底部导航栏
package io.github.hotmanxp.lanagent

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.hotmanxp.lanagent.ui.AppNavHost
import io.github.hotmanxp.lanagent.ui.LanAgentTheme

class MainActivity : ComponentActivity() {

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
        setContent {
            LanAgentTheme {
                AppNavHost()
            }
        }
    }
}