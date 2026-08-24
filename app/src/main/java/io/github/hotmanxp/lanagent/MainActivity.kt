// MainActivity.kt — 单 Activity 入口,启用 immersive 全屏
package io.github.hotmanxp.lanagent

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
        // Edge-to-edge: app draws under system bars; WebView fills the screen.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Hide both status bar and navigation bar; swipe to reveal (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            LanAgentTheme {
                AppNavHost()
            }
        }
    }
}